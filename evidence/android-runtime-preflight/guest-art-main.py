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

status['readOnlyCommands']='execute already staged own Dex main, without -showversion (version flag exits before main)'
output=bytearray()
try:
 with socket.create_connection(('127.0.0.1',5581),timeout=3) as s:
  s.settimeout(15);s.sendall(packet('CNXN',0x01000001,262144,b'host::\x00'));name,a,b,data=receive(s);assert name=='CNXN'
  command=b'shell:CLASSPATH=/data/local/tmp/tracker-art-preflight.dex /apex/com.android.art/bin/dalvikvm64 TrackerArtProbe; printf "\\nTRACKER_ART_MAIN_EXIT=%s\\n" $?\x00'
  s.sendall(packet('OPEN',1,0,command))
  while True:
   name,a,b,data=receive(s)
   if name=='WRTE':
    output+=data;(r/'guest-art-main.log').write_bytes(output);s.sendall(packet('OKAY',1,a))
   elif name=='CLSE':s.sendall(packet('CLSE',1,a));break
  status['result']='guest ART main probe completed'
except Exception as e:status['result']=type(e).__name__+': '+str(e)
text=output.decode('utf-8','replace');status['javaMainRan']='TRACKER_ART_VM_RUNNING=' in text;status['exit0']='TRACKER_ART_MAIN_EXIT=0' in text
(r/'guest-art-main.json').write_text(json.dumps(status,indent=2)+'\n');print(json.dumps(status))
