#!/usr/bin/env python3
"""Verify native Settings navigation with reversible preference changes and no media capture."""
import argparse
import json
import re
import time
import xml.etree.ElementTree as ET

from verify_recorder import Device, ROOT, PACKAGE
from verify_playback import volume, wheel_driver, wheel_up, wheel_down, tap_node, playback_line
from verify_card_flows import home, open_deck, select_card, side_click, wait_for, dump_ui, require, snapshot_timer
from device import require_evidence_serial


KEYS = ('screen_brightness', 'screen_brightness_mode', 'screen_off_timeout', 'sound_effects_enabled')


def nodes(ui):
    return list(ET.fromstring(ui).iter('node'))


def has(ui, description):
    return any(n.get('content-desc') == description for n in nodes(ui))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device-test', action='store_true')
    if not parser.parse_args().device_test:
        parser.error('--device-test is required; preference changes are restored after verification')
    d = Device()
    require(not d.microphone_active(), 'Finish recording before Settings verification')
    uid = re.search(r'uid:(\d+)', d.shell('cmd', 'package', 'list', 'packages', '-U', PACKAGE)).group(1)
    require(not playback_line(d, uid), 'Stop playback before Settings verification')
    before_notes, before_volume, before_timer = d.notes(), volume(d), snapshot_timer(d)
    require(not before_timer or json.loads(before_timer).get('phase') == 'NONE',
            'An existing timer is active; this check will not interrupt it')
    evidence = ROOT / 'evidence/settings'
    evidence.mkdir(parents=True, exist_ok=True)
    journal = evidence / 'preference-check.json'
    if journal.exists():
        previous = json.loads(journal.read_text())
        require_evidence_serial(previous, d.serial, journal)
        require(previous.get('phase') == 'restored', 'Recover the previous Settings preference check before retrying')
    result = {'passed': False}
    original = {key: d.shell('settings', 'get', 'system', key) for key in KEYS}
    last = {}
    last_volume = None
    wheel, power = wheel_driver(d), d.power_driver()

    def save_journal(phase='checking'):
        journal.write_text(json.dumps({'device_serial': d.serial, 'phase': phase,
            'original': original, 'original_volume': before_volume,
            'last': last, 'last_volume': last_volume}, indent=2) + '\n')

    save_journal()

    def check(name, condition):
        result[name] = bool(condition)
        require(condition, name)
        print(name + ': passed', flush=True)

    def page(description):
        return wait_for(d, lambda ui: has(ui, description)
                        and (description != 'Device info' or not has(ui, 'Device settings')),
                        'Page did not open: ' + description)

    def tap(description):
        ui = wait_for(d, lambda value: has(value, description), 'Control missing: ' + description)
        matches = [n for n in nodes(ui) if n.get('content-desc') == description]
        require(len(matches) == 1, 'Ambiguous control: ' + description)
        tap_node(d, matches[0])

    def back(description):
        d.shell('input', 'keyevent', 'KEYCODE_BACK')
        return page(description)

    def quick():
        d.shell('input', 'swipe', 240, 12, 240, 245, 300)
        return page('Quick settings')

    def open_settings():
        home(d); quick(); tap('Settings')
        return page('Rabbit settings')

    def setting(key):
        return d.shell('settings', 'get', 'system', key)

    def screenshot(name):
        d.screenshot(evidence / (name + '.png'))

    def settled(predicate):
        deadline = time.monotonic() + 4
        while time.monotonic() < deadline:
            if predicate():
                return True
            time.sleep(.1)
        return False

    try:
        ui = open_settings()
        lease = d.lease()
        check('documented_root_categories', all(has(ui, name) for name in
              ('Display', 'Sound', 'Bluetooth', 'Network', 'Magic', 'Device')))
        check('opening_settings_does_not_write_preferences', all(setting(k) == v for k, v in original.items())
              and volume(d) == before_volume)
        screenshot('root')
        # First selected row is Display; the actual PMIC path opens both levels.
        side_click(d, power); page('Display settings')
        side_click(d, power); page('Adjust brightness')
        check('raw_side_enters_brightness_editor', True)
        initial_brightness = int(original['screen_brightness'])
        brightness_up = initial_brightness <= 242
        expected_brightness = max(0, min(255, initial_brightness + (13 if brightness_up else -13)))
        last['screen_brightness'] = str(expected_brightness)
        last['screen_brightness_mode'] = '0'
        save_journal()
        (wheel_up if brightness_up else wheel_down)(d, wheel)
        check('raw_wheel_changes_real_brightness', settled(lambda:
              setting('screen_brightness') == str(expected_brightness)
              and setting('screen_brightness_mode') == '0'))
        screenshot('brightness')
        side_click(d, power); page('Display settings')
        tap('Auto-sleep'); page('Auto-sleep settings')
        timeout_minutes = 5 if original['screen_off_timeout'] == '600000' else 10
        last['screen_off_timeout'] = str(timeout_minutes * 60000)
        save_journal()
        tap('Set auto-sleep to ' + str(timeout_minutes) + ' minutes'); page('Display settings')
        check('auto_sleep_updates_android', setting('screen_off_timeout') == last['screen_off_timeout'])
        back('Rabbit settings')
        wheel_down(d, wheel); side_click(d, power); page('Sound settings')
        side_click(d, power); page('Adjust media volume')
        audio = d.shell('cmd', 'media_session', 'volume', '--stream', '3', '--get')
        maximum = int(re.search(r'0\.\.(\d+)', audio).group(1))
        increase_volume = before_volume < maximum
        last_volume = before_volume + (1 if increase_volume else -1)
        save_journal()
        (wheel_up if increase_volume else wheel_down)(d, wheel)
        check('raw_wheel_changes_only_media_volume', settled(lambda: volume(d) == last_volume)
              and not playback_line(d, uid))
        screenshot('volume')
        ui = quick()
        check('quick_settings_hides_settings_editor', not has(ui, 'Adjust media volume'))
        back('Adjust media volume')
        check('quick_dismiss_restores_same_editor', volume(d) == last_volume)
        quick(); tap('Settings'); page('Adjust media volume')
        check('settings_shortcut_preserves_existing_child', volume(d) == last_volume)
        side_click(d, power); page('Sound settings')
        original_sound = int(original['sound_effects_enabled']) if original['sound_effects_enabled'] != 'null' else 1
        last['sound_effects_enabled'] = '0' if original_sound != 0 else '1'
        save_journal()
        tap('System sound effects')
        check('sound_effects_toggle_updates_android', settled(lambda:
              setting('sound_effects_enabled') == last['sound_effects_enabled']))
        back('Rabbit settings')
        tap('Network'); page('Network settings')
        screenshot('network')
        check('native_children_share_hardware_lease', d.lease() == lease)
        tap('Wi-Fi')
        wait_for(d, lambda ui: any(n.get('package') == 'com.android.settings' for n in nodes(ui)),
                 'Android Wi-Fi panel did not open')
        check('external_wifi_releases_home_hardware', not re.fullmatch(r'[a-f0-9]{32}',
              d.shell('cat', '/data/user/0/' + PACKAGE + '/files/hardware-lease', check=False)))
        back('Network settings')
        check('android_back_restores_native_parent', True)
        back('Rabbit settings'); tap('Device'); page('Device settings')
        tap('Device info'); page('Device info')
        screenshot('device-info')
        back('Device settings'); back('Rabbit settings')
        back('Rabbit home. Swipe up or use the wheel to open the card stack.')
        check('root_back_returns_home', True)
        # The card entry uses the same native root and standard feature transition.
        open_deck(d, wheel); select_card(d, wheel, 'settings'); side_click(d, power)
        page('Rabbit settings')
        check('settings_card_opens_same_root', True)
        d.shell('am', 'force-stop', PACKAGE)
        open_settings()
        check('restart_preserves_user_settings', all(setting(k) == v for k, v in last.items())
              and volume(d) == last_volume)
        result['passed'] = True
    except Exception as error:
        result['failure'] = str(error).replace(d.serial, '[selected R1]')
        try:
            (evidence / 'failure.xml').write_text(dump_ui(d)); screenshot('failure')
        except Exception:
            pass
    finally:
        # Release the editor before restoration; a queued raw input must never
        # apply an adjustment after the test has restored the user's values.
        try:
            home(d)
        except Exception:
            d.shell('am', 'force-stop', PACKAGE)
        for key, expected in last.items():
            try:
                current = setting(key)
                if current == expected:
                    if original[key] == 'null':
                        d.shell('settings', 'delete', 'system', key)
                    else:
                        d.shell('settings', 'put', 'system', key, original[key])
                result[key + '_restored'] = setting(key) == original[key]
            except Exception:
                result[key + '_restored'] = False
        if last_volume is not None:
            try:
                if volume(d) == last_volume:
                    d.shell('cmd', 'media_session', 'volume', '--stream', '3', '--set', before_volume)
            except Exception:
                pass
        try:
            home(d)
            result['notes_unchanged'] = d.notes() == before_notes
            result['microphone_idle'] = not d.microphone_active()
            result['volume_restored'] = volume(d) == before_volume
            result['timer_unchanged'] = snapshot_timer(d) == before_timer
            result['returned_home'] = True
        except Exception:
            result['returned_home'] = False
        result['passed'] = result['passed'] and all(result.values())
        restored = all(result.get(key + '_restored', True) for key in last) and result.get('volume_restored', False)
        save_journal('restored' if restored else 'needs-review')
        (evidence / 'verification.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2), flush=True)
    raise SystemExit(0 if result['passed'] else 1)


if __name__ == '__main__':
    main()
