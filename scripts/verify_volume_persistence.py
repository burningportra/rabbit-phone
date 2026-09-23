#!/usr/bin/env python3
"""Opt-in speaker-volume persistence check using recorder UI; no playback or recording."""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET

from verify_playback import dump_ui, tap_label, tap_node, volume
from verify_recorder import Device, PACKAGE, ROOT


def speaker_state(device):
    audio = device.shell('dumpsys', 'audio')
    block = re.search(r'(?ms)^- STREAM_MUSIC:\n(.*?)(?=^- STREAM_|\Z)', audio)
    if not block or not re.search(r'(?m)^\s*Devices:\s*speaker\(2\)\s*$', block[1]):
        raise RuntimeError('Media must be routed only to the R1 speaker')
    current = re.search(r'(?:Current:\s*|,\s*)2 \(speaker\): (\d+)', block[1])
    maximum = re.search(r'(?m)^\s*Max: (\d+)', block[1])
    if not current or not maximum or int(current[1]) != volume(device):
        raise RuntimeError('Speaker and active media levels disagree')
    return int(current[1]), int(maximum[1])


def open_library(device):
    if device.microphone_active():
        raise RuntimeError('A recording is active; leaving its screen and audio untouched')
    device.home()
    for label in ('All apps', 'Utilities', 'Voice notes'):
        tap_label(device, label)


def move_volume(device, expected, target):
    for _ in range(33):
        if speaker_state(device)[0] != expected:
            raise RuntimeError('Volume changed externally; refusing further adjustments')
        if expected == target:
            return
        label = 'Raise media volume' if target > expected else 'Lower media volume'
        nodes = [node for node in ET.fromstring(dump_ui(device)).iter('node')
                 if node.get('content-desc') == label]
        if len(nodes) != 1 or volume(device) != expected:
            raise RuntimeError('Volume or visible controls changed before adjustment')
        tap_node(device, nodes[0])
        expected += 1 if target > expected else -1
        time.sleep(.15)
    raise RuntimeError('Volume adjustment exceeded the bounded step limit')


def verify_level(device, expected):
    current, maximum = speaker_state(device)
    label = 'Media volume off' if expected == 0 else 'Media volume ' + str(
        int(expected * 100 / max(1, maximum) + .5)) + '%'
    nodes = ET.fromstring(dump_ui(device)).iter('node')
    if current != expected or not any(node.get('text') == label for node in nodes):
        raise RuntimeError('Media volume or recorder label did not persist')
    saved = device.shell('settings', 'get', 'system', 'volume_music_speaker', check=False)
    stored = int(saved) if re.fullmatch(r'-?\d+', saved) else None
    if stored is not None and stored != expected:
        raise RuntimeError('Android speaker setting did not persist')
    if device.microphone_active():
        raise RuntimeError('Microphone unexpectedly active')
    return {'level': current, 'label': label, 'persisted_speaker_setting': stored}


def reboot_and_wait(device):
    before = device.shell('cat', '/proc/sys/kernel/random/boot_id')
    if not before:
        raise RuntimeError('Missing initial boot identity')
    device.adb('reboot')
    deadline, next_progress = time.monotonic() + 240, 0
    probe = ('cat /proc/sys/kernel/random/boot_id; getprop sys.boot_completed; '
             'getprop init.svc.rabbit-phone-controls; pidof rabbit-phone-hardware; '
             'getprop init.svc.rabbit-phone-theme')
    while time.monotonic() < deadline:
        now = time.monotonic()
        if now >= next_progress:
            print('Waiting for a new boot, Android, and the hardware helper...', flush=True)
            next_progress = now + 10
        try:
            response = subprocess.run(['adb', '-s', device.serial, 'shell', probe],
                                      capture_output=True, text=True,
                                      timeout=max(.01, min(5, deadline - time.monotonic())))
            fields = response.stdout.splitlines()
            if (response.returncode == 0 and len(fields) >= 4 and fields[0] != before
                    and fields[0] and fields[1:3] == ['1', 'running']
                    and re.fullmatch(r'\d+(?: \d+)*', fields[3])
                    and (len(fields) == 4 or fields[4] in ('', 'stopped'))):
                # The optional theme job may recreate SystemUI after boot_complete.
                time.sleep(2)
                return
        except subprocess.TimeoutExpired:
            pass
        time.sleep(max(0, min(2, deadline - time.monotonic())))
    raise RuntimeError('Reboot verification exceeded 240 seconds')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--change-volume', action='store_true', help='Authorize UI volume changes')
    parser.add_argument('--zero', action='store_true', help='Test the muted zero level')
    parser.add_argument('--reboot', action='store_true', help='Also reboot and verify persistence')
    args = parser.parse_args()
    if not args.change_volume:
        parser.error('--change-volume is required; this check adjusts speaker media volume')
    evidence = ROOT / 'evidence/craft'
    evidence.mkdir(parents=True, exist_ok=True)
    receipt = evidence / ('volume-zero-verification.json' if args.zero else 'volume-verification.json')
    result = {'passed': False, 'reboot_requested': args.reboot, 'zero_requested': args.zero}
    device, initial, baseline, target = None, None, None, None
    stage = 'baseline'
    try:
        device = Device()
        if device.microphone_active() or device.shell('id', '-u') != '0':
            raise RuntimeError('Requires idle microphone and root ADB for note integrity checks')
        baseline, _ = speaker_state(device)
        initial = device.notes()
        result.update(baseline_level=baseline, existing_note_files=len(initial))
        target = 0 if args.zero else baseline - 1 if baseline > 0 else 1
        result['test_level'] = target
        print('Testing speaker media level:', target, '(no playback or recording)', flush=True)
        stage = 'UI volume change'
        open_library(device)
        if args.zero and baseline == 0:
            move_volume(device, 0, 1)  # Exercise an actual return to zero, even when already muted.
            move_volume(device, 1, 0)
        else:
            move_volume(device, baseline, target)
        time.sleep(1)  # AudioService persists per-device volume after its 500 ms delay.
        result['changed'] = verify_level(device, target)
        stage = 'app restart'
        print('Checking the level after an app restart...', flush=True)
        device.home()
        device.shell('am', 'force-stop', PACKAGE)
        open_library(device)
        result['app_restart'] = verify_level(device, target)
        if args.reboot:
            stage = 'reboot'
            reboot_and_wait(device)
            result['new_boot_android_and_helper_ready'] = True
            result['level_immediately_after_reboot'] = speaker_state(device)[0]
            if result['level_immediately_after_reboot'] != target:
                raise RuntimeError('Speaker volume changed across reboot')
            open_library(device)
            result['reboot'] = verify_level(device, target)
        result['passed'] = True
    except Exception as error:
        message = str(error)
        if device is not None:
            message = message.replace(device.serial, '[selected R1]')
        result['failure'] = {'stage': stage, 'error_type': type(error).__name__, 'message': message}
    finally:
        if device is not None and baseline is not None and target is not None:
            try:
                if speaker_state(device)[0] == target:
                    open_library(device)
                    move_volume(device, target, baseline)
                    time.sleep(1)
                    result['restored'] = verify_level(device, baseline)
                    device.home()
                else:
                    result['restore_skipped'] = 'Current level differs from test level; left unchanged'
                    result['passed'] = False
            except Exception as error:
                result['restore_failure'] = type(error).__name__
                result['passed'] = False
        if device is not None and initial is not None:
            try:
                after = device.notes()
                result['notes_unchanged'] = all(after.get(path) == checksum
                                                for path, checksum in initial.items())
                result['new_note_files'] = len(set(after) - set(initial))
                result['microphone_idle'] = not device.microphone_active()
                result['passed'] &= result['notes_unchanged'] and result['microphone_idle']
            except Exception as error:
                result['integrity_check_failure'] = type(error).__name__
                result['passed'] = False
        receipt.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2), flush=True)
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
