#!/usr/bin/env python3
"""Exercise visible R1 navigation and raw wheel/power paths without capturing media."""
import argparse
import json
import re
import shlex
import time
import xml.etree.ElementTree as ET
from verify_recorder import Device, ROOT, PACKAGE
from verify_playback import volume, wheel_down, wheel_up, wheel_driver


def dump_ui(device):
    # Match a normal accessibility service. The default diagnostic dump includes
    # NO_HIDE_DESCENDANTS nodes intentionally and is not a modal-accessibility test.
    path = '/data/local/tmp/rabbit-navigation-ui.xml'
    try:
        device.shell('uiautomator', 'dump', '--compressed', path)
        return device.shell('cat', path)
    finally:
        device.shell('rm', '-f', path)


def selected(ui):
    return next((n.get('content-desc') for n in ET.fromstring(ui).iter('node')
                 if ', card ' in n.get('content-desc', '')), '')


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device-test', action='store_true')
    if not parser.parse_args().device_test:
        parser.error('--device-test is required')
    device = Device()
    require(not device.microphone_active(), 'Finish your recording before navigation verification')
    evidence = ROOT / 'evidence/navigation/device'
    evidence.mkdir(parents=True, exist_ok=True)
    original_notes, original_volume = device.notes(), volume(device)
    result = {}

    def receipt(name, condition):
        result[name] = bool(condition)
        require(condition, 'Navigation check failed: ' + name)

    def screen(name):
        time.sleep(.35)
        ui = dump_ui(device)
        (evidence / (name + '.xml')).write_text(ui)
        device.screenshot(evidence / (name + '.png'))
        return ui

    def swipe(x1, y1, x2, y2):
        device.shell('input', 'swipe', x1, y1, x2, y2, '300')
        time.sleep(.35)

    def click_power():
        driver = shlex.quote(device.power_driver())
        release = 'sendevent ' + driver + ' 1 116 0; sendevent ' + driver + ' 0 0 0'
        device.adb('shell', 'trap ' + shlex.quote(release) + ' EXIT; sendevent ' + driver
                   + ' 1 116 1; sendevent ' + driver + ' 0 0 0; sleep .075; ' + release)
        # Single-click arbitration plus the 720ms feature reveal must settle.
        time.sleep(1.15)

    try:
        device.home()
        ui = screen('home')
        receipt('home_clock_surface', 'Rabbit home.' in ui)
        wheel_down(device)
        ui = screen('deck')
        receipt('first_raw_wheel_opens_deck', bool(selected(ui)))
        before = selected(ui)
        wheel_down(device)
        ui = screen('deck-next')
        receipt('raw_wheel_changes_card', selected(ui) != before and bool(selected(ui)))
        receipt('wheel_preserves_media_volume', volume(device) == original_volume)
        swipe(240, 622, 240, 395)
        receipt('bottom_edge_returns_home', 'Rabbit home.' in screen('quick-home'))
        swipe(240, 485, 240, 260)
        receipt('home_swipe_opens_deck', bool(selected(screen('swipe-deck'))))
        swipe(240, 12, 240, 245)
        ui = screen('quick-settings')
        receipt('top_edge_opens_quick_settings', 'Quick settings' in ui and 'Media volume' in ui)
        receipt('quick_settings_hides_underlay_accessibility', not selected(ui))
        receipt('opening_quick_settings_preserves_volume', volume(device) == original_volume)
        for _ in range(3):
            wheel_down(device)
            time.sleep(.08)
        click_power()
        ui = screen('settings')
        receipt('raw_side_selects_quick_settings_shortcut', 'Rabbit settings' in ui)
        swipe(240, 622, 240, 395)
        receipt('settings_bottom_edge_returns_home', 'Rabbit home.' in screen('settings-home'))
        wheel_down(device)
        # Use the selected card and PMIC click, rather than only tapping a
        # visible card, to verify the actual wheel-to-side-button contract.
        wheel = wheel_driver(device)
        for _ in range(20):
            wheel_up(device, wheel)
            time.sleep(.05)
        for _ in range(20):
            ui = dump_ui(device)
            if selected(ui).startswith('recorder,'):
                recorder_position = re.search(r'card (\d+) of (\d+)', selected(ui)).groups()
                click_power()
                break
            wheel_down(device, wheel)
        else:
            raise RuntimeError('Raw wheel navigation did not select the recorder card')
        ui = screen('recorder-library')
        receipt('raw_side_opens_selected_recorder_card', 'Voice recorder' in ui and 'Media volume' in ui)
        receipt('library_does_not_record', not device.microphone_active())
        receipt('recorder_hides_underlying_cards_from_accessibility', not selected(ui))
        swipe(240, 12, 240, 245)
        receipt('recorder_top_edge_quick_settings', 'Quick settings' in screen('recorder-quick-settings'))
        device.shell('input', 'keyevent', 'KEYCODE_BACK')
        swipe(240, 622, 240, 395)
        receipt('recorder_bottom_edge_returns_home', 'Rabbit home.' in screen('recorder-home'))
        wheel_down(device)
        ui = screen('visited-recorder')
        receipt('visited_feature_stays_in_catalog', selected(ui).startswith('recorder,') and 'active' not in selected(ui)
                and re.search(r'card (\d+) of (\d+)', selected(ui)).groups() == recorder_position)
        swipe(340, 240, 12, 240)
        ui = screen('catalog-recorder-swipe')
        receipt('left_swipe_keeps_permanent_feature_card', selected(ui).startswith('recorder,')
                and re.search(r'card (\d+) of (\d+)', selected(ui)).groups() == recorder_position)
        receipt('catalog_swipe_keeps_saved_notes', device.notes() == original_notes)
    finally:
        result['microphone_remained_off'] = not device.microphone_active()
        result['saved_notes_unchanged'] = device.notes() == original_notes
        result['media_volume_unchanged'] = volume(device) == original_volume
        device.home()
        (evidence / 'verification.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result, indent=2))
    require(all(result.values()), 'Navigation verification did not pass')


if __name__ == '__main__':
    main()
