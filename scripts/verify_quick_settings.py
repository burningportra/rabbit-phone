#!/usr/bin/env python3
"""Opt-in R1 Quick Settings verification; changes small nearby brightness and volume values."""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import time

from device import select_r1


ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'com.kevtrinh.rabbitphone'
NOTES = '/data/user/0/' + PACKAGE + '/files/voice-notes'
SLIDER_LEFT, SLIDER_RIGHT = 24, 456
BRIGHTNESS_Y, VOLUME_Y = 152, 280


class ADBDevice:
    def __init__(self):
        self.serial = select_r1(mutation=True)

    def adb(self, *args, binary=False, check=True):
        result = subprocess.run(['adb', '-s', self.serial, *map(str, args)],
                                capture_output=True, text=not binary, timeout=20)
        if check and result.returncode:
            raise RuntimeError('ADB operation failed: ' + result.stderr.strip())
        return result.stdout if binary else result.stdout.strip()

    def shell(self, *args, check=True):
        return self.adb('shell', shlex.join(map(str, args)), check=check)

    def notes(self):
        listing = self.shell('find', NOTES, '-maxdepth', '1', '-type', 'f', check=False)
        result = {}
        for path in listing.splitlines():
            if not path.startswith(NOTES + '/'):
                raise RuntimeError('Unexpected note path')
            result[path] = self.shell('sha256sum', path).split()[0]
        return result

    def microphone_active(self):
        return 'running' in self.shell('cmd', 'appops', 'get', PACKAGE,
                                      'RECORD_AUDIO').lower()

    def home(self):
        self.shell('input', 'keyevent', 'KEYCODE_WAKEUP')
        time.sleep(.3)
        self.shell('wm', 'dismiss-keyguard')
        self.shell('am', 'start', '-W', '-a', 'android.intent.action.MAIN',
                   '-c', 'android.intent.category.HOME', '-n', PACKAGE + '/.HomeActivity')

    def screenshot(self, destination):
        destination.write_bytes(self.adb('exec-out', 'screencap', '-p', binary=True))


def dump_ui(device):
    path = '/data/local/tmp/rabbit-quick-settings-ui.xml'
    try:
        device.shell('uiautomator', 'dump', '--compressed', path)
        return device.shell('cat', path)
    finally:
        device.shell('rm', '-f', path)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def logical_size(device):
    text = device.shell('wm', 'size')
    values = re.findall(r'(?:Physical|Override) size:\s*(\d+)x(\d+)', text)
    if not values:
        raise RuntimeError('Could not read wm size')
    return tuple(map(int, values[-1]))


def brightness(device):
    value = device.shell('settings', 'get', 'system', 'screen_brightness')
    mode = device.shell('settings', 'get', 'system', 'screen_brightness_mode')
    if not re.fullmatch(r'\d+', value) or not re.fullmatch(r'\d+', mode):
        raise RuntimeError('System brightness or mode is unavailable')
    return int(value), int(mode)


def media_volume(device):
    text = device.shell('cmd', 'media_session', 'volume', '--stream', '3', '--get')
    match = re.search(r'volume is (\d+) in range\s*\[?0\.\.(\d+)\]?', text)
    if not match:
        raise RuntimeError('Could not read media volume and range')
    return int(match.group(1)), int(match.group(2))


def nearby_tap(current, maximum):
    for delta in range(1, maximum + 1):
        for target in (current + delta, current - delta):
            if target < 0 or target > maximum:
                continue
            x = int(SLIDER_LEFT + (SLIDER_RIGHT - SLIDER_LEFT) * target / maximum + .5)
            # Android's Math.round is positive-value floor(x + .5), not Python's
            # banker's rounding, so use the same rule as Slider.changeAt().
            applied = int(maximum * (x - SLIDER_LEFT) / (SLIDER_RIGHT - SLIDER_LEFT) + .5)
            if applied != current:
                return x, applied
    raise RuntimeError('No nearby slider value is available')


def tap(device, x, y):
    device.shell('input', 'tap', x, y)
    time.sleep(.35)


def swipe(device, x1, y1, x2, y2):
    device.shell('input', 'swipe', x1, y1, x2, y2, '300')
    time.sleep(.45)


def wait_for_text(device, text, present=True):
    deadline = time.monotonic() + 3
    while time.monotonic() < deadline:
        ui = dump_ui(device)
        found = text in ui
        if found == present:
            return ui
        time.sleep(.15)
    raise RuntimeError(('Did not show ' if present else 'Did not hide ') + text)


def open_quick_settings(device):
    swipe(device, 240, 12, 240, 245)
    return wait_for_text(device, 'Quick settings')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device-test', action='store_true',
                        help='Authorize nearby brightness and media-volume changes on the selected R1')
    args = parser.parse_args()
    if not args.device_test:
        parser.error('--device-test is required because this check changes brightness and volume')

    device = ADBDevice()
    evidence = ROOT / 'evidence/quick-settings'
    evidence.mkdir(parents=True, exist_ok=True)
    result = {'passed': False}
    original_notes = original_brightness = original_volume = None
    last_brightness = last_volume = None

    def receipt(name, condition):
        result[name] = bool(condition)
        require(condition, 'Quick Settings verification failed: ' + name)

    try:
        receipt('profile_is_480x640', logical_size(device) == (480, 640))
        receipt('microphone_idle_before_test', not device.microphone_active())
        original_notes = device.notes()
        original_brightness = brightness(device)
        original_volume = media_volume(device)
        result['brightness_before'] = {'value': original_brightness[0], 'mode': original_brightness[1]}
        result['volume_before'] = {'value': original_volume[0], 'maximum': original_volume[1]}

        device.home()
        receipt('home_visible', 'Rabbit home.' in wait_for_text(device, 'Rabbit home.'))
        ui = open_quick_settings(device)
        receipt('quick_settings_visible', 'Brightness' in ui and 'Media volume' in ui)
        device.screenshot(evidence / 'opened.png')

        brightness_x, expected_brightness = nearby_tap(original_brightness[0], 255)
        tap(device, brightness_x, BRIGHTNESS_Y)
        last_brightness = (expected_brightness, 0)
        receipt('brightness_changed_in_android', brightness(device) == last_brightness)

        volume_x, expected_volume = nearby_tap(original_volume[0], original_volume[1])
        tap(device, volume_x, VOLUME_Y)
        last_volume = expected_volume
        receipt('volume_changed_in_android', media_volume(device)[0] == last_volume)
        receipt('microphone_remains_idle_after_changes', not device.microphone_active())
        device.screenshot(evidence / 'changed.png')

        swipe(device, 240, 580, 240, 380)
        receipt('grab_swipe_dismisses_quick_settings', 'Quick settings' not in wait_for_text(device, 'Quick settings', False))
        open_quick_settings(device)
        receipt('reopen_keeps_brightness', brightness(device) == last_brightness)
        receipt('reopen_keeps_volume', media_volume(device)[0] == last_volume)

        device.shell('am', 'force-stop', PACKAGE)
        device.home()
        open_quick_settings(device)
        receipt('app_restart_keeps_brightness', brightness(device) == last_brightness)
        receipt('app_restart_keeps_volume', media_volume(device)[0] == last_volume)
        result['passed'] = True
    except Exception as error:
        message = str(error).replace(device.serial, '[selected R1]')
        result['failure'] = {'type': type(error).__name__, 'message': message}
    finally:
        if original_brightness is not None and last_brightness is not None:
            try:
                if brightness(device) == last_brightness:
                    device.shell('settings', 'put', 'system', 'screen_brightness', original_brightness[0])
                    device.shell('settings', 'put', 'system', 'screen_brightness_mode', original_brightness[1])
                    result['brightness_restored'] = brightness(device) == original_brightness
                else:
                    result['brightness_restore_skipped_external_change'] = True
            except Exception as error:
                result['brightness_restore_failure'] = type(error).__name__
                result['passed'] = False
        if original_volume is not None and last_volume is not None:
            try:
                if media_volume(device)[0] == last_volume:
                    device.shell('cmd', 'media_session', 'volume', '--stream', '3', '--set', original_volume[0])
                    result['volume_restored'] = media_volume(device)[0] == original_volume[0]
                else:
                    result['volume_restore_skipped_external_change'] = True
            except Exception as error:
                result['volume_restore_failure'] = type(error).__name__
                result['passed'] = False
        if original_notes is not None:
            try:
                result['notes_unchanged'] = device.notes() == original_notes
                result['microphone_idle_after_test'] = not device.microphone_active()
                result['passed'] &= result['notes_unchanged'] and result['microphone_idle_after_test']
            except Exception as error:
                result['integrity_check_failure'] = type(error).__name__
                result['passed'] = False
        try:
            device.home()
        except Exception:
            result['returned_home'] = False
        (evidence / 'verification.json').write_text(json.dumps(result, indent=2) + '\n')

    print(json.dumps(result, indent=2), flush=True)
    raise SystemExit(0 if result['passed'] else 1)


if __name__ == '__main__':
    main()
