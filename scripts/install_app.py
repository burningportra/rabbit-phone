#!/usr/bin/env python3
"""Install the built launcher, grant its explicit media features, and set HOME."""
import argparse
import os
from pathlib import Path
import subprocess
import sys
from device import select_r1

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
    for permission in ['android.permission.CAMERA', 'android.permission.RECORD_AUDIO']:
        subprocess.run(['adb', '-s', serial, 'shell', 'pm', 'grant', PACKAGE, permission], check=True)
    subprocess.run(['adb', '-s', serial, 'shell', 'cmd', 'package', 'set-home-activity',
                    PACKAGE + '/.HomeActivity'], check=True)
    print('Installed Rabbit Phone. Media capture still requires a user gesture.')


if __name__ == '__main__':
    main()
