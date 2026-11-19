"""Run isolated TV remote, search, focus, and local-image regressions."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
ADB = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'AppData/Local/Android/Sdk'))) / 'platform-tools/adb.exe'
CLASSES = [
    'cn.hongguo.tv.ui.PlayerControlsDeviceTest',
    'cn.hongguo.tv.ui.SearchAndFocusDeviceTest',
    'cn.hongguo.tv.ui.ImageLoaderDeviceTest',
    'cn.hongguo.tv.ui.HomeResumeDeviceTest',
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='127.0.0.1:16384')
    parser.add_argument('--install', action='store_true')
    parser.add_argument('--label', choices=['baseline', 'final'], default='final')
    args = parser.parse_args()
    work = ROOT / '_work/tv-optimize'
    work.mkdir(parents=True, exist_ok=True)

    def adb(*parts, timeout=40):
        return subprocess.run([str(ADB), '-s', args.serial, *parts], capture_output=True,
                              encoding='utf-8', errors='replace', timeout=timeout)

    connected = adb('get-state')
    if connected.returncode or connected.stdout.strip() != 'device':
        raise RuntimeError('Target QA device is not connected')
    if args.install:
        packages = sorted((ROOT / 'app/build/outputs/apk/debug').glob('*-qa-debug.apk'),
                          key=lambda item: item.stat().st_mtime)
        test_package = ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
        if not packages or not test_package.is_file():
            raise RuntimeError('Build isolated Debug and AndroidTest APKs first')
        for package in (packages[-1], test_package):
            result = adb('install', '-r', str(package), timeout=90)
            if result.returncode or 'Success' not in result.stdout:
                raise RuntimeError('Isolated QA APK installation failed')
    volume = adb('shell', 'cmd', 'media_session', 'volume', '--stream', '3', '--set', '0')
    if volume.returncode:
        raise RuntimeError('Cannot mute the QA device before playback checks')
    result = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', ','.join(CLASSES),
                 'cn.hongguo.tv.qa.test/android.test.InstrumentationTestRunner', timeout=240)
    output = result.stdout + result.stderr
    match = re.search(r'OK \((\d+) tests?\)', output)
    count = match or re.search(r'Tests run:\s*(\d+)', output) or re.search(r'INSTRUMENTATION_STATUS: numtests=(\d+)', output)
    passed = result.returncode == 0 and bool(match) and not any(value in output for value in (
        'FAILURES!!!', 'Process crashed', 'INSTRUMENTATION_FAILED'))
    failures = re.findall(r'INSTRUMENTATION_STATUS: stack=(.*?)(?=INSTRUMENTATION_STATUS:|INSTRUMENTATION_RESULT:)', output, re.S)
    summary = {'passed': passed, 'tests': int(count.group(1)) if count else 0, 'failures': len(failures),
               'isolatedPackage': True, 'mediaMuted': True, 'classes': CLASSES,
               'assertions': [re.sub(r'https?://\S+', '[address removed]', value)[:1800] for value in failures]}
    (work / (args.label + '-experience-result.json')).write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False), flush=True)
    return 0 if passed else 1


if __name__ == '__main__':
    sys.exit(main())
