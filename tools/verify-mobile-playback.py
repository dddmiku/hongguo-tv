"""Run actual TV model and decoder checks; record only safe assertion summaries."""
import argparse,json,os,re,subprocess,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
WORK=ROOT/'_work/mobile-playback';WORK.mkdir(parents=True,exist_ok=True)
ADB=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'AppData/Local/Android/Sdk')))/'platform-tools/adb.exe'
parser=argparse.ArgumentParser();parser.add_argument('--install',action='store_true');parser.add_argument('--abi',choices=['arm64-v8a','armeabi-v7a']);parser.add_argument('--serial',default='127.0.0.1:16384');args=parser.parse_args()
def adb(*parts,timeout=40):return subprocess.run([str(ADB),'-s',args.serial,*parts],capture_output=True,encoding='utf-8',errors='replace',timeout=timeout)
if args.install:
    apks=sorted((ROOT/'app/build/outputs/apk/debug').glob('*-qa-debug.apk'),key=lambda item:item.stat().st_mtime)
    for file in [apks[-1],ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
        options=['--abi',args.abi] if args.abi and file==apks[-1] else []
        result=adb('install','-r',*options,str(file),timeout=90)
        if result.returncode or 'Success' not in result.stdout:raise RuntimeError('QA installation failed')
adb('shell','cmd','media_session','volume','--stream','3','--set','0')
result=adb('shell','am','instrument','-w','-r','-e','class','cn.hongguo.tv.media.MobilePlaybackDeviceTest','cn.hongguo.tv.qa.test/android.test.InstrumentationTestRunner',timeout=420)
output=result.stdout+result.stderr;match=re.search(r'OK \((\d+) tests?\)',output)
passed=bool(match) and not any(item in output for item in ['FAILURES!!!','Process crashed','INSTRUMENTATION_FAILED'])
errors=re.findall(r'INSTRUMENTATION_STATUS: stack=(.*?)(?=INSTRUMENTATION_STATUS:|INSTRUMENTATION_RESULT:)',output,re.S)
report={'passed':passed,'tests':int(match.group(1)) if match else 0,'mediaMuted':True,'isolatedPackage':True,'requestedAbi':args.abi,
    'assertions':[re.sub(r'https?://\S+','[address removed]',item)[:1000] for item in errors]}
if passed:
    screenshot=adb('pull','/sdcard/Android/data/cn.hongguo.tv.qa/files/mobile-episode54.png',str(WORK/'episode54.png'))
    report['screenshotCaptured']=screenshot.returncode==0
(WORK/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(report,ensure_ascii=False));sys.exit(not passed)
