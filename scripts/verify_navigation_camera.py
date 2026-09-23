#!/usr/bin/env python3
"""Opt-in camera navigation check using PMIC events, without photos or recordings."""
import argparse
import json
import shlex
import time
import xml.etree.ElementTree as ET
from verify_recorder import Device, ROOT
from verify_playback import wheel_down, volume, tap_node
from verify_navigation import dump_ui, require


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device-test', action='store_true')
    if not parser.parse_args().device_test:
        parser.error('--device-test is required; opens the camera preview without capturing')
    d = Device()
    require(not d.microphone_active(), 'Finish recording before the navigation test')
    notes, level = d.notes(), volume(d)
    def photos():
        return d.shell('find', '/sdcard/Pictures/Rabbit Phone', '-type', 'f', check=False)
    before_photos = photos()
    result = {}
    def check(name, value):
        result[name] = bool(value)
        require(value, name)
    def top():
        d.shell('input', 'swipe', 240, 12, 240, 245, 300)
        time.sleep(.3)
    driver = shlex.quote(d.power_driver())
    def press(count=1):
        # Run both edges on-device: separate ADB round trips can exceed the
        # 320ms double-click window even when the host sleeps only briefly.
        release = 'sendevent ' + driver + ' 1 116 0; sendevent ' + driver + ' 0 0 0'
        pulse = ('sendevent ' + driver + ' 1 116 1; sendevent ' + driver
                 + ' 0 0 0; sleep .075; ' + release + '; sleep .085; ')
        d.adb('shell', 'trap ' + shlex.quote(release) + ' EXIT; ' + pulse * count)
    try:
        d.shell('cmd', 'statusbar', 'collapse')
        d.home()
        check('home_visible_before_button_test', 'Rabbit home.' in dump_ui(d))
        press(2); time.sleep(.85)
        ui = dump_ui(d)
        (ROOT / 'evidence/navigation/device/camera-open.xml').write_text(ui)
        check('raw_double_press_opens_camera', 'Shutter' in ui or 'Take photo' in ui)
        # Android's camera privacy indicator temporarily owns the very top strip.
        # The visible header action stays reachable immediately; do not disable
        # the system indicator or count a delayed edge as an immediate-edge pass.
        header = next(n for n in ET.fromstring(ui).iter('node') if n.get('content-desc') == 'Open quick settings')
        tap_node(d, header)
        check('camera_header_opens_quick_settings_immediately', 'Quick settings' in dump_ui(d))
        d.shell('input', 'keyevent', 'KEYCODE_BACK')
        time.sleep(7)
        top(); ui = dump_ui(d)
        (ROOT / 'evidence/navigation/device/camera-quick.xml').write_text(ui)
        check('camera_top_edge_after_system_indicator_settles', 'Quick settings' in ui)
        check('camera_shutter_hidden_from_accessibility', 'Shutter' not in ui and 'Take photo' not in ui)
        press(); time.sleep(.65)
        ui = dump_ui(d)
        check('camera_shortcut_keeps_camera_open', 'Shutter' in ui or 'Take photo' in ui)
        check('camera_shortcut_does_not_capture', photos() == before_photos)
        top()
        for _ in range(3): wheel_down(d); time.sleep(.08)
        press(); time.sleep(.65)
        check('camera_side_button_selects_settings', 'android settings' in dump_ui(d))
        d.shell('input', 'swipe', 240, 622, 240, 395, 300)
        check('camera_settings_returns_home', 'Rabbit home.' in dump_ui(d))
    finally:
        d.shell('cmd', 'statusbar', 'collapse')
        d.home()
        result['no_photos_created'] = photos() == before_photos
        result['saved_notes_unchanged'] = d.notes() == notes
        result['microphone_idle'] = not d.microphone_active()
        result['volume_unchanged'] = volume(d) == level
        evidence = ROOT / 'evidence/navigation/device'
        evidence.mkdir(parents=True, exist_ok=True)
        (evidence / 'camera-verification.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result, indent=2))
    require(all(result.values()), 'Camera navigation verification failed')


if __name__ == '__main__': main()
