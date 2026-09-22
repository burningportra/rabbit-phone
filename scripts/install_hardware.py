#!/usr/bin/env python3
"""Install/remove only Rabbit Phone's reviewed helper and boot service."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time

from device import require_evidence_serial, select_r1

ROOT = Path(__file__).resolve().parents[1]
SERIAL = None
SERVICE = 'rabbit-phone-controls'
BIN = '/data/local/rabbit-phone/rabbit-phone-hardware'
RC = '/system/etc/init/init-debug.rc'


def adb(*args, check=True):
    if SERIAL is None:
        raise RuntimeError('Device has not been selected')
    result = subprocess.run(['adb', '-s', SERIAL, *args], capture_output=True,
                            text=True, timeout=30)
    if check and result.returncode:
        raise RuntimeError('ADB operation failed: ' + result.stdout.strip() + ' ' + result.stderr.strip())
    return result.stdout.strip()


def stop_helper():
    adb('shell', 'setprop', 'ctl.stop', SERVICE, check=False)
    time.sleep(.2)
    pids = adb('shell', 'pidof', 'rabbit-phone-hardware', check=False).split()
    for pid in pids:
        if pid.isdigit():
            argv = adb('shell', 'cat', '/proc/' + pid + '/cmdline', check=False)
            if argv.startswith(BIN + '\0') or argv == BIN:
                adb('shell', 'kill', '-TERM', pid)
    time.sleep(.4)


def install():
    global SERIAL
    SERIAL = select_r1(mutation=True)
    assert adb('shell', 'id', '-u') == '0'
    evidence = ROOT / 'evidence'
    evidence.mkdir(exist_ok=True)
    receipt = evidence / 'hardware-install.json'
    if receipt.exists():
        require_evidence_serial(json.loads(receipt.read_text()), SERIAL, receipt)
    source = ROOT / 'hardware/rabbit-hardware'
    expected = hashlib.sha256(source.read_bytes()).hexdigest()
    backup = evidence / 'init-debug.before.rc'
    current = subprocess.check_output(['adb', '-s', SERIAL, 'exec-out', 'cat', RC])
    if not backup.exists():
        assert b'RABBIT PHONE' not in current
        backup.write_bytes(current)
    original = backup.read_bytes()
    if current != original:
        prior = json.loads(receipt.read_text())
        require_evidence_serial(prior, SERIAL, receipt)
        tracked = prior.get('installed_startup_sha256')
        assert tracked and hashlib.sha256(current).hexdigest() == tracked, 'Startup file changed outside this task; inspect before updating'
    desired = (original + b'\n# BEGIN RABBIT PHONE CONTROLS\n' +
               (ROOT / 'hardware/rabbit-phone.rc').read_bytes() + b'# END RABBIT PHONE CONTROLS\n')
    # Reuse this existing file's allocated block; the immutable ROM has no free data blocks.
    assert len(original) > 0 and len(desired) <= 4096
    staged = evidence / 'init-debug.installed.rc'
    staged.write_bytes(desired)
    stop_helper()
    adb('shell', 'mkdir', '-p', '/data/local/rabbit-phone')
    adb('shell', 'chmod', '0700', '/data/local/rabbit-phone')
    adb('push', str(source), BIN + '.new')
    adb('shell', 'chmod', '0755', BIN + '.new')
    adb('shell', 'chown', 'root:root', BIN + '.new')
    adb('shell', 'mv', BIN + '.new', BIN)
    adb('shell', 'restorecon', BIN)
    assert adb('shell', 'sha256sum', BIN).split()[0] == expected
    adb('push', str(staged), '/data/local/tmp/rabbit-phone-init.rc')
    # This ROM spoofs locked/green properties; the kernel cmdline reports orange.
    # The verified owner-unlocked device permits a reversible ext4 remount.
    assert 'androidboot.verifiedbootstate=orange' in adb('shell', 'cat', '/proc/cmdline')
    adb('shell', 'mount', '-o', 'remount,rw', '/')
    try:
        adb('shell', 'dd', 'if=/data/local/tmp/rabbit-phone-init.rc', 'of=' + RC, 'conv=notrunc')
        adb('shell', 'truncate', '-s', str(len(desired)), RC)
        adb('shell', 'sync')
        got = subprocess.check_output(['adb', '-s', SERIAL, 'exec-out', 'cat', RC])
        assert got == desired
        # Remove only the empty task-owned placeholders from the failed allocation attempt.
        for empty in ['/system/bin/rabbit-phone-hardware', '/system/etc/init/rabbit-phone.rc']:
            if adb('shell', 'stat', '-c', '%s', empty, check=False) == '0':
                adb('shell', 'rm', empty)
    finally:
        adb('shell', 'mount', '-o', 'remount,ro', '/')
    # Init caches service definitions until reboot; run the reviewed binary for
    # this session, then verify the installed init definition after restarting.
    adb('shell', 'nohup /system/xbin/su 0 ' + BIN +
        ' >/data/local/tmp/rabbit-phone-hardware.log 2>&1 </dev/null &')
    data = {'binary': BIN, 'startup_file': RC,
            'startup_backup': str(backup.relative_to(ROOT)),
            'original_startup_sha256': hashlib.sha256(original).hexdigest(),
            'installed_startup_sha256': hashlib.sha256(desired).hexdigest(),
            'sha256': expected, 'boot_startup_verified': False,
            'device_serial': SERIAL}
    receipt.write_text(json.dumps(data, indent=2) + '\n')
    print('Helper installed; private input service started. Reboot verification still required.')


def remove():
    global SERIAL
    SERIAL = select_r1(mutation=True)
    record = json.loads((ROOT / 'evidence/hardware-install.json').read_text())
    require_evidence_serial(record, SERIAL, ROOT / 'evidence/hardware-install.json')
    assert record['binary'] == BIN and record['startup_file'] == RC
    backup = Path(record['startup_backup'])
    if not backup.is_absolute():
        backup = ROOT / backup
    original = backup.read_bytes()
    assert hashlib.sha256(original).hexdigest() == record['original_startup_sha256']
    current = subprocess.check_output(['adb', '-s', SERIAL, 'exec-out', 'cat', RC])
    assert hashlib.sha256(current).hexdigest() == record['installed_startup_sha256'], 'Startup file changed since installation; inspect before restoring'
    stop_helper()
    adb('push', str(backup), '/data/local/tmp/rabbit-phone-init-restore.rc')
    adb('shell', 'mount', '-o', 'remount,rw', '/')
    try:
        adb('shell', 'dd', 'if=/data/local/tmp/rabbit-phone-init-restore.rc', 'of=' + RC, 'conv=notrunc')
        adb('shell', 'truncate', '-s', str(len(original)), RC)
        adb('shell', 'sync')
        assert subprocess.check_output(['adb', '-s', SERIAL, 'exec-out', 'cat', RC]) == original
    finally:
        adb('shell', 'mount', '-o', 'remount,ro', '/')
    adb('shell', 'rm', '-f', BIN)
    print('Removed the task-owned input helper and boot service. Power uses Android defaults.')


parser = argparse.ArgumentParser()
parser.add_argument('action', choices=['install', 'remove'])
{'install': install, 'remove': remove}[parser.parse_args().action]()
