#!/usr/bin/env python3
"""Opt-in active-card checks using visible R1 controls; never captures or plays audio."""
import argparse
import json
import re
import time
import xml.etree.ElementTree as ET

from verify_card_flows import (dump_ui, home, open_deck, require, select_card,
                               setup_pair, side_click, wait_for)
from verify_playback import tap_node, volume, wheel_down, wheel_driver, wheel_up
from verify_recorder import Device, FILES, PACKAGE, ROOT


CATALOG_START = ('camera', 'magic gallery', 'timer', 'translator')
BOUNDS = re.compile(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]')


def timer_state(device):
    raw = device.shell('cat', FILES + '/timer-state.json', check=False)
    if not raw:
        return {'phase': 'NONE', 'token': ''}
    value = json.loads(raw)
    require(value.get('phase') in ('NONE', 'RUNNING', 'PAUSED', 'FINISHED'),
            'Timer state has an unknown phase')
    return value


def node_bounds(node):
    match = BOUNDS.fullmatch(node.get('bounds', ''))
    require(match is not None, 'Visible card has invalid accessibility bounds')
    return tuple(map(int, match.groups()))


def near(actual, expected, tolerance=4):
    return all(abs(left - right) <= tolerance for left, right in zip(actual, expected))


def card_node(ui, title):
    matches = [node for node in ET.fromstring(ui).iter('node')
               if node.get('text', '').lower() == title
               and node.get('content-desc', '').lower().startswith(title)]
    require(len(matches) == 1, 'Expected one visible accessibility card: ' + title)
    return matches[0]


def tap_label(device, label):
    def visible(ui):
        return any(node.get('text') == label or node.get('content-desc') == label
                   for node in ET.fromstring(ui).iter('node'))
    ui = wait_for(device, visible, 'Visible control did not settle: ' + label)
    matches = [node for node in ET.fromstring(ui).iter('node')
               if node.get('text') == label or node.get('content-desc') == label]
    require(len(matches) == 1, 'Expected one visible control: ' + label)
    tap_node(device, matches[0])


def selected_description(ui):
    return next((node.get('content-desc', '') for node in ET.fromstring(ui).iter('node')
                 if ', card ' in node.get('content-desc', '')), '')


def legacy_opened_cache_absent(device):
    path = '/data/user/0/' + PACKAGE + '/shared_prefs/HomeActivity.xml'
    return 'opened_cards' not in device.shell('cat', path, check=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device-test', action='store_true',
                        help='Authorize visible wheel, side-button, timer, and app-restart checks')
    args = parser.parse_args()
    if not args.device_test:
        parser.error('--device-test is required because this check drives raw R1 input')

    device = Device()
    evidence = ROOT / 'evidence/active-cards'
    evidence.mkdir(parents=True, exist_ok=True)
    result = {'passed': False}
    owned = set()
    notes_before = volume_before = microphone_before = None
    wheel = driver = None

    def check(name, condition):
        result[name] = bool(condition)
        require(condition, name)
        print(name + ': passed', flush=True)

    def remember(phase):
        value = timer_state(device)
        require(value.get('phase') == phase, 'Expected timer phase ' + phase)
        token = value.get('token', '')
        require(bool(token), 'Created timer state is missing its ownership token')
        owned.add(token)
        return value

    def own():
        value = timer_state(device)
        require(value.get('token') in owned,
                'Timer changed outside this test; leaving it untouched')
        return value

    def deck_at_top():
        home(device)
        open_deck(device, wheel)
        current = re.search(r', card (\d+) of (\d+)', selected_description(dump_ui(device)))
        require(current is not None, 'Selected card position is unavailable')
        for _ in range(int(current.group(1)) - 1):
            wheel_up(device, wheel)
            time.sleep(.04)
        return wait_for(device, lambda ui: bool(selected_description(ui)),
                        'Card deck did not settle at its first card')

    def cancel_owned():
        own()
        ui = deck_at_top()
        require(selected_description(ui).startswith('timer,')
                and ', active' in selected_description(ui),
                'Owned timer is not the first active card')
        own()
        tap_label(device, 'Cancel timer')
        wait_for(device, lambda ui: selected_description(ui).startswith('camera,'),
                 'Cancel did not return the timer to the catalog')
        remember('NONE')

    try:
        microphone_before = device.microphone_active()
        require(not microphone_before,
                'Finish the active recording before active-card verification')
        notes_before = device.notes()
        volume_before = volume(device)
        require(timer_state(device).get('phase') == 'NONE',
                'An existing timer is active; it will not be changed')
        wheel = wheel_driver(device)
        driver = device.power_driver()

        home(device)
        open_deck(device, wheel)
        select_card(device, wheel, 'timer')
        side_click(device, driver)
        setup = wait_for(device,
                         lambda ui: 'Custom timer' in ui and 'Start 5 minute timer' in ui,
                         'Visible Timer presets did not open')
        check('five_minute_preset_is_visible', 'Start 5 minute timer' in setup)
        tap_label(device, 'Start 5 minute timer')
        running = remember('RUNNING')
        check('visible_preset_starts_five_minute_timer', running.get('duration') == 300_000)
        tap_label(device, 'Pause timer')
        paused = remember('PAUSED')
        check('owned_timer_paused_immediately', paused.get('remaining', 0) > 0
              and paused.get('token') != running.get('token'))

        paused_ui = wait_for(device,
                             lambda ui: selected_description(ui).startswith('timer,')
                             and ', active' in selected_description(ui)
                             and 'Resume timer' in ui,
                             'Paused active Timer card did not settle')
        timer = card_node(paused_ui, 'timer')
        camera = card_node(paused_ui, 'camera')
        check('active_timer_accessibility_bounds', near(node_bounds(timer), (88, 118, 388, 508)))
        check('next_camera_cue_is_twelve_pixels', near(node_bounds(camera), (88, 538, 388, 550)))
        visible_catalog = [node.get('text', '').lower()
                           for node in ET.fromstring(paused_ui).iter('node')
                           if node.get('text', '').lower() in CATALOG_START]
        check('lower_catalog_has_no_hidden_virtual_nodes', visible_catalog == ['timer', 'camera'])
        device.screenshot(evidence / 'paused-active-timer.png')

        select_card(device, wheel, 'translator')
        translator_selection = selected_description(dump_ui(device))
        check('translator_is_not_promoted', ', active' not in translator_selection)
        side_click(device, driver)
        wait_for(device, setup_pair, 'Translator setup did not open through wheel and side button')
        device.shell('input', 'keyevent', 'KEYCODE_BACK')
        wait_for(device, lambda ui: 'Rabbit home.' in ui,
                 'Translator Back did not return Home')
        check('translator_wheel_side_back_home', not device.microphone_active())

        open_deck(device, wheel)
        select_card(device, wheel, 'recorder')
        recorder_selection = selected_description(dump_ui(device))
        check('recorder_is_not_promoted', ', active' not in recorder_selection)
        side_click(device, driver)
        wait_for(device, lambda ui: 'Voice recorder' in ui and 'Media volume' in ui,
                 'Recorder library did not open')
        check('recorder_library_browsed_without_capture', not device.microphone_active())
        device.shell('input', 'keyevent', 'KEYCODE_BACK')
        wait_for(device, lambda ui: 'Rabbit home.' in ui,
                 'Recorder Back did not return Home')

        top_ui = deck_at_top()
        current = own()
        check('raw_wheel_returns_to_same_paused_timer',
              selected_description(top_ui).startswith('timer,')
              and ', active' in selected_description(top_ui)
              and 'Resume timer' in top_ui
              and current.get('phase') == 'PAUSED'
              and current.get('token') == paused.get('token')
              and current.get('remaining') == paused.get('remaining'))

        device.shell('am', 'force-stop', PACKAGE)
        restored_ui = deck_at_top()
        restored = own()
        check('paused_active_timer_restores_after_force_stop',
              restored.get('phase') == 'PAUSED'
              and restored.get('token') == paused.get('token')
              and restored.get('remaining') == paused.get('remaining')
              and selected_description(restored_ui).startswith('timer,')
              and ', active' in selected_description(restored_ui)
              and 'Resume timer' in restored_ui)
        check('active_timer_restore_ignores_legacy_opened_cache',
              legacy_opened_cache_absent(device))
        device.screenshot(evidence / 'restored-active-timer.png')

        cancel_owned()
        catalog = []
        active_flags = []
        for index, _ in enumerate(CATALOG_START):
            ui = dump_ui(device)
            description = selected_description(ui)
            catalog.append(description.split(',', 1)[0])
            active_flags.append(', active' in description)
            if index + 1 < len(CATALOG_START):
                wheel_down(device, wheel)
                wait_for(device,
                         lambda value, title=CATALOG_START[index + 1]:
                         selected_description(value).startswith(title + ','),
                         'Catalog did not advance to ' + CATALOG_START[index + 1])
        check('catalog_order_retained_after_cancel', tuple(catalog) == CATALOG_START)
        check('catalog_active_flags_are_cleared', not any(active_flags)
              and timer_state(device).get('phase') == 'NONE')
        result['passed'] = True
    except Exception as error:
        result['failure'] = {
            'type': type(error).__name__,
            'message': str(error).replace(device.serial, '[selected R1]'),
        }
    finally:
        try:
            current = timer_state(device)
            if current.get('phase') != 'NONE' and current.get('token') in owned:
                cancel_owned()
            result['owned_timer_removed'] = timer_state(device).get('phase') == 'NONE'
        except Exception as cleanup_error:
            result['owned_timer_removed'] = False
            result['cleanup_failure'] = {
                'type': type(cleanup_error).__name__,
                'message': str(cleanup_error).replace(device.serial, '[selected R1]'),
            }
        try:
            home(device)
            result['returned_home'] = True
        except Exception:
            result['returned_home'] = False
        if notes_before is not None:
            result['saved_notes_unchanged'] = device.notes() == notes_before
        if microphone_before is not None:
            result['microphone_unchanged'] = device.microphone_active() == microphone_before
        if volume_before is not None:
            result['media_volume_unchanged'] = volume(device) == volume_before
        required = ('owned_timer_removed', 'returned_home', 'saved_notes_unchanged',
                    'microphone_unchanged', 'media_volume_unchanged')
        result['passed'] = result['passed'] and all(result.get(name, False) for name in required)
        (evidence / 'verification.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2), flush=True)
    raise SystemExit(0 if result['passed'] else 1)


if __name__ == '__main__':
    main()
