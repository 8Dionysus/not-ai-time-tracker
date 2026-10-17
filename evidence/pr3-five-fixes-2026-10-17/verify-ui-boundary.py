"""Verify that only filter metadata plumbing changed in the existing goal editor."""
import json,subprocess,hashlib
from pathlib import Path
root=Path(__file__).resolve().parents[2]
base='ed5fd5fded38f0371fb9bbf5c0d73d4cf7c5ddde'
editor='app/src/main/kotlin/dev/notai/tracker/android/ui/Editors.kt'
old=subprocess.check_output(['git','-C',str(root),'show',base+':'+editor],text=True)
new=(root/editor).read_text()
restored=new.replace('    var activityChanged by remember { mutableStateOf(false) }\n','').replace('; activityChanged = true','').replace(', target, initial?.originalFilter, activityChanged),',', target),')
assert restored==old,'Editor changed beyond the explicitly allowed filter metadata plumbing'
changed=subprocess.check_output(['git','-C',str(root),'diff',base,'--name-only'],text=True).splitlines()
production={p for p in changed if '/src/main/' in p}
expected={'core/src/main/kotlin/dev/notai/tracker/Schedule.kt','app/src/main/kotlin/dev/notai/tracker/android/LongRecordActions.kt',editor,'app/src/main/kotlin/dev/notai/tracker/android/ui/TrackerViewModel.kt'}
assert production==expected,production
paths=subprocess.check_output(['git','-C',str(root),'ls-files','-z'],text=True).split('\0')
rows=[[p,hashlib.sha256((root/p).read_bytes()).hexdigest()] for p in sorted(p for p in paths if p and not p.startswith('evidence/')) if (root/p).is_file()]
source_patch=subprocess.check_output(['git','-C',str(root),'diff','--binary','--full-index',base,'--','app/src','core/src'])
print(json.dumps({'status':'PASS','editor_rendering_structure_restored_exactly':True,'production_file_allowlist_matches':True,'resources_navigation_styles_texts_unchanged':True,'method':'source comparison; no pixel or physical-device claim','source_input_sha256':hashlib.sha256(json.dumps(rows,separators=(',',':')).encode()).hexdigest(),'source_patch_sha256':hashlib.sha256(source_patch).hexdigest(),'production_files':{p:hashlib.sha256((root/p).read_bytes()).hexdigest() for p in sorted(production)},'identity':'actual working-tree bytes including tests; HEAD alone is not the identity'},indent=2))
