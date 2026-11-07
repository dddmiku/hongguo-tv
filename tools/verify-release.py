"""Verify the independent TV source tag, downloaded APK and app update feed."""
import hashlib
import json
from pathlib import Path
import publish
from github import request,token

version,code=publish.version()
expected=Path('app/build/outputs/apk/release')/('hongguo-tv-'+version+'.apk')
publish.verify_signature(expected)
digest=hashlib.sha256(expected.read_bytes()).hexdigest()
status,release=request('GET',publish.API+'/releases/latest',token())
assert status==200 and release['tag_name']=='v'+version and not release['draft']
apk=next(a for a in release['assets'] if a['name']=='hongguo-tv-'+version+'.APK')
assert apk['size']==expected.stat().st_size and apk.get('digest')=='sha256:'+digest
status,download=request('GET',apk['browser_download_url'])
assert status==200 and hashlib.sha256(download).hexdigest()==digest
status,feed=request('GET',next(a for a in release['assets'] if a['name']=='latest.json')['browser_download_url'])
if isinstance(feed,bytes):feed=json.loads(feed)
assert status==200 and feed['version']==version and set(feed['platforms'])=={'android-tv'}
tv=feed['platforms']['android-tv']
assert tv['versionCode']==code and tv['sha256']==digest and tv['url']==apk['browser_download_url']
print(json.dumps({'repository':publish.REPO,'tag':release['tag_name'],'versionCode':code,'size':len(download),'sha256':digest,'independentUpdateFeed':True}))
