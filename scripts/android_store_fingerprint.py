#!/usr/bin/env python3
"""Hash canonical values of an isolated QA store without copying its database file."""
import argparse, hashlib, json, pathlib, shlex, subprocess
p=argparse.ArgumentParser();p.add_argument("--serial",required=True);p.add_argument("--avd",required=True);p.add_argument("--output",required=True,type=pathlib.Path);a=p.parse_args()
def adb(*args): return subprocess.check_output(["adb","-s",a.serial,*args],text=True).strip()
assert a.serial.startswith("emulator-") and adb("shell","getprop","ro.boot.qemu.avd_name")==a.avd
version=int(adb("shell",shlex.join(["run-as","dev.notai.tracker","sqlite3","databases/tracker.db","PRAGMA user_version;"])))
result={"schema":"tracker_android_store_fingerprint_v2","roomVersion":version,"tables":{},
        "scope":"Canonical values of the listed tables/columns only; not a raw-file, all-table or device acceptance proof.",
        "manualConfirmationColumnCovered":version>=10,
        "nullCompatibility":"An absent pre-schema10 manualConfirmation and SQL NULL have the same canonical representation. Historical v1 hashes omit non-null confirmations and are not comparable when those exist."}
for table in ["metadata","activity_types","cards","sessions","segments","favorites","measurements","vocabulary","store_generation"]:
    fields="id,generation" if table=="store_generation" else "id,payload" if table in ["metadata","vocabulary"] else "id,position,payload,sessionId,openSlot" if table=="segments" else "id,position,payload"
    query=f"SELECT json_array({fields}) FROM {table} ORDER BY id;"
    if version>=5 and table=="sessions":
        query="SELECT json_array(s.id,s.position,s.status,c.payload,s.note,s.importOrigin) FROM sessions s JOIN history_classifications c ON c.id=s.selectionId ORDER BY s.id;"
    if version>=5 and table=="segments":
        confirmation=",s.manualConfirmation" if version>=10 else ""
        query=f"SELECT json_array(s.id,s.position,s.sessionId,s.openSlot,s.start,s.end,s.durationMs,c.payload,s.anchor,s.uncertain,s.observedUntil,s.limitMs{confirmation}) FROM segments s JOIN history_classifications c ON c.id=s.selectionId ORDER BY s.id;"
    cmd=shlex.join(["run-as","dev.notai.tracker","sqlite3","databases/tracker.db",query])
    proc=subprocess.Popen(["adb","-s",a.serial,"exec-out",cmd],stdout=subprocess.PIPE,text=True)
    h=hashlib.sha256();count=0;raw_payload_bytes=0
    for line in proc.stdout:
        row=json.loads(line)
        if version>=5 and table=="sessions":
            domain={"id":row[0],"status":row[2],"selection":json.loads(row[3]),"note":row[4],"importOrigin":json.loads(row[5]) if row[5] is not None else None}
            row=[row[0],row[1],domain]
        elif version>=5 and table=="segments":
            domain={"id":row[0],"sessionId":row[2],"start":row[4],"end":row[5],"durationMs":row[6],"selection":json.loads(row[7]),
                    "anchor":json.loads(row[8]) if row[8] is not None else None,"uncertain":bool(row[9]),"observedUntil":row[10],"limitMs":row[11]}
            if version>=10 and row[12] is not None:
                domain["manualConfirmation"]=json.loads(row[12])
            row=[row[0],row[1],domain,row[2],row[3]]
        elif table!="store_generation":
            index=1 if table in ["metadata","vocabulary"] else 2
            raw_payload_bytes+=len(row[index].encode())
            row[index]=json.loads(row[index])
        h.update(json.dumps(row,sort_keys=True,ensure_ascii=False,separators=(",",":")).encode()+b"\n");count+=1
    assert proc.wait()==0
    result["tables"][table]={"rows":count,"canonicalValuesSha256":h.hexdigest(),"payloadBytes":raw_payload_bytes}
a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(result,indent=2)+"\n")
print(json.dumps(result))
