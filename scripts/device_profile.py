#!/usr/bin/env python3
"""Back up, apply, or restore the small reversible Rabbit Phone system profile."""
import argparse
import datetime
import json
from pathlib import Path
import subprocess

from device import require_evidence_serial, select_r1

ROOT = Path(__file__).resolve().parents[1]
SERIAL = None
SNAPSHOT = ROOT / 'evidence/settings-before.json'
KEYS = [('secure', 'camera_double_tap_power_gesture_disabled'),
        ('secure', 'camera_gesture_disabled'), ('secure', 'sysui_qs_tiles'),
        ('secure', 'theme_customization_overlay_packages'), ('secure', 'ui_night_mode'),
        ('system', 'screen_off_timeout'), ('system', 'accelerometer_rotation'),
        ('system', 'user_rotation'), ('system', 'font_scale')]


def adb(*args):
    if SERIAL is None:
        raise RuntimeError('Device has not been selected')
    return subprocess.check_output(['adb', '-s', SERIAL, *args], text=True, timeout=20).strip()


def choose_device(mutation):
    global SERIAL
    SERIAL = select_r1(mutation=mutation)


def backup(mutation=False):
    choose_device(mutation)
    if SNAPSHOT.exists():
        data = json.loads(SNAPSHOT.read_text())
        require_evidence_serial(data, SERIAL, SNAPSHOT)
        if 'timezone' not in data:
            data['timezone'] = adb('shell', 'getprop', 'persist.sys.timezone')
            SNAPSHOT.write_text(json.dumps(data, indent=2) + '\n')
        return data
    data = {'created': datetime.datetime.now().isoformat(), 'device_serial': SERIAL,
            'settings': {},
            'home': adb('shell', 'cmd', 'role', 'get-role-holders', 'android.app.role.HOME'),
            'night': adb('shell', 'cmd', 'uimode', 'night'),
            'overlays': adb('shell', 'cmd', 'overlay', 'list'),
            'timezone': adb('shell', 'getprop', 'persist.sys.timezone')}
    for namespace, key in KEYS:
        data['settings'][namespace + ':' + key] = adb('shell', 'settings', 'get', namespace, key)
    SNAPSHOT.parent.mkdir(parents=True, exist_ok=True)
    SNAPSHOT.write_text(json.dumps(data, indent=2) + '\n')
    print('Saved original settings to ' + str(SNAPSHOT))
    return data


def apply():
    backup(mutation=True)
    # The current runtime overlay was queried before enabling this supported gesture.
    supported = adb('shell', 'cmd', 'overlay', 'lookup', 'android',
                    'android:bool/config_cameraDoubleTapPowerGestureEnabled')
    if supported == 'true':
        adb('shell', 'settings', 'put', 'secure', 'camera_double_tap_power_gesture_disabled', '0')
    adb('shell', 'cmd', 'uimode', 'night', 'yes')
    adb('shell', 'cmd', 'alarm', 'set-timezone', 'America/New_York')
    # Put useful controls first; Android's tile editor still offers the other tiles.
    tiles = ('internet,bt,custom(com.rabbitescape.stepmotor/.CameraTileService),dnd,'
             'alarm,rotation,battery,mictoggle,cameratoggle,airplane,screenrecord')
    # Quote only the tile value for the remote Android shell; it contains parentheses.
    adb('shell', 'cmd statusbar set-tiles ' + "'" + tiles + "'")
    print('Applied dark mode, supported double-power camera, and focused Quick Settings.')


def restore():
    choose_device(mutation=True)
    data = json.loads(SNAPSHOT.read_text())
    require_evidence_serial(data, SERIAL, SNAPSHOT)
    import shlex
    for compound, value in data['settings'].items():
        namespace, key = compound.split(':', 1)
        if value == 'null':
            adb('shell', 'settings', 'delete', namespace, key)
        elif namespace == 'secure' and key == 'sysui_qs_tiles':
            adb('shell', 'cmd statusbar set-tiles ' + shlex.quote(value))
        else:
            adb('shell', 'settings put ' + namespace + ' ' + key + ' ' + shlex.quote(value))
    night = data['night'].removeprefix('Night mode: ').strip()
    if night in ['yes', 'no', 'auto', 'custom']:
        adb('shell', 'cmd', 'uimode', 'night', night)
    if data.get('timezone'):
        adb('shell', 'cmd', 'alarm', 'set-timezone', data['timezone'])
    if data['home'] == 'com.android.launcher3':
        adb('shell', 'cmd', 'package', 'set-home-activity', 'com.android.launcher3/.CipherLauncher')
    print('Restored saved settings and original home. App data and installed apps remain intact.')


parser = argparse.ArgumentParser()
parser.add_argument('action', choices=['backup', 'apply', 'restore'])
action = parser.parse_args().action
{'backup': backup, 'apply': apply, 'restore': restore}[action]()
