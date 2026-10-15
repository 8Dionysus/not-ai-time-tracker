from pathlib import Path
import socket,struct,time,json
r=Path('/workspace/tracker-runtime-preflight');status={'transport':'existing emulator-5580 / TCP5581','officialAdbClient':'blocked by read-only home; no HOME/security changes','readOnlyCommands':'getprop sys.boot_completed; dalvikvm64 -version; exit status','authenticatedByProbe':False,'packets':[]}
def packet(name,a,b,data=b''):
 command=int.from_bytes(name.encode(),'little');return struct.pack('<6I',command,a,b,len(data),sum(data)&0xffffffff,command^0xffffffff)+data
def receive(s):
 def exact(n):
  data=b''
  while len(data)<n:
   c=s.recv(n-len(data))
   if not c:raise EOFError('ADB transport closed')
   data+=c
  return data
 header=struct.unpack('<6I',exact(24));assert header[3]<=1024*1024 and header[5]==header[0]^0xffffffff
 data=exact(header[3]);name=header[0].to_bytes(4,'little').decode('ascii');status['packets'].append(name)
 return name,header[1],header[2],data

def connect():
 s=socket.create_connection(('127.0.0.1',5581),timeout=5);s.settimeout(15)
 s.sendall(packet('CNXN',0x01000001,262144,b'host::\x00'))
 name,a,b,data=receive(s)
 if name!='CNXN':s.close();raise RuntimeError('Guest authentication required; no credentials supplied')
 return s
try:
 data=(r/'dex/classes.dex').read_bytes();path=b'/data/local/tmp/tracker-art-preflight.dex,33188'
 with connect() as s:
  s.sendall(packet('OPEN',1,0,b'sync:\x00'));name,remote,local,body=receive(s);assert name=='OKAY'
  sync=b'SEND'+struct.pack('<I',len(path))+path+b'DATA'+struct.pack('<I',len(data))+data+b'DONE'+struct.pack('<I',int(time.time()))
  s.sendall(packet('WRTE',1,remote,sync));saved=False
  for i in range(5):
   name,a,b,body=receive(s)
   if name=='WRTE':
    s.sendall(packet('OKAY',1,a));assert body.startswith(b'OKAY'),body[:200];saved=True;break
  assert saved;s.sendall(packet('CLSE',1,remote))
 with connect() as s:
  cmd=b'shell:printf "\\nTRACKER_DIRECT_BOOT="; getprop sys.boot_completed; CLASSPATH=/data/local/tmp/tracker-art-preflight.dex /apex/com.android.art/bin/dalvikvm64 -showversion TrackerArtProbe; printf "\\nTRACKER_DIRECT_ART_EXIT=%s\\n" $?\x00'
  s.sendall(packet('OPEN',1,0,cmd));output=bytearray()
  while True:
   name,a,b,body=receive(s)
   if name=='WRTE':output+=body;s.sendall(packet('OKAY',1,a))
   elif name=='CLSE':s.sendall(packet('CLSE',1,a));break
  text=output.decode('utf-8','replace');(r/'guest-art-fixture.log').write_text(text)
  status['result']='own synthetic Dex fixture executed through existing guest ADB transport'
  status['fixtureOnlyWrite']='/data/local/tmp/tracker-art-preflight.dex'
  status['artExit0']='TRACKER_DIRECT_ART_EXIT=0' in text
  status['javaMainRan']='TRACKER_ART_VM_RUNNING=' in text
  status['bootCompleted']='TRACKER_DIRECT_BOOT=1' in text
except Exception as e:status['result']=type(e).__name__+': '+str(e)
(r/'guest-art-fixture.json').write_text(json.dumps(status,indent=2)+'\n');print(json.dumps(status))
