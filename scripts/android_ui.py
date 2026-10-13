#!/usr/bin/env python3
"""Inspect/tap an observed UI node on an explicitly selected QA emulator."""
import argparse, pathlib, re, subprocess, xml.etree.ElementTree as ET
p = argparse.ArgumentParser()
p.add_argument("--serial",required=True);p.add_argument("--avd",required=True)
p.add_argument("--evidence",required=True,type=pathlib.Path)
p.add_argument("--target");p.add_argument("--contains",action="store_true")
p.add_argument("--tap",action="store_true");p.add_argument("--up",action="store_true")
p.add_argument("--scrolls",type=int,default=0)
a=p.parse_args()
def adb(*args): return subprocess.run(["adb","-s",a.serial,*args],check=True,capture_output=True,text=True).stdout
assert a.serial.startswith("emulator-") and adb("shell","getprop","ro.boot.qemu.avd_name").strip()==a.avd
a.evidence.parent.mkdir(parents=True,exist_ok=True)
def bounds(node): return tuple(map(int,re.findall(r"\d+",node.get("bounds",""))))
last_adjusted = None
for attempt in range(a.scrolls+1):
    raw=adb("exec-out","uiautomator","dump","/dev/tty")
    a.evidence.with_name(a.evidence.stem+f"-{attempt}.xml").write_text(raw)
    tree=ET.fromstring(raw[raw.index("<?xml"):raw.index("</hierarchy>")+len("</hierarchy>")])
    nodes=list(tree.iter("node"))
    if a.target is None:
        for node in nodes:
            label=node.get("text") or node.get("content-desc") or node.get("resource-id")
            if label: print(label,node.get("bounds"))
        break
    def matches(node):
        labels=[node.get(k,"").casefold() for k in ("text","content-desc","resource-id")]
        return any(a.target.casefold() in s if a.contains else a.target.casefold()==s for s in labels)
    found=[n for n in nodes if matches(n) and n.get("enabled")=="true" and bounds(n)[2]>bounds(n)[0] and bounds(n)[3]>bounds(n)[1]]
    scrollable=[n for n in nodes if n.get("scrollable")=="true"]
    if found:
        assert len(found)==1, "Ambiguous observed target"
        x1,y1,x2,y2=bounds(found[0]);print(found[0].attrib)
        # A partially visible edge-to-edge app node can overlap the system navigation
        # hit area. Move it into the observed scroll viewport before tapping.
        if a.tap and scrollable and attempt<a.scrolls:
            sx1,sy1,sx2,sy2=bounds(scrollable[0]);cy=(y1+y2)//2
            lo=sy1+(sy2-sy1)//4;hi=sy2-(sy2-sy1)//4;sx=(sx1+sx2)//2
            if (cy>hi or cy<lo) and bounds(found[0]) != last_adjusted:
                last_adjusted = bounds(found[0]) # Stop retrying a viewport edge that cannot scroll farther.
                adb("shell","input","swipe",str(sx),str(hi if cy>hi else lo),str(sx),str((hi-min(150,max(40,(cy-(sy1+sy2)//2)//2))) if cy>hi else (lo+min(150,max(40,((sy1+sy2)//2-cy)//2)))),"500")
                continue
        if a.tap: adb("shell","input","tap",str((x1+x2)//2),str((y1+y2)//2))
        break
    if attempt==a.scrolls or not scrollable: raise SystemExit("Target not present after observed scrolling")
    x1,y1,x2,y2=bounds(scrollable[0]);x=(x1+x2)//2;lo=y1+(y2-y1)//4;hi=y2-(y2-y1)//4
    adb("shell","input","swipe",str(x),str(lo if a.up else hi),str(x),str(hi if a.up else lo),"300")
