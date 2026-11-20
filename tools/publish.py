"""Publish an immutable TV release and update feed in the dedicated TV repository."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from urllib.parse import quote
from github import request, token

ROOT=Path(__file__).resolve().parents[1]
REPO="dddmiku/hongguo-tv"
API="https://api.github.com/repos/"+REPO
SIGNING_CERT="7009c6ca8eedca64e259e16964e4112f6b181aaf7e47d9680a8d05b33af364e8"

def version():
    source=(ROOT/"app/build.gradle").read_text(encoding="utf-8")
    return re.search(r'versionName\s+"([0-9.]+)"',source).group(1),int(re.search(r'versionCode\s+(\d+)',source).group(1))

def feed(tv_version, code, apk, notes):
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+",tv_version) or isinstance(code,bool) or not isinstance(code,int) or code<1:
        raise ValueError("TV 版本号不正确")
    if apk.name!="hongguo-tv-"+tv_version+".APK":raise ValueError("安装包名称与版本不一致")
    digest=hashlib.sha256(apk.read_bytes()).hexdigest()
    platform={"version":tv_version,"versionCode":code,"url":"https://github.com/"+REPO+"/releases/download/v"+tv_version+"/"+apk.name,"size":apk.stat().st_size,"sha256":digest,"notes":notes,"supportedAbis":["arm64-v8a"]}
    return {"version":tv_version,"sha256":digest,"notes":notes,"repository":"https://github.com/"+REPO,"platforms":{"android-tv":platform}}

def call(method,path,secret,**kwargs):
    status,data=request(method,API+path,secret,**kwargs)
    if status not in (200,201,204):raise RuntimeError("GitHub 操作失败 HTTP "+str(status))
    return data

def require_draft(release):
    if release.get("draft") is not True:
        raise RuntimeError("该版本已公开，拒绝替换已发布资产；应递增版本号")

def find_release(tag, secret):
    status, release = request("GET", API + "/releases/tags/" + tag, secret)
    if status == 200:
        return release
    if status != 404:
        raise RuntimeError("查询发布版本失败 HTTP " + str(status))
    # The tag endpoint may omit drafts. Reuse the authenticated listing after interruption.
    for page in range(1, 11):
        items = call("GET", f"/releases?per_page=100&page={page}", secret)
        if not isinstance(items, list):
            raise RuntimeError("无法核对发布草稿")
        matches = [item for item in items if item.get("tag_name") == tag]
        if len(matches) > 1:
            raise RuntimeError("该版本存在多个发布，已停止")
        if matches or len(items) < 100:
            return matches[0] if matches else None
    raise RuntimeError("发布列表过长，无法确定版本是否已存在")

def verified_assets(release, paths):
    for path in paths:
        assets = [asset for asset in release.get("assets", []) if asset.get("name") == path.name]
        digest = "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()
        if (len(assets) != 1 or assets[0].get("state") != "uploaded"
                or assets[0].get("size") != path.stat().st_size or assets[0].get("digest") != digest):
            raise RuntimeError("草稿资产未通过完整校验：" + path.name)

def verify_signature(apk):
    sdk=os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        properties=ROOT/"local.properties"
        if properties.is_file():
            match=re.search(r"^sdk.dir=(.+)$",properties.read_text(encoding="utf-8"),re.M)
            if match:sdk=match.group(1).strip().replace("\\\\","\\").replace("\\:",":")
    tools=sorted((Path(sdk)/"build-tools").glob("*/apksigner.bat")) if sdk else []
    if not tools:raise RuntimeError("找不到 apksigner，无法确认安装包能覆盖原 TV 版")
    checked=subprocess.run([str(tools[-1]),"verify","--print-certs",str(apk)],capture_output=True,text=True)
    if checked.returncode!=0 or SIGNING_CERT not in checked.stdout.lower():raise RuntimeError("正式安装包签名不匹配原 TV 版，已停止发布")

def upload(release,path,secret):
    require_draft(call("GET","/releases/"+str(release["id"]),secret))
    digest="sha256:"+hashlib.sha256(path.read_bytes()).hexdigest()
    current=call("GET","/releases/"+str(release["id"])+"/assets",secret)
    old=next((asset for asset in current if asset["name"]==path.name),None)
    if old:
        if old.get("state") == "uploaded" and old.get("digest")==digest and old["size"]==path.stat().st_size:return
        require_draft(call("GET","/releases/"+str(release["id"]),secret))
        call("DELETE","/releases/assets/"+str(old["id"]),secret)
    url=release["upload_url"].split("{")[0]+"?name="+quote(path.name)
    status,asset=request("POST",url,secret,raw=path.read_bytes(),content_type="application/octet-stream")
    if status!=201 or asset.get("size")!=path.stat().st_size or asset.get("digest") not in (None,digest):raise RuntimeError("上传资产校验失败")
    print("已上传：",path.name,asset["size"],"bytes",flush=True)

def publish(source_commit):
    if subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT).strip():
        raise RuntimeError("源码仍有未提交改动，停止发布")
    current = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    if source_commit != current:
        raise RuntimeError("发布提交与当前源码不一致，停止发布")
    tv_version,code=version();tag="v"+tv_version;secret=token()
    apk=ROOT/"app/build/outputs/apk/release"/("hongguo-tv-"+tv_version+".apk")
    if not apk.is_file():raise RuntimeError("正式签名安装包尚未生成")
    verify_signature(apk)
    notes=(ROOT/"docs/release-notes.md").read_text(encoding="utf-8")
    staging=ROOT/"_work/release";staging.mkdir(parents=True,exist_ok=True)
    public_apk=staging/("hongguo-tv-"+tv_version+".APK");shutil.copyfile(apk,public_apk)
    latest=staging/"latest.json";latest.write_text(json.dumps(feed(tv_version,code,public_apk,notes),ensure_ascii=False,indent=2),encoding="utf-8")
    sums=staging/"SHA256SUMS.txt";sums.write_text(hashlib.sha256(public_apk.read_bytes()).hexdigest()+"  "+public_apk.name+"\n",encoding="utf-8")
    remote=call("GET","/commits/"+source_commit,secret)
    if remote.get("sha")!=source_commit:raise RuntimeError("正式源码提交尚未推送")
    release = find_release(tag, secret)
    if release is None:
        release=call("POST","/releases",secret,data={"tag_name":tag,"target_commitish":source_commit,"name":"红果客厅 TV "+tv_version,"body":notes,"draft":True,"prerelease":False})
    require_draft(release)
    ref_status,ref=request("GET",API+"/git/ref/tags/"+tag,secret)
    if ref_status==200 and ref.get("object",{}).get("sha")!=source_commit:raise RuntimeError("该草稿已有不同源码标签，请检查后再发布")
    if ref_status not in (200, 404):
        raise RuntimeError("无法核对草稿源码标签 HTTP " + str(ref_status))
    for path in (public_apk,latest,sums):upload(release,path,secret)
    uploaded = call("GET", "/releases/"+str(release["id"]), secret)
    require_draft(uploaded)
    verified_assets(uploaded, (public_apk, latest, sums))
    done=call("PATCH","/releases/"+str(release["id"]),secret,data={"draft":False,"make_latest":"true","target_commitish":source_commit,"body":notes,"name":"红果客厅 TV "+tv_version})
    if done.get("draft"):raise RuntimeError("发布未完成")
    print("已发布：",done["html_url"],flush=True)

if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("--source-commit",required=True);args=parser.parse_args()
    if not re.fullmatch(r"[a-f0-9]{40}",args.source_commit):raise SystemExit("源码提交格式不正确")
    try:publish(args.source_commit)
    except Exception as e:print("发布停止：",str(e),file=sys.stderr);raise SystemExit(1)
