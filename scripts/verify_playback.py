#!/usr/bin/env python3
"""Opt-in R1 playback/volume check; preserves existing notes and exports no audio."""
import argparse
import json
import re
import time
import xml.etree.ElementTree as ET

from verify_recorder import Device, FILES, PACKAGE, ROOT


def volume(device):
    output = device.shell('cmd', 'media_session', 'volume', '--stream', '3', '--get')
    match = re.search(r'volume is (\d+) in range', output)
    if not match:
        raise RuntimeError('Cannot read media volume')
    return int(match.group(1))


def dump_ui(device):
    path = '/data/local/tmp/rabbit-playback-ui.xml'
    try:
        device.shell('uiautomator', 'dump', path)
        return device.shell('cat', path)
    finally:
        device.shell('rm', '-f', path)


def tap_node(device, node):
    coordinates = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
    if not coordinates:
        raise RuntimeError('Missing UI control bounds')
    left, top, right, bottom = map(int, coordinates.groups())
    device.shell('input', 'tap', (left + right) // 2, (top + bottom) // 2)


def tap_label(device, label):
    nodes = [node for node in ET.fromstring(dump_ui(device)).iter('node')
             if node.get('text') == label or node.get('content-desc') == label]
    if len(nodes) != 1:
        raise RuntimeError('Expected one visible control: ' + label)
    tap_node(device, nodes[0])


MAX_DECK_TICKS = 20


def wheel_driver(device):
    inputs = device.adb('shell', 'for f in /sys/class/input/event*/device/name; do '
                        'printf "%s " "$f"; cat "$f"; done')
    name = next((line.split('/')[4] for line in inputs.splitlines()
                 if line.endswith(' och1970_holl_key')), None)
    if name is None:
        raise RuntimeError('R1 wheel input driver not found')
    # These are Linux input codes. Android key codes are a separate mapping.
    capabilities = device.shell('cat', '/sys/class/input/' + name + '/device/capabilities/key')
    bits = 0
    for word in capabilities.split():
        bits = (bits << 64) | int(word, 16)  # R1's aarch64 kernel uses 64-bit bitmap words.
    if not all(bits & (1 << key) for key in (103, 108)):
        raise RuntimeError('Unexpected R1 wheel key capabilities; verify before injecting events')
    return '/dev/input/' + name


def wheel_tick(device, key_code, wheel=None):
    if wheel is None:
        wheel = wheel_driver(device)
    for value in ('1', '0'):
        device.shell('sendevent', wheel, '1', str(key_code), value)
        device.shell('sendevent', wheel, '0', '0', '0')


def wheel_up(device, wheel=None):
    wheel_tick(device, 103, wheel)  # Linux KEY_UP reported by this R1 wheel.


def wheel_down(device, wheel=None):
    wheel_tick(device, 108, wheel)  # Linux KEY_DOWN on the installed wheel mapping.


def visible_label(device, label):
    nodes = [node for node in ET.fromstring(dump_ui(device)).iter('node')
             if node.get('text') == label]
    if len(nodes) > 1:
        raise RuntimeError('Expected at most one visible control: ' + label)
    if not nodes:
        return None
    coordinates = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',
                               nodes[0].get('bounds', ''))
    if not coordinates or coordinates.group(1) == coordinates.group(3) or coordinates.group(2) == coordinates.group(4):
        raise RuntimeError('Visible control has invalid bounds: ' + label)
    return nodes[0]


def open_recorder_library(device):
    """Follow the rendered Home path: wheel opens the deck, then its recorder card."""
    if device.microphone_active():
        raise RuntimeError('Finish the active recording before opening recorder library')
    device.home()
    wheel = wheel_driver(device)
    wheel_down(device, wheel)
    time.sleep(.2)
    # Opened cards persist above the catalog, so always reset the selected card
    # through physical wheel edges before walking forward through the visible deck.
    for _ in range(MAX_DECK_TICKS):
        wheel_up(device, wheel)
        time.sleep(.05)
    for _ in range(MAX_DECK_TICKS):
        recorder = visible_label(device, 'recorder')
        if recorder is not None:
            tap_node(device, recorder)
            deadline = time.monotonic() + 12
            while time.monotonic() < deadline:
                ui = dump_ui(device)
                if 'Voice recorder' in ui and 'Media volume' in ui:
                    return device.lease()
                time.sleep(.12)
            raise RuntimeError('Recorder library did not become accessible after its card transition')
        wheel_down(device, wheel)
        time.sleep(.12)
    raise RuntimeError('Recorder card was not visible within the bounded deck traversal')


def playback_line(device, uid):
    return next((line for line in device.shell('dumpsys', 'audio').splitlines()
                 if 'AudioPlaybackConfiguration ' in line and 'u/pid:' + uid + '/' in line
                 and 'state:started' in line and 'USAGE_MEDIA' in line), '')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--play-note', action='store_true',
                        help='Play an existing note on this R1 while testing its volume controls')
    if not parser.parse_args().play_note:
        parser.error('--play-note is required; playback may be audible')
    device = Device()
    if device.microphone_active():
        raise RuntimeError('Finish the active recording before running this playback check')
    initial = device.notes()
    starting_volume = volume(device)
    if starting_volume <= 0:
        raise RuntimeError('Choose an audible media level before this playback check')
    entries = sorted((path for path in initial if path.endswith('.m4a')),
                     key=lambda path: int(device.shell('stat', '-c', '%Y', path)), reverse=True)
    if not entries:
        raise RuntimeError('No saved notes to test')
    uid = re.search(r'uid:(\d+)', device.shell('cmd', 'package', 'list', 'packages',
                                            '-U', PACKAGE)).group(1)
    evidence = ROOT / 'evidence/playback'
    evidence.mkdir(parents=True, exist_ok=True)
    result = {'starting_volume': starting_volume, 'existing_note_files': len(initial)}
    try:
        lease = open_recorder_library(device)
        ui = dump_ui(device)
        nodes = list(ET.fromstring(ui).iter('node'))
        rows = [node for node in nodes if node.get('content-desc', '').startswith('Open voice note,')]
        if not rows or 'Media volume' not in ui:
            raise RuntimeError('Recorder library or volume controls not visible')
        # Use the longest visible note so playback stays active during the dump.
        index = max(range(len(rows)), key=lambda i: int(
            device.shell('stat', '-c', '%s', entries[i])))
        note_row = rows[index]
        tap_node(device, note_row)
        detail_ui = dump_ui(device)
        result['row_opens_detail_without_playback'] = ('Voice note details' in detail_ui
                and not playback_line(device, uid) and not device.microphone_active())
        result['real_duration_visible'] = any(re.fullmatch(r'Duration \d+:\d{2}:\d{2}', node.get('text', ''))
                                             for node in ET.fromstring(detail_ui).iter('node'))
        if not result['row_opens_detail_without_playback'] or not result['real_duration_visible']:
            raise RuntimeError('Voice-note detail did not open silently with readable duration metadata')
        nodes = list(ET.fromstring(detail_ui).iter('node'))
        lower = next(node for node in nodes if node.get('content-desc') == 'Lower media volume')
        higher = next(node for node in nodes if node.get('content-desc') == 'Raise media volume')
        device.shell('cmd', 'media_session', 'volume', '--stream', '3', '--set', '0')
        time.sleep(.6)
        muted_ui = dump_ui(device)
        result['zero_volume_visible'] = 'Media volume off' in muted_ui
        device.screenshot(evidence / 'volume-off.png')
        tap_label(device, 'Play')
        time.sleep(.3)
        silent = playback_line(device, uid)
        result['play_respects_zero_volume'] = volume(device) == 0 and bool(silent)
        # Playback is a separate explicit detail action; opening a row is silent.
        tap_label(device, 'Stop')
        tap_node(device, higher)
        time.sleep(.2)
        result['plus_raises_one_step'] = volume(device) == 1
        tap_node(device, lower)
        time.sleep(.2)
        result['minus_lowers_one_step'] = volume(device) == 0

        # The lower control is selected. One actual wheel DOWN selects plus,
        # and the physical power-driver click must activate that control.
        wheel_down(device)
        power = device.power_driver()
        device.edge(power, True)
        time.sleep(.08)
        device.edge(power, False)
        time.sleep(.5)
        result['wheel_and_side_button_adjust_volume'] = volume(device) == 1
        result['same_hardware_lease'] = device.lease() == lease
        device.shell('cmd', 'media_session', 'volume', '--stream', '3', '--set', str(starting_volume))
        time.sleep(.6)
        device.screenshot(evidence / 'volume-restored.png')
        tap_label(device, 'Play')
        time.sleep(.4)
        audible = playback_line(device, uid)
        result['playback_started_unmuted'] = bool(audible) and 'mutedState:none' in audible
        devices = re.search(r'deviceIds:\[([^]]*)\]', audible)
        active_devices = set(devices.group(1).split(',')) if devices else set()
        policy = device.shell('dumpsys', 'media.audio_policy')
        speakers = set(re.findall(r'Port ID: (\d+); "Speaker"; \{AUDIO_DEVICE_OUT_SPEAKER', policy))
        result['routed_to_speaker'] = bool(active_devices & speakers)
        (evidence / 'active-audio.txt').write_text(device.shell('dumpsys', 'audio'))
        (evidence / 'active-flinger.txt').write_text(device.shell('dumpsys', 'media.audio_flinger'))
        device.screenshot(evidence / 'playing.png')
        if not all(value for value in result.values() if isinstance(value, bool)):
            raise RuntimeError('Playback/volume check failed: ' + json.dumps(result))
    finally:
        device.home()
        device.shell('cmd', 'media_session', 'volume', '--stream', '3', '--set', str(starting_volume))
        result['notes_unchanged'] = device.notes() == initial
        result['playback_stopped_on_exit'] = not bool(playback_line(device, uid))
        (evidence / 'verification.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
    if not result['notes_unchanged'] or not result['playback_stopped_on_exit']:
        raise RuntimeError('Playback cleanup failed')


if __name__ == '__main__':
    main()
