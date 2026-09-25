#!/usr/bin/env python3
"""Copy every Beats sound and the Beats state off the R1 into a dated folder outside Git."""
import argparse
from datetime import datetime
from pathlib import Path
import subprocess

from device import select_r1

PACKAGE = 'com.kevtrinh.rabbitphone'
SOURCE = '/sdcard/Android/data/' + PACKAGE + '/files/beats'
DEFAULT_BACKUP = Path('/Volumes/1tb/r1-firmware/beats-backup')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--to', type=Path, default=DEFAULT_BACKUP,
                        help='backup root; a dated folder is created inside (default: %(default)s)')
    args = parser.parse_args()
    serial = select_r1()
    present = subprocess.run(['adb', '-s', serial, 'shell', 'ls', SOURCE],
                             capture_output=True, text=True, timeout=30)
    if present.returncode != 0:
        raise SystemExit('There are no Beats sounds on the R1 yet.')
    destination = args.to.expanduser() / datetime.now().strftime('%Y%m%d-%H%M%S')
    destination.mkdir(parents=True)
    subprocess.run(['adb', '-s', serial, 'pull', SOURCE, str(destination)], check=True, timeout=600)
    sounds = sorted((destination / 'beats').rglob('*.wav'))
    print('Backed up ' + str(len(sounds)) + ' sound files to ' + str(destination / 'beats'))


if __name__ == '__main__':
    main()
