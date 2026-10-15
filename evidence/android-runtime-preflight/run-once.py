from pathlib import Path
import os,sys,json,subprocess,time,pty,select,signal,re
r=Path('/workspace/tracker-runtime-preflight');sdk=r/'sdk'
with (r/'launch-issued.json').open('x') as f:json.dump({'issuedAtUtc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'maximumSeconds':600,'singleAttempt':True},f)
avd=r/'avd/tracker-aosp36.avd';avd.mkdir(exist_ok=True)
(r/'avd/tracker-aosp36.ini').write_text('avd.ini.encoding=UTF-8\npath='+str(avd)+'\ntarget=android-36\n')
(avd/'config.ini').write_text('''AvdId=tracker-aosp36
avd.ini.encoding=UTF-8
PlayStore.enabled=false
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=2
hw.ramSize=2048
hw.lcd.width=480
hw.lcd.height=800
hw.lcd.density=160
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader
hw.audioInput=no
hw.audioOutput=no
hw.camera.back=none
hw.camera.front=none
image.sysdir.1=system-images/android-36/default/x86_64/
tag.id=default
tag.display=Default Android System Image
disk.dataPartition.size=2048M
fastboot.forceColdBoot=yes
showDeviceFrame=no
''')
env=os.environ.copy();env.update(json.loads((r/'process-environment.json').read_text()))
(r/'service/runtime').mkdir(exist_ok=True,mode=0o700);env['XDG_RUNTIME_DIR']=str(r/'service/runtime')
cmd=[str(sdk/'emulator/emulator'),'-avd','tracker-aosp36','-no-window','-no-audio','-no-snapshot','-no-boot-anim','-gpu','swiftshader','-accel','off','-cores','2','-memory','2048','-no-metrics','-port','5580','-shell','-verbose']
(r/'command.json').write_text(json.dumps({'argv':cmd,'environment':{k:v for k,v in env.items() if k in json.loads((r/'process-environment.json').read_text()) or k=='XDG_RUNTIME_DIR'},'homeChanged':False},indent=2)+'\n')
master,slave=pty.openpty();started=time.monotonic();p=subprocess.Popen(cmd,stdin=slave,stdout=slave,stderr=slave,env=env,start_new_session=True);os.close(slave)
status={'pid':p.pid,'phase':'running','bootCompleted':False,'artExecuted':False,'adbAvailable':False,'elapsedSeconds':0,'exitCode':None}
(r/'emulator.pid').write_text(str(p.pid)+'\n')
text='';lastProbe=0;artSent=False;finished=False
try:
 with (r/'emulator.log').open('wb') as log:
  while time.monotonic()-started<600:
   if select.select([master],[],[],1)[0]:
    try:data=os.read(master,65536)
    except OSError:data=b''
    if data:
     log.write(data);log.flush();text=(text+data.decode('utf-8','replace'))[-4_000_000:]
   if p.poll() is not None:status['phase']='process-exited';break
   elapsed=time.monotonic()-started
   if elapsed>30 and elapsed-lastProbe>20 and not status['bootCompleted']:
    os.write(master,b"printf '\\nTRACKER_PREFLIGHT_BOOT='; getprop sys.boot_completed\n");lastProbe=elapsed
   if re.search(r'(?:^|[\r\n])TRACKER_PREFLIGHT_BOOT=1(?:[\r\n]|$)',text):status['bootCompleted']=True
   if status['bootCompleted'] and not artSent:
    os.write(master,b"printf '\\nTRACKER_PREFLIGHT_FINGERPRINT='; getprop ro.build.fingerprint; printf '\\nTRACKER_PREFLIGHT_ABI='; getprop ro.product.cpu.abi; /apex/com.android.art/bin/dalvikvm64 -version; printf '\\nTRACKER_PREFLIGHT_ART_EXIT=%s\\n' $?\n")
    artSent=True
   if 'TRACKER_PREFLIGHT_ART_EXIT=0' in text:
    status['artExecuted']=True
    try:
     a=subprocess.run([str(sdk/'platform-tools/adb'),'-P','5039','devices','-l'],env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=10)
     (r/'adb-devices.log').write_bytes(a.stdout);status['adbExit']=a.returncode
     status['adbAvailable']=a.returncode==0 and b'emulator-5580' in a.stdout and b'offline' not in a.stdout
    except subprocess.TimeoutExpired:status['adbExit']='timeout'
    status['phase']='preflight-complete';finished=True;break
   status['elapsedSeconds']=round(elapsed,3);(r/'status.json').write_text(json.dumps(status,indent=2)+'\n')
  else:status['phase']='deadline'
finally:
 status['elapsedSeconds']=round(time.monotonic()-started,3)
 if p.poll() is None:
  os.killpg(p.pid,signal.SIGTERM)
  try:p.wait(timeout=10)
  except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait(timeout=10)
 status['exitCode']=p.returncode;status['stoppedAfterBoundedPreflight']=True
 os.close(master);(r/'status.json').write_text(json.dumps(status,indent=2)+'\n');print(json.dumps(status))
