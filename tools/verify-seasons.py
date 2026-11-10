"""Isolated, silent TV season tests. Credentials and upstream signed URLs are never reported."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[1]
ADB=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'AppData/Local/Android/Sdk')))/'platform-tools/adb.exe'
WORK=ROOT/'_work/season-device';WORK.mkdir(parents=True,exist_ok=True)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--serial',default='127.0.0.1:16384');parser.add_argument('--install',action='store_true');parser.add_argument('--live',action='store_true');parser.add_argument('--details',action='store_true');args=parser.parse_args()
    def adb(*parts,timeout=40):return subprocess.run([str(ADB),'-s',args.serial,*parts],capture_output=True,encoding='utf-8',errors='replace',timeout=timeout)
    if args.install:
        apks=sorted((ROOT/'app/build/outputs/apk/debug').glob('*-qa-debug.apk'),key=lambda item:item.stat().st_mtime)
        tests=ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
        if not apks or not tests.is_file():raise RuntimeError('Build the independent QA and test APKs first')
        for package in (apks[-1],tests):
            result=adb('install','-r',str(package),timeout=90)
            if result.returncode or 'Success' not in result.stdout:raise RuntimeError('Isolated QA package installation failed')
    # Volume is intentionally zero before any MediaPlayer can start.
    adb('shell','cmd','media_session','volume','--stream','3','--set','0')
    classes=['cn.hongguo.tv.ui.PlayerSeasonDeviceTest']
    label='controlled'
    if args.details:classes=['cn.hongguo.tv.ui.SeriesSeasonsDeviceTest'];label='details'
    if args.live:classes=['cn.hongguo.tv.ui.PlayerSeasonLiveDeviceTest'];label='live'
    result=adb('shell','am','instrument','-w','-r','-e','class',','.join(classes),'cn.hongguo.tv.qa.test/android.test.InstrumentationTestRunner',timeout=420)
    output=result.stdout+result.stderr
    match=re.search(r'OK \((\d+) tests?\)',output)
    passed=bool(match) and not any(value in output for value in ('FAILURES!!!','Process crashed','INSTRUMENTATION_FAILED'))
    report={'passed':passed,'tests':int(match.group(1)) if match else 0,'classes':classes,'isolatedPackage':True,'mediaMuted':True}
    screenshot='seasons-details' if args.details else 'seasons-player' if args.live else None
    if passed and screenshot:
        pulled=adb('pull','/sdcard/Android/data/cn.hongguo.tv.qa/files/'+screenshot+'.png',str(WORK/(screenshot+'.png')))
        report['screenshotCaptured']=pulled.returncode==0
    if not passed:
        # Stack traces contain controlled test assertions, never network bodies.
        errors=re.findall(r'INSTRUMENTATION_STATUS: stack=(.*?)(?=INSTRUMENTATION_STATUS:|INSTRUMENTATION_RESULT:)',output,re.S)
        report['assertions']=[re.sub(r'https?://\S+','[address removed]',value)[:1400] for value in errors]
    (WORK/(label+'-result.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False),flush=True)
    return 0 if passed else 1

if __name__=='__main__':sys.exit(main())
