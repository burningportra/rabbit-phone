#!/usr/bin/env python3
"""Reboot one selected R1 and require Android, the helper, and the HOME role."""
import argparse
import json
from pathlib import Path
import subprocess
import time

from device import require_evidence_serial, select_r1

SERIAL = None
EVIDENCE = Path(__file__).resolve().parents[1] / 'evidence'


def verify_theme():
    if not (EVIDENCE / 'theme/applied.json').exists():
        return None
    if json.loads((EVIDENCE / 'theme/applied.json').read_text()).get('status') == 'restored':
        return None
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        service = adb('shell', 'getprop', 'init.svc.rabbit-phone-theme')
        primary = adb('shell', 'cmd', 'overlay', 'lookup', 'android', 'android:color/system_primary_dark')
        background = adb('shell', 'cmd', 'overlay', 'lookup', 'android', 'android:color/system_background_dark')
        spacing = adb('shell', 'cmd', 'overlay', 'lookup', 'com.android.systemui',
                      'com.android.systemui:dimen/keyguard_clock_line_spacing_scale')
        size = adb('shell', 'cmd', 'overlay', 'lookup', 'com.android.systemui',
                   'com.android.systemui:dimen/small_clock_text_size')
        if service == 'stopped' and primary == '#ffff5a1f' and background == '#ff0a0a09' and float(spacing) == 1.0 and size == '68.0dip':
            break
        time.sleep(1)
    else:
        raise RuntimeError('Theme did not finish and retain its expected resources after reboot')
    applications = json.loads((EVIDENCE.parent / 'theme/apps.json').read_text())
    for package, config in applications.items():
        for name, expected in config['colors'].items():
            actual = adb('shell', 'cmd', 'overlay', 'lookup', package, package + ':color/' + name)
            assert actual.lower() == '#' + expected.lower(), package + ':' + name
    assert adb('shell', 'getenforce') == 'Enforcing'
    mounts = adb('shell', 'cat', '/proc/mounts').splitlines()
    for mount in ('/', '/product'):
        assert any(line.split()[1] == mount and 'ro' in line.split()[3].split(',') for line in mounts)
    fonts_checked = 0
    for transaction in sorted(EVIDENCE.glob('system-font-*/transaction.json')):
        data = json.loads(transaction.read_text())
        if data.get('phase') != 'applied':
            continue
        require_evidence_serial(data, SERIAL, transaction)
        for entry in data['files']:
            assert adb('shell', 'sha256sum', entry['path']).split()[0] == entry['after']
            fonts_checked += 1
    return {'boot_service': service, 'primary': primary, 'background': background,
            'clock_line_spacing': spacing, 'small_clock_text_size': size,
            'app_palettes': len(applications),
            'font_and_config_files_verified': fonts_checked, 'selinux': 'Enforcing',
            'system_and_product_readonly': True}


def adb(*args, timeout=5):
    if SERIAL is None:
        raise RuntimeError('Device has not been selected')
    return subprocess.check_output(['adb', '-s', SERIAL, *args], text=True,
                                   stderr=subprocess.DEVNULL, timeout=timeout).strip()


def main():
    global SERIAL
    parser = argparse.ArgumentParser(
        description='Reboot the selected Android 16 Rabbit R1 and verify startup recovery.')
    parser.parse_args()
    SERIAL = select_r1(mutation=True)
    receipt = EVIDENCE / 'hardware-install.json'
    installed = json.loads(receipt.read_text())
    require_evidence_serial(installed, SERIAL, receipt)

    before = adb('shell', 'cat', '/proc/sys/kernel/random/boot_id')
    subprocess.run(['adb', '-s', SERIAL, 'reboot'], check=True)
    end = time.monotonic() + 240
    last = None
    booted_at = None
    while time.monotonic() < end:
        try:
            identifier = adb('shell', 'cat', '/proc/sys/kernel/random/boot_id')
            complete = adb('shell', 'getprop', 'sys.boot_completed')
            service = adb('shell', 'getprop', 'init.svc.rabbit-phone-controls')
            state = (identifier != before, complete, service)
            if state != last:
                print('New boot / Android complete / control service:', state, flush=True)
                last = state
            if identifier != before and complete == '1':
                if booted_at is None:
                    booted_at = time.monotonic()
                if service == 'running':
                    pid = adb('shell', 'pidof', 'rabbit-phone-hardware')
                    assert pid
                    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
                    adb('shell', 'wm', 'dismiss-keyguard')
                    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
                    time.sleep(1.5)
                    role = adb('shell', 'cmd', 'role', 'get-role-holders',
                               'android.app.role.HOME')
                    assert role == 'com.kevtrinh.rabbitphone'
                    theme = verify_theme()
                    data = {'new_boot': True, 'boot_completed': complete,
                            'startup_service': service, 'helper_pid': pid,
                            'init_service_pid': adb('shell', 'getprop',
                                                   'init.svc_debug_pid.rabbit-phone-controls'),
                            'home_package': role, 'device_serial': SERIAL}
                    if theme is not None:
                        data['theme'] = theme
                    EVIDENCE.mkdir(parents=True, exist_ok=True)
                    (EVIDENCE / 'reboot-verification.json').write_text(
                        json.dumps(data, indent=2) + '\n')
                    installed['boot_startup_verified'] = True
                    receipt.write_text(json.dumps(installed, indent=2) + '\n')
                    print(json.dumps(data, indent=2), flush=True)
                    return
                if time.monotonic() - booted_at > 30:
                    raise SystemExit(
                        'Android booted, but the control service did not reach running state')
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            pass
        time.sleep(3)
    raise SystemExit('Boot verification timed out')


if __name__ == '__main__':
    main()
