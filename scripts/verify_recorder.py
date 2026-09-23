#!/usr/bin/env python3
"""Explicit, short real-microphone check of the R1 hold/release recorder path.

Requires --record-test. Captures and plays only purpose-made test takes, checks
AAC without transcribing audio, and removes its new verified test recording.
Existing notes are never downloaded or removed. Screenshots/receipts stay ignored.
"""
import argparse
import json
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

from device import select_r1, wake_for_ui

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'com.kevtrinh.rabbitphone'
FILES = '/data/user/0/' + PACKAGE + '/files'
NOTES = FILES + '/voice-notes'


class Device:
    def __init__(self):
        self.serial = select_r1(mutation=True)

    def adb(self, *args, binary=False, check=True):
        result = subprocess.run(['adb', '-s', self.serial, *map(str, args)],
                                capture_output=True, text=not binary, timeout=20)
        if check and result.returncode:
            raise RuntimeError('ADB operation failed: ' + str(result.stderr))
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

    def lease(self):
        value = self.shell('cat', FILES + '/hardware-lease')
        if not re.fullmatch('[a-f0-9]{32}', value):
            raise RuntimeError('Private foreground hardware lease missing')
        return value

    def power_driver(self):
        # Discover by kernel name, never assume input device numbering.
        listing = self.adb('shell', 'for f in /sys/class/input/event*/device/name; do '
                           'printf "%s " "$f"; cat "$f"; done')
        for line in listing.splitlines():
            if line.endswith(' mtk-pmic-keys'):
                event = line.split('/')[4]
                if re.fullmatch('event[0-9]+', event):
                    return '/dev/input/' + event
        raise RuntimeError('R1 PMIC power driver not found')

    def edge(self, driver, down):
        self.shell('sendevent', driver, '1', '116', '1' if down else '0')
        self.shell('sendevent', driver, '0', '0', '0')

    def home(self):
        wake_for_ui(self)
        self.shell('am', 'start', '-W', '-a', 'android.intent.action.MAIN',
                   '-c', 'android.intent.category.HOME', '-n', PACKAGE + '/.HomeActivity')
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            value = self.shell('cat', FILES + '/hardware-lease', check=False)
            if re.fullmatch('[a-f0-9]{32}', value):
                return value
            time.sleep(.2)
        raise RuntimeError('Rabbit Home did not acquire an unlocked foreground hardware lease')

    def screenshot(self, destination):
        destination.write_bytes(self.adb('exec-out', 'screencap', '-p', binary=True))

    def microphone_active(self):
        return 'running' in self.shell('cmd', 'appops', 'get', PACKAGE,
                                      'RECORD_AUDIO').lower()

    def tap_text(self, ui, label):
        matches = [node for node in ET.fromstring(ui).iter('node')
                   if node.get('text') == label and node.get('clickable') == 'true']
        if len(matches) != 1:
            raise RuntimeError('Expected one clickable recorder action: ' + label)
        coordinates = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',
                                   matches[0].get('bounds', ''))
        if not coordinates:
            raise RuntimeError('Recorder action has invalid bounds')
        left, top, right, bottom = map(int, coordinates.groups())
        self.shell('input', 'tap', (left + right) // 2, (top + bottom) // 2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--record-test', action='store_true',
                        help='Explicitly authorize the short local microphone test')
    parser.add_argument('--camera', action='store_true',
                        help='Check hold/release in the built-in camera without taking a photo')
    args = parser.parse_args()
    if not args.record_test:
        parser.error('--record-test is required; this test intentionally uses the microphone')
    if not shutil.which('ffprobe'):
        raise RuntimeError('ffprobe is required before recording can start')
    device = Device()
    if device.shell('id', '-u') != '0':
        raise RuntimeError('This device check needs root ADB on the tested R1')
    directory = ROOT / 'evidence/recorder' / ('camera' if args.camera else 'home')
    directory.mkdir(parents=True, exist_ok=True)
    initial = device.notes()
    driver = device.power_driver()
    lease = device.home()
    if args.camera:
        device.shell('am', 'start', '-W', '-n', PACKAGE + '/.CameraActivity')
        time.sleep(.8)
        lease = device.lease()
    result = {'existing_note_files': len(initial),
              'activity': 'camera' if args.camera else 'home',
              'raw_power_driver': driver, 'test_audio_removed': False}
    test_file = None
    try:
        device.edge(driver, True)
        try:
            time.sleep(1.1)
            device.screenshot(directory / 'recording.png')
            result['same_lease_during_hold'] = device.lease() == lease
            result['microphone_active_during_hold'] = device.microphone_active()
            time.sleep(4.2)
        finally:
            device.edge(driver, False)
        time.sleep(.8)
        after = device.notes()
        created = set(after) - set(initial)
        if len(created) != 1:
            raise RuntimeError('Expected exactly one newly saved test note; found ' + str(len(created)))
        candidate = created.pop()
        if not re.fullmatch(re.escape(NOTES) + r'/voice-note-[0-9-]+\.m4a', candidate):
            raise RuntimeError('Unexpected test-note filename; leaving it intact')
        test_file = candidate
        if any(after.get(path) != checksum for path, checksum in initial.items()):
            raise RuntimeError('Existing notes changed during the test')
        result['same_lease_after_release'] = device.lease() == lease
        result['microphone_stopped_after_release'] = not device.microphone_active()
        with tempfile.TemporaryDirectory(prefix='rabbit-recorder-test-') as temporary:
            local = Path(temporary) / 'test.m4a'
            device.adb('pull', test_file, local)
            info = json.loads(subprocess.check_output([
                'ffprobe', '-v', 'error', '-show_entries',
                'format=duration:stream=codec_name,codec_type,sample_rate,channels',
                '-of', 'json', str(local)], text=True))
            duration = float(info['format']['duration'])
            if duration <= 0 or not any(s.get('codec_name') == 'aac' for s in info['streams']):
                raise RuntimeError('Saved note does not contain valid AAC audio')
            result['audio'] = info
        device.screenshot(directory / 'saved.png')
        # Capture a stable, finished UI rather than waiting for accessibility
        # idle while the real microphone meter is updating.
        device.shell('uiautomator', 'dump', '/data/local/tmp/rabbit-recorder-ui.xml')
        ui = device.shell('cat', '/data/local/tmp/rabbit-recorder-ui.xml')
        result['recorder_heading_visible'] = 'voice recorder' in ui.lower()
        result['saved_feedback_visible'] = 'saved' in ui.lower()
        device.shell('rm', '-f', '/data/local/tmp/rabbit-recorder-ui.xml')

        # Play only this deliberately created test note. A new hold must stop
        # playback before constructing the recording UI, including its meter.
        device.tap_text(ui, 'Play')
        device.screenshot(directory / 'playback.png')

        # A second, deliberately canceled take exercises actual Activity focus
        # loss while the physical driver is still held. Preserve the first take
        # until both checks finish so its saved content is checked as well.
        device.edge(driver, True)
        try:
            time.sleep(1.1)
            result['microphone_active_before_cancel'] = device.microphone_active()
            device.screenshot(directory / 'recording-after-playback.png')
            device.shell('am', 'start', '-W', '-a', 'android.settings.SETTINGS')
            time.sleep(.4)
            result['microphone_stopped_on_focus_loss'] = not device.microphone_active()
        finally:
            device.edge(driver, False)
        result['focus_loss_left_no_partial_or_saved_take'] = device.notes() == after
        if not all(result[key] for key in ['same_lease_during_hold', 'same_lease_after_release',
                                          'microphone_active_during_hold',
                                          'microphone_stopped_after_release',
                                          'recorder_heading_visible', 'saved_feedback_visible',
                                          'microphone_active_before_cancel',
                                          'microphone_stopped_on_focus_loss',
                                          'focus_loss_left_no_partial_or_saved_take']):
            raise RuntimeError('Recorder activation/save checks failed: ' + json.dumps(result))
    finally:
        # Home causes any remaining foreground recording to cancel before the
        # explicitly identified test file is removed.
        device.shell('input', 'keyevent', 'KEYCODE_HOME')
        if test_file is not None:
            device.shell('rm', '-f', test_file)
            result['test_audio_removed'] = device.notes() == initial
        (directory / 'verification.json').write_text(json.dumps(result, indent=2) + '\n')
    if not result['test_audio_removed']:
        raise RuntimeError('Test cleanup did not restore the original note files')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
