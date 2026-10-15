from pathlib import Path
import urllib.request,xml.etree.ElementTree as E,json,hashlib,zipfile,sys,os
root=Path('/workspace/tracker-runtime-preflight');sdk=root/'sdk';sdk.mkdir(exist_ok=True)
mode=sys.argv[1];tree=E.parse(root/('repository.xml' if mode=='emulator' else 'images.xml')).getroot()
path='emulator' if mode=='emulator' else 'system-images;android-36;default;x86_64'
pkg=next(p for p in tree.findall('remotePackage') if p.get('path')==path and p.find('channelRef').get('ref')=='channel-0')
licenseid=pkg.find('uses-license').get('ref');license=next(l for l in tree.findall('license') if l.get('id')==licenseid).text
(root/(mode+'-license.txt')).write_text(license)
licenses=sdk/'licenses';licenses.mkdir(exist_ok=True)
(licenses/licenseid).write_text(hashlib.sha1(license.strip().encode()).hexdigest()+'\n')
archive=next(a for a in pkg.findall('archives/archive') if mode!='emulator' or a.findtext('host-os')=='linux')
complete=archive.find('complete');url='https://dl.google.com/android/repository/'+('' if mode=='emulator' else 'sys-img/android/')+complete.findtext('url')
file=root/(mode+'.zip');expected=int(complete.findtext('size'));digest=complete.findtext('checksum')
if not file.exists() or file.stat().st_size!=expected:
 with urllib.request.urlopen(url,timeout=60) as remote,file.open('wb') as out:
  while data:=remote.read(4*1024*1024):out.write(data)
assert file.stat().st_size==expected
sha1=hashlib.sha1();sha256=hashlib.sha256()
with file.open('rb') as src:
 while data:=src.read(4*1024*1024):sha1.update(data);sha256.update(data)
assert sha1.hexdigest()==digest
with zipfile.ZipFile(file) as z:
 assert z.testzip() is None
 target=sdk if mode=='emulator' else sdk/'system-images/android-36/default'
 target.mkdir(parents=True,exist_ok=True)
 for info in z.infolist():
  name=Path(info.filename);assert not name.is_absolute() and '..' not in name.parts
  z.extract(info,target)
  perms=(info.external_attr>>16)&0o777
  if perms:os.chmod(target/info.filename,perms)
revision='.'.join(x.text for x in pkg.find('revision'))
record={'package':path,'revision':revision,'channel':'stable','url':url,'bytes':expected,'repositorySha1':digest,'sha256':sha256.hexdigest(),'license':licenseid,'licenseSha256':hashlib.sha256(license.encode()).hexdigest(),'authorization':'Sentinel_2820110c26c88191a3c47ba511480ca6 / 2026-10-15 19:24:12 UTC; one official Emulator+AOSP test, SDK terms','sdk':str(sdk)}
(root/(mode+'-installation.json')).write_text(json.dumps(record,indent=2)+'\n');print(json.dumps(record))
