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
                    data = {'new_boot': True, 'boot_completed': complete,
                            'startup_service': service, 'helper_pid': pid,
                            'init_service_pid': adb('shell', 'getprop',
                                                   'init.svc_debug_pid.rabbit-phone-controls'),
                            'home_package': role, 'device_serial': SERIAL}
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
