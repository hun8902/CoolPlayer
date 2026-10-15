#!/usr/bin/env python3
"""Package a signed APK and optionally publish an Android GitHub release."""
import argparse, hashlib, json, os, pathlib, re, shutil, subprocess, urllib.request, urllib.error, urllib.parse
p=argparse.ArgumentParser()
p.add_argument('--apk',required=True,type=pathlib.Path)
p.add_argument('--build-tools',required=True,type=pathlib.Path)
p.add_argument('--notes',required=True,type=pathlib.Path)
p.add_argument('--out',default='dist/android',type=pathlib.Path)
p.add_argument('--publish',action='store_true')
a=p.parse_args(); repo='hun8902/CoolPlayer'
subprocess.run([str(a.build_tools/'apksigner'),'verify',str(a.apk)],check=True)
badging=subprocess.check_output([str(a.build_tools/'aapt'),'dump','badging',str(a.apk)],text=True)
package=re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",badging)
if not package or package[1]!='com.coolplayer.android': raise SystemExit('Unexpected package')
code=int(package[2]); version=package[3]
if not re.fullmatch(r'\d+\.\d+\.\d+',version): raise SystemExit('Stable x.y.z version required')
minimum=int(re.search(r"sdkVersion:'(\d+)'",badging)[1]); tag='android-v'+version
notes=a.notes.read_text(); a.out.mkdir(parents=True,exist_ok=True)
apk=a.out/'Cool-Player-Android.apk'; shutil.copyfile(a.apk,apk)
digest=hashlib.sha256(apk.read_bytes()).hexdigest()
metadata={'packageName':package[1],'versionCode':code,'versionName':version,'minSdk':minimum,'apkUrl':f'https://github.com/{repo}/releases/download/{tag}/{apk.name}','sha256':digest,'size':apk.stat().st_size,'notes':notes}
(a.out/'android-update.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n')
files=[apk,a.out/'android-update.json']
(a.out/'SHA256SUMS.txt').write_text(''.join(f'{hashlib.sha256(f.read_bytes()).hexdigest()}  {f.name}\n' for f in files))
files.append(a.out/'SHA256SUMS.txt')
print('Prepared',tag,'versionCode',code,flush=True)
if not a.publish: raise SystemExit(0)
# Release tags must already point to the exact reviewed source commit.
subprocess.run(['git','show-ref','--verify','refs/tags/'+tag],check=True,stdout=subprocess.DEVNULL)
token=os.environ.get('GH_TOKEN')
if not token:
 result=subprocess.run(['git','credential','fill'],input='protocol=https\nhost=github.com\n\n',text=True,capture_output=True,check=True)
 token=dict(line.split('=',1) for line in result.stdout.splitlines() if '=' in line).get('password')
if not token: raise SystemExit('GitHub authentication unavailable')
def api(path,method='GET',data=None,raw=None,mime='application/json'):
 url=path if path.startswith('https://') else 'https://api.github.com'+path
 if urllib.parse.urlparse(url).hostname not in ('api.github.com','uploads.github.com'): raise ValueError('Unexpected API host')
 body=raw if raw is not None else json.dumps(data).encode() if data is not None else None
 request=urllib.request.Request(url,data=body,method=method,headers={'Authorization':'Bearer '+token,'Accept':'application/vnd.github+json','Content-Type':mime,'User-Agent':'CoolPlayer-release'})
 with urllib.request.urlopen(request,timeout=180) as response:
  content=response.read(); return json.loads(content) if content else {}
remote=api(f'/repos/{repo}/git/ref/tags/{tag}')
local=subprocess.check_output(['git','rev-parse','refs/tags/'+tag],text=True).strip()
if remote['object']['sha']!=local: raise SystemExit('Remote tag does not match local tag')
try: release=api(f'/repos/{repo}/releases/tags/{tag}')
except urllib.error.HTTPError as e:
 if e.code!=404: raise
 release=api(f'/repos/{repo}/releases','POST',{'tag_name':tag,'name':'Cool Player for Android '+version,'body':notes,'draft':True,'prerelease':False})
if not release['draft']: raise SystemExit('Release already published; use a new version rather than replacing installed artifacts')
base=release['upload_url'].split('{')[0]
for f in files:
 existing=next((item for item in release['assets'] if item['name']==f.name),None)
 if existing:
  expected='sha256:'+hashlib.sha256(f.read_bytes()).hexdigest()
  if existing.get('digest')!=expected: raise SystemExit('Draft asset differs: '+f.name)
  continue
 mime='application/vnd.android.package-archive' if f.suffix=='.apk' else 'application/json' if f.suffix=='.json' else 'text/plain'
 api(base+'?name='+urllib.parse.quote(f.name),'POST',raw=f.read_bytes(),mime=mime)
 print('Uploaded',f.name,flush=True)
release=api(f'/repos/{repo}/releases/{release["id"]}','PATCH',{'draft':False,'make_latest':'true'})
print('Published',release['html_url'],flush=True)
