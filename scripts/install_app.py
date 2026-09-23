#!/usr/bin/env python3
"""Install the built launcher, grant its explicit media features, and set HOME."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
from device import select_r1, require_evidence_serial

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'com.kevtrinh.rabbitphone'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    serial = select_r1(mutation=True)
    apk = ROOT / 'build/rabbit-phone.apk'
    if not apk.is_file():
        raise SystemExit('Build the APK with scripts/build.py first.')
    environment = dict(os.environ, RABBIT_ADB_SERIAL=serial)
    subprocess.run([sys.executable, str(ROOT / 'scripts/device_profile.py'), 'backup'],
                   env=environment, check=True)
    subprocess.run(['adb', '-s', serial, 'install', '-r', '--no-incremental', str(apk)], check=True)
    for permission in ['android.permission.CAMERA', 'android.permission.RECORD_AUDIO',
                       'android.permission.POST_NOTIFICATIONS']:
        subprocess.run(['adb', '-s', serial, 'shell', 'pm', 'grant', PACKAGE, permission], check=True)
    # Save the previous special-access grant once; it does not change brightness.
    grant_backup = ROOT / 'evidence/navigation-write-settings-before.json'
    if grant_backup.exists():
        require_evidence_serial(json.loads(grant_backup.read_text()), serial, grant_backup)
    else:
        previous = subprocess.check_output(['adb', '-s', serial, 'shell', 'cmd', 'appops',
            'get', PACKAGE, 'WRITE_SETTINGS'], text=True).strip()
        grant_backup.write_text(json.dumps({'device_serial': serial, 'write_settings': previous}, indent=2) + '\n')
    subprocess.run(['adb', '-s', serial, 'shell', 'cmd', 'appops', 'set', PACKAGE,
                    'WRITE_SETTINGS', 'allow'], check=True)
    timer_backup = ROOT / 'evidence/timer-exact-alarm-before.json'
    if timer_backup.exists():
        require_evidence_serial(json.loads(timer_backup.read_text()), serial, timer_backup)
    else:
        previous = subprocess.check_output(['adb', '-s', serial, 'shell', 'cmd', 'appops',
            'get', PACKAGE, 'SCHEDULE_EXACT_ALARM'], text=True).strip()
        timer_backup.write_text(json.dumps({'device_serial': serial,
            'schedule_exact_alarm': previous}, indent=2) + '\n')
    subprocess.run(['adb', '-s', serial, 'shell', 'cmd', 'appops', 'set', PACKAGE,
                    'SCHEDULE_EXACT_ALARM', 'allow'], check=True)
    subprocess.run(['adb', '-s', serial, 'shell', 'cmd', 'package', 'set-home-activity',
                    PACKAGE + '/.HomeActivity'], check=True)
    print('Installed Rabbit Phone. Media capture still requires a user gesture.')


if __name__ == '__main__':
    main()
