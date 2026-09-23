#!/usr/bin/env python3
"""Verify Recorder list/detail navigation with a temporary synthetic silent note."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET

from verify_recorder import Device, FILES, NOTES, PACKAGE, ROOT
from verify_playback import volume, wheel_driver, wheel_up, tap_node, playback_line
from verify_card_flows import home, open_deck, select_card, side_click, wait_for, dump_ui, snapshot_timer, require


def nodes(ui):
    return list(ET.fromstring(ui).iter('node'))


def control(ui, description):
    choices = [n for n in nodes(ui) if n.get('content-desc') == description]
    require(len(choices) == 1, 'Expected one control: ' + description)
    return choices[0]


def library(ui):
    return 'Voice recorder' in ui and 'New voice note' in ui and 'Voice note details' not in ui


def detail(ui):
    return 'Voice note details' in ui and 'Duration ' in ui


def first_note(ui):
    choices = [n for n in nodes(ui) if n.get('content-desc', '').startswith('Open voice note,')]
    require(bool(choices), 'The synthetic note is not visible in the library')
    return choices[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device-test', action='store_true', help='Use UI/raw buttons; add and remove one verified silent fixture')
    if not parser.parse_args().device_test:
        parser.error('--device-test is required')
    require(shutil.which('ffmpeg') is not None, 'ffmpeg is required for a synthetic fixture; microphone is never used')
    d = Device()
    require(not d.microphone_active(), 'Finish the active recording before Recorder verification')
    notes_before, original_volume, timer_before = d.notes(), volume(d), snapshot_timer(d)
    require(not timer_before or json.loads(timer_before).get('phase') == 'NONE',
            'An existing timer is active; Recorder restart verification will leave it untouched')
    uid = re.search(r'uid:(\d+)', d.shell('cmd', 'package', 'list', 'packages', '-U', PACKAGE)).group(1)
    require(not playback_line(d, uid), 'Stop current playback before Recorder verification')
    evidence = ROOT / 'evidence/recorder-navigation'
    evidence.mkdir(parents=True, exist_ok=True)
    destination = NOTES + '/voice-note-ui-fixture-' + uuid.uuid4().hex + '.m4a'
    staged = destination + '.part'
    expected_hash = None
    last_test_volume = None
    result = {}

    def check(name, condition):
        result[name] = bool(condition)
        require(condition, name)
        print(name + ': passed', flush=True)

    def open_library():
        home(d)
        open_deck(d, wheel)
        select_card(d, wheel, 'recorder')
        side_click(d, power)
        return wait_for(d, library, 'Recorder library did not open')

    def set_volume(value):
        nonlocal last_test_volume
        d.shell('cmd', 'media_session', 'volume', '--stream', '3', '--set', value)
        last_test_volume = value

    try:
        with tempfile.TemporaryDirectory(prefix='rabbit-silent-note-') as temporary:
            fixture = Path(temporary) / 'silence.m4a'
            subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i',
                            'anullsrc=r=16000:cl=mono', '-t', '20', '-c:a', 'aac', '-b:a', '64k', str(fixture)], check=True)
            expected_hash = hashlib.sha256(fixture.read_bytes()).hexdigest()
            owner = d.shell('stat', '-c', '%u:%g', NOTES)
            require(re.fullmatch(r'\d+:\d+', owner) is not None and int(owner.split(':')[0]) >= 10000,
                    'Unexpected note directory owner')
            require(not d.shell('ls', destination, check=False), 'Fixture name already exists')
            (evidence / 'fixture.json').write_text(json.dumps({'device_serial': d.serial,
                'path': destination, 'sha256': expected_hash, 'source': 'generated silence, never microphone'}) + '\n')
            d.adb('push', fixture, staged)
            require(d.shell('sha256sum', staged).split()[0] == expected_hash, 'Staged fixture hash mismatch')
            d.shell('chown', owner, staged)
            d.shell('chmod', '600', staged)
            d.shell('restorecon', '-F', staged)
            d.shell('mv', staged, destination)
            d.shell('touch', destination)
            installed_notes = d.notes()
            newest = max(installed_notes, key=lambda path: int(d.shell('stat', '-c', '%Y', path)))
            require(newest == destination, 'The fixture is not the newest note; refusing playback')

        wheel, power = wheel_driver(d), d.power_driver()
        ui = open_library()
        lease = d.lease()
        check('library_is_modal', ', card ' not in ui)
        d.screenshot(evidence / 'library.png')
        tap_node(d, first_note(ui))
        ui = wait_for(d, detail, 'Note details did not open')
        check('row_opens_details_without_playback', not playback_line(d, uid) and not d.microphone_active())
        durations = [n.get('text', '') for n in nodes(ui) if n.get('text', '').startswith('Duration ')]
        check('real_duration_metadata_visible', len(durations) == 1 and durations[0].endswith('0:00:20'))
        check('details_keep_home_hardware_lease', d.lease() == lease)
        d.screenshot(evidence / 'details.png')
        wheel_up(d, wheel)
        side_click(d, power)
        ui = wait_for(d, library, 'Wheel and side button did not return from details')
        check('wheel_and_side_back_to_library', True)
        tap_node(d, control(ui, 'New voice note'))
        ui = wait_for(d, lambda value: 'Hold the side button to record' in value, 'Plus did not open Ready')
        check('plus_opens_ready_without_microphone', not d.microphone_active())
        d.shell('input', 'keyevent', 'KEYCODE_BACK')
        ui = wait_for(d, library, 'Ready Back did not return to library')
        check('ready_back_to_library', True)
        tap_node(d, first_note(ui))
        ui = wait_for(d, detail, 'Details did not reopen')
        set_volume(0)
        ui = wait_for(d, lambda value: detail(value) and 'Media volume off' in value, 'Muted volume not visible')
        tap_node(d, control(ui, 'Play'))
        deadline = time.monotonic() + 4
        while not playback_line(d, uid) and time.monotonic() < deadline:
            time.sleep(.1)
        check('explicit_play_starts_only_the_silent_fixture', bool(playback_line(d, uid)) and volume(d) == 0)
        ui = wait_for(d, lambda value: detail(value) and any(n.get('content-desc') == 'Stop' for n in nodes(value)),
                      'Detail Stop control missing during playback')
        tap_node(d, control(ui, 'Stop'))
        ui = wait_for(d, detail, 'Stop lost the note details page')
        check('stop_keeps_details_and_silences_player', not playback_line(d, uid))
        d.shell('input', 'keyevent', 'KEYCODE_BACK')
        ui = wait_for(d, library, 'Detail Back did not return to library after Stop')
        tap_node(d, control(ui, 'Raise media volume'))
        last_test_volume = 1
        check('library_volume_control_raises_one_step', volume(d) == 1)
        home(d)
        d.shell('am', 'force-stop', PACKAGE)
        ui = open_library()
        check('volume_survives_app_restart', volume(d) == 1 and 'Media volume' in ui)
    except Exception as error:
        result['failure'] = str(error).replace(d.serial, '[selected R1]')
        raise
    finally:
        # Stop our silent player without depending on a successful UI dump, so
        # fixture/volume cleanup also runs after a navigation assertion fails.
        d.shell('am', 'force-stop', PACKAGE)
        current = d.notes()
        if destination in current and current[destination] == expected_hash:
            d.shell('rm', destination)
        d.shell('rm', '-f', staged)
        if last_test_volume is not None and volume(d) == last_test_volume:
            d.shell('cmd', 'media_session', 'volume', '--stream', '3', '--set', original_volume)
        result['fixture_removed'] = destination not in d.notes()
        result['original_notes_unchanged'] = d.notes() == notes_before
        result['microphone_idle'] = not d.microphone_active()
        result['playback_stopped'] = not playback_line(d, uid)
        result['original_volume_restored'] = volume(d) == original_volume
        result['timer_unchanged'] = snapshot_timer(d) == timer_before
        (evidence / 'verification.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result, indent=2), flush=True)
        d.home()
    require(all(value is True for value in result.values()), 'Recorder verification failed')


if __name__ == '__main__':
    main()
