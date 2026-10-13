#!/usr/bin/env python3
"""Repeated debug-build startup timings on an explicitly selected test emulator."""
import argparse, json, pathlib, re, subprocess, time

p = argparse.ArgumentParser()
p.add_argument("--serial", required=True)
p.add_argument("--avd", required=True)
p.add_argument("--label", required=True)
p.add_argument("--output", type=pathlib.Path, required=True)
p.add_argument("--count", type=int, default=5)
a = p.parse_args()
assert 1 <= a.count <= 20
package = "dev.notai.tracker"

def adb(*args):
    return subprocess.run(["adb", "-s", a.serial, *args], check=True, text=True, capture_output=True).stdout

assert a.serial.startswith("emulator-")
assert adb("shell", "getprop", "ro.boot.qemu.avd_name").strip() == a.avd, "Wrong test AVD"
assert adb("shell", "run-as", package, "id").strip(), "Debug package required"
a.output.mkdir(parents=True, exist_ok=True)
samples = []
pattern = re.compile(r"phase=(\S+) elapsedNs=(\d+) processStartMs=(\d+) pid=(\d+)(?: durationNs=(\d+))?")
for mode in ("cold-process", "warm-process-new-activity"):
    previous_pid = None
    for i in range(a.count):
        if mode == "cold-process":
            adb("shell", "am", "force-stop", package)
        else:
            previous_pid = adb("shell", "pidof", package).strip()
            adb("shell", "input", "keyevent", "4")
        adb("logcat", "-c")
        result = adb("shell", "am", "start", "-W", "-n", package+"/dev.notai.tracker.android.MainActivity")
        deadline = time.monotonic()+60
        while True:
            logs = adb("logcat", "-d", "-s", "TrackerStartup:I", "*:S")
            matches = list(pattern.finditer(logs))
            phases = {m[1]: {"elapsedNs": int(m[2]), "processStartMs": int(m[3]), "pid": int(m[4]),
                             **({"durationNs": int(m[5])} if m[5] else {})} for m in matches}
            if "action-first-frame" in phases:
                break
            assert time.monotonic() < deadline, "Real action-ready frame did not arrive"
            time.sleep(.25)
        ready = phases["action-first-frame"]
        if previous_pid is not None:
            assert str(ready["pid"]) == previous_pid, "Warm sample unexpectedly lost process"
            assert "store-open-start" not in phases, "Warm sample reopened store"
        create = phases["activity-create"]["elapsedNs"]
        sample = {"mode": mode, "iteration": i, "phases": phases,
                  "processStartToActionFrameNs": ready["elapsedNs"]-ready["processStartMs"]*1_000_000,
                  "activityCreateToActionFrameNs": ready["elapsedNs"]-create,
                  "actionReadyToFrameNs": ready["elapsedNs"]-phases["action-ready"]["elapsedNs"]}
        if "history-graph-ready" in phases:
            sample["processStartToHistoryGraphNs"] = phases["history-graph-ready"]["elapsedNs"]-ready["processStartMs"]*1_000_000
        (a.output/f"{a.label}-{mode}-{i}.log").write_text(result+"\n"+logs)
        samples.append(sample)
        print(json.dumps({"label": a.label, "mode": mode, "iteration": i,
                          "activityToActionFrameMs": sample["activityCreateToActionFrameNs"]/1e6}), flush=True)
        (a.output/f"{a.label}.json").write_text(json.dumps({"schema": "tracker_android_startup_samples_v1",
            "label": a.label, "serial": a.serial, "avd": a.avd, "countPerMode": a.count,
            "noDiscardedWarmups": True, "noOSPageCacheEviction": True,
            "warmProcessStartMetricIsProcessAge": True, "samples": samples}, indent=2)+"\n")
