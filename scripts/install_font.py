#!/usr/bin/env python3
"""Install the owner's stock Rabbit font privately; never bundle or publish it."""
import argparse
import hashlib
from pathlib import Path
import shlex
import subprocess

from device import select_r1

STOCK_SHA256 = 'd6ad38cde62d42278205c5134c59a3b094d39d600e3bb42dc8c55d6241e1f343'
DESTINATION = '/data/user/0/com.kevtrinh.rabbitphone/files/fonts'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('font', type=Path, help='PowerGroteskRegular.otf from your stock firmware')
    font = parser.parse_args().font.resolve()
    if hashlib.sha256(font.read_bytes()).hexdigest() != STOCK_SHA256:
        raise RuntimeError('This is not the verified stock Rabbit Power Grotesk Regular font')
    serial = select_r1(mutation=True)

    def adb(*args):
        return subprocess.check_output(['adb', '-s', serial, *args], text=True, timeout=30).strip()

    def shell(*args):
        return adb('shell', shlex.join(args))

    if shell('id', '-u') != '0':
        raise RuntimeError('The private font installer requires root ADB on the tested R1')
    owner = shell('stat', '-c', '%u:%g', '/data/user/0/com.kevtrinh.rabbitphone')
    shell('mkdir', '-p', DESTINATION)
    shell('chown', owner, DESTINATION)
    shell('chmod', '700', DESTINATION)
    staged = DESTINATION + '/PowerGrotesk-Regular.otf.new'
    final = DESTINATION + '/PowerGrotesk-Regular.otf'
    adb('push', str(font), staged)
    try:
        if shell('sha256sum', staged).split()[0] != STOCK_SHA256:
            raise RuntimeError('Device font checksum mismatch')
        shell('chown', owner, staged)
        shell('chmod', '600', staged)
        shell('restorecon', '-RF', DESTINATION)
        shell('mv', staged, final)
    finally:
        shell('rm', '-f', staged)
    print('Installed the verified stock font in Rabbit Phone private storage. Restart the app to apply.')


if __name__ == '__main__':
    main()
