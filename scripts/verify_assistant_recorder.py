#!/usr/bin/env python3
"""Opt-in microphone test of Android's real power-long-press assistant route.

Starts with Settings behind a sleeping keyguard (or awake with --from-app).
Does not dismiss keyguard or directly start the recorder after the test begins.
Only its newly identified test note is copied for AAC validation, then removed.
"""
import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time

from verify_recorder import Device, FILES, NOTES, PACKAGE, ROOT


def wait_for(predicate, timeout=5):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(.15)
    return False


def focused(device):
    return next((line.strip() for line in device.shell('dumpsys', 'window').splitlines()
                 if 'mCurrentFocus=' in line), '')


def asleep(device):
    return 'mWakefulness=Asleep' in device.shell('dumpsys', 'power')


def ui_dump(device):
    path = '/data/local/tmp/rabbit-assistant-test.xml'
    try:
        device.shell('uiautomator', 'dump', path)
        return device.shell('cat', path)
    finally:
        device.shell('rm', '-f', path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--record-test', action='store_true')
    parser.add_argument('--from-app', action='store_true', help='Start in awake Settings')
    args = parser.parse_args()
    if not args.record_test:
        parser.error('--record-test is required; this check intentionally uses the microphone')
    if not shutil.which('ffprobe'):
        raise RuntimeError('ffprobe is required before recording')
    device = Device()
    if device.shell('id', '-u') != '0':
        raise RuntimeError('This R1 check requires root ADB')
    if device.shell('settings', 'get', 'global', 'power_button_long_press') != '5':
        raise RuntimeError('Android power long press is not configured for the assistant')
    component = device.shell('settings', 'get', 'secure', 'assistant')
    if component not in (PACKAGE + '/.RecorderAssistActivity',
                          PACKAGE + '/' + PACKAGE + '.RecorderAssistActivity'):
        raise RuntimeError('Rabbit recorder is not the selected Android assistant')
    result = {'start': 'settings' if args.from_app else 'standby', 'test_audio_removed': False}
    evidence = ROOT / 'evidence/recorder' / ('assistant-app' if args.from_app else 'assistant-standby')
    evidence.mkdir(parents=True, exist_ok=True)
    initial = device.notes()
    result['existing_note_files'] = len(initial)
    test_file = None
    driver = device.power_driver()
    device.home()
    device.shell('am', 'start', '-W', '-a', 'android.settings.SETTINGS')
    if not wait_for(lambda: 'com.android.settings/' in focused(device)):
        raise RuntimeError('Settings did not acquire foreground before the test')
    if not args.from_app:
        device.shell('input', 'keyevent', 'KEYCODE_SLEEP')
        if not wait_for(lambda: asleep(device)):
            raise RuntimeError('Device did not reach sleep before the test')
    result['no_foreground_helper_lease_before_hold'] = not bool(
        device.shell('cat', FILES + '/hardware-lease', check=False))
    if not result['no_foreground_helper_lease_before_hold']:
        raise RuntimeError('Foreground helper lease remained outside Rabbit Phone')
    try:
        device.edge(driver, True)
        try:
            result['microphone_activated_by_original_hold'] = wait_for(
                lambda: device.microphone_active(), timeout=6)
            result['recorder_launched_by_android'] = '.RecorderAssistActivity' in focused(device)
            device.screenshot(evidence / 'recording.png')
            if not result['microphone_activated_by_original_hold'] or not result['recorder_launched_by_android']:
                raise RuntimeError('Original power hold did not activate the recorder')
            lease = device.lease()
            marker = FILES + '/hardware-assist-used-' + lease
            result['passive_handoff_marker_consumed'] = device.shell(
                'cat', marker, check=False) == 'ASSIST'
            time.sleep(2)
        finally:
            device.edge(driver, False)
        result['microphone_stopped_after_release'] = wait_for(
            lambda: not device.microphone_active())
        time.sleep(.3)
        after = device.notes()
        created = set(after) - set(initial)
        if len(created) != 1:
            raise RuntimeError('Expected one new saved note; found ' + str(len(created)))
        candidate = created.pop()
        if not re.fullmatch(re.escape(NOTES) + r'/voice-note-[0-9-]+\.m4a', candidate):
            raise RuntimeError('Unexpected new filename; preserving it')
        test_file = candidate
        if any(after.get(path) != checksum for path, checksum in initial.items()):
            raise RuntimeError('Existing notes changed during the test')
        with tempfile.TemporaryDirectory(prefix='rabbit-assistant-test-') as temporary:
            local = Path(temporary) / 'test.m4a'
            device.adb('pull', test_file, local)
            info = json.loads(subprocess.check_output([
                'ffprobe', '-v', 'error', '-show_entries',
                'format=duration:stream=codec_name,codec_type,sample_rate,channels',
                '-of', 'json', str(local)], text=True))
            if float(info['format']['duration']) <= 0 or not any(
                    stream.get('codec_name') == 'aac' for stream in info['streams']):
                raise RuntimeError('Saved take is not valid AAC audio')
            result['audio'] = info
        device.screenshot(evidence / 'saved.png')
        ui = ui_dump(device)
        result['saved_feedback_visible'] = 'Voice note saved' in ui
        result['normal_controls_reconnected'] = wait_for(lambda: bool(
            device.shell('cat', FILES + '/hardware-lease', check=False))
            and device.shell('cat', FILES + '/hardware-lease', check=False) != lease)
        device.tap_text(ui, 'Done')
        result['done_returned_to_previous_app'] = wait_for(
            lambda: 'com.android.settings/' in focused(device))
        if not result['done_returned_to_previous_app']:
            raise RuntimeError('Done did not return to Settings')
        # A fresh ordinary press proves Android is not stuck waiting for the
        # original release, and that observer teardown did not retain a grab.
        time.sleep(.4)
        device.edge(driver, True)
        time.sleep(.08)
        device.edge(driver, False)
        result['normal_short_power_still_sleeps'] = wait_for(lambda: asleep(device))
        for key, value in result.items():
            if isinstance(value, bool) and key != 'test_audio_removed' and not value:
                raise RuntimeError('Assistant route check failed: ' + key)
    finally:
        device.shell('input', 'keyevent', 'KEYCODE_BACK')
        device.home()
        if test_file is not None:
            device.shell('rm', '-f', test_file)
        result['test_audio_removed'] = device.notes() == initial
        (evidence / 'verification.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
    if not result['test_audio_removed']:
        raise RuntimeError('Test cleanup did not restore the original notes')


if __name__ == '__main__':
    main()
