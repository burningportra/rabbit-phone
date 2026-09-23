#!/usr/bin/env python3
"""Opt-in real UI/driver timer check. Sounds one timer alert; never captures media."""
import argparse
import json
import re
import shlex
import time
import xml.etree.ElementTree as ET
from verify_recorder import Device, FILES, PACKAGE, ROOT
from verify_navigation import dump_ui, selected, require
from verify_card_flows import wait_for
from verify_playback import volume, wheel_driver, wheel_down, wheel_up, tap_node
from verify_volume_persistence import reboot_and_wait


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device-test', action='store_true')
    parser.add_argument('--reboot', action='store_true', help='Reboot once with a three-minute timer running')
    parser.add_argument('--access-recovery', action='store_true', help='Only check hardware controls and active-timer permission loss; no alert')
    args = parser.parse_args()
    if not args.device_test:
        parser.error('--device-test is required; one short timer will sound')
    d = Device()
    require(not d.microphone_active(), 'Finish the active recording before timer verification')
    evidence = ROOT / 'evidence/timer'
    evidence.mkdir(parents=True, exist_ok=True)
    before_notes, before_volume = d.notes(), volume(d)
    owned = set()
    result = {}
    wheel = wheel_driver(d)
    driver = shlex.quote(d.power_driver())

    def state():
        raw = d.shell('cat', FILES + '/timer-state.json', check=False)
        return json.loads(raw) if raw else {'phase': 'NONE', 'token': ''}

    require(state()['phase'] == 'NONE', 'An existing timer is active; it will not be changed')

    def check(name, condition):
        result[name] = bool(condition)
        require(condition, name)
        print(name + ': passed', flush=True)

    def remember(phase):
        value = state()
        require(value['phase'] == phase, 'Expected timer phase ' + phase)
        owned.add(value['token'])
        return value

    def own():
        value = state()
        require(value['token'] in owned, 'Timer changed outside this test; leaving it untouched')
        return value

    def click_power():
        release = 'sendevent ' + driver + ' 1 116 0; sendevent ' + driver + ' 0 0 0'
        d.adb('shell', 'trap ' + shlex.quote(release) + ' EXIT; sendevent ' + driver
              + ' 1 116 1; sendevent ' + driver + ' 0 0 0; sleep .08; ' + release)
        time.sleep(.5)

    def ui(): return dump_ui(d)

    def tap(description):
        nodes = [n for n in ET.fromstring(ui()).iter('node')
                 if n.get('content-desc') == description or n.get('text') == description]
        require(len(nodes) == 1, 'Expected one visible timer control: ' + description)
        tap_node(d, nodes[0]); time.sleep(.2)

    def deck():
        d.shell('cmd', 'statusbar', 'collapse')
        d.home(); require('Rabbit home.' in ui(), 'Home is not visible before wheel navigation')
        wheel_down(d, wheel); time.sleep(.2)
        for _ in range(20): wheel_up(d, wheel)
        time.sleep(.25)

    def menu():
        deck()
        for _ in range(20):
            if selected(ui()).startswith('timer,'):
                click_power()
                wait_for(d, lambda value: 'Custom timer' in value, 'Timer preset screen did not open')
                return
            wheel_down(d, wheel); time.sleep(.2)
        raise RuntimeError('Wheel did not find the timer card')

    def screenshot(name):
        d.screenshot(evidence / (name + '.png'))
        (evidence / (name + '.xml')).write_text(ui())

    def cancel_owned():
        own(); deck()
        require(selected(ui()).startswith('timer,'), 'Owned timer is not at the top of the stack')
        own(); tap('Cancel timer'); remember('NONE')

    def notification_count():
        raw = d.shell('dumpsys', 'notification')
        records = re.split(r'(?=NotificationRecord\()', raw)
        return sum(1 for block in records if block.startswith('NotificationRecord(')
                   and re.search(r'pkg=' + re.escape(PACKAGE) + r'\b', block.split('\n')[0])
                   and 'id=9041' in block.split('\n')[0])

    def access_recovery():
        menu()
        wheel_down(d, wheel); wheel_down(d, wheel); click_power()
        running = remember('RUNNING')
        check('wheel_and_side_button_start_selected_preset', running['duration'] == 300_000)
        screenshot('final-running')
        own(); tap('Pause timer'); paused = remember('PAUSED')
        own(); tap('Resume timer'); resumed = remember('RUNNING')
        check('final_card_pause_resume_controls', resumed['token'] != paused['token'])
        access = d.shell('cmd', 'appops', 'get', PACKAGE, 'SCHEDULE_EXACT_ALARM')
        original = re.search(r'SCHEDULE_EXACT_ALARM:\s*(\w+)', access)
        require(original is not None and original[1] in ('allow', 'default'), 'Unknown original alarm-access mode')
        d.shell('cmd', 'appops', 'set', PACKAGE, 'SCHEDULE_EXACT_ALARM', 'deny')
        try:
            d.home(); recovery = remember('PAUSED')
            check('revoked_access_pauses_existing_timer', 0 < recovery['remaining'] <= resumed['remaining'])
            time.sleep(1.2)
            check('access_loss_does_not_fake_a_running_countdown', state()['remaining'] == recovery['remaining'])
        finally:
            d.shell('cmd', 'appops', 'set', PACKAGE, 'SCHEDULE_EXACT_ALARM', original[1])
        deck()
        restored_ui = ui()
        diagnostic = {'phase': state()['phase'], 'selected': selected(restored_ui),
                      'resume_visible': 'Resume timer' in restored_ui}
        (evidence / 'recovery-visible-state.json').write_text(json.dumps(diagnostic, indent=2) + '\n')
        (evidence / 'recovery-restored.xml').write_text(restored_ui)
        d.screenshot(evidence / 'recovery-restored.png')
        print(json.dumps(diagnostic), flush=True)
        check('restored_access_waits_for_user_resume', diagnostic['phase'] == 'PAUSED' and diagnostic['resume_visible'])
        own(); tap('Resume timer'); remember('RUNNING')
        own(); d.shell('input', 'swipe', 340, 360, 12, 360, 300); time.sleep(.4)
        remember('NONE')
        check('swipe_dismiss_cancels_real_timer', state()['phase'] == 'NONE')

    try:
        if args.access_recovery:
            access_recovery()
        else:
            menu(); screenshot('presets')
            tap('Start 5 minute timer')
            running = remember('RUNNING')
            check('preset_starts_real_timer', running['duration'] == 300_000)
            first = ui(); screenshot('running')
            check('running_card_has_countdown_and_controls', 'Pause timer' in first and 'Cancel timer' in first
                  and bool(re.search(r'0[34]:[0-5][0-9]', first)))
            def countdown(xml):
                description = next(n.get('content-desc', '') for n in ET.fromstring(xml).iter('node') if n.get('text') == 'timer')
                stamp = re.search(r'\b(\d{2}):(\d{2})\b', description)
                require(stamp is not None, 'Accessible countdown is missing')
                return int(stamp[1]) * 60 + int(stamp[2])
            time.sleep(1.3)
            check('countdown_updates_without_leaving_card', countdown(ui()) < countdown(first))
            own(); tap('Pause timer'); paused = remember('PAUSED'); screenshot('paused')
            check('pause_freezes_remaining', paused['remaining'] > 0 and paused['token'] != running['token'])
            time.sleep(1.2)
            check('paused_value_stays_fixed', state()['remaining'] == paused['remaining'])
            d.home(); d.shell('am', 'force-stop', PACKAGE); deck()
            check('pause_survives_app_restart', state()['phase'] == 'PAUSED'
                  and state()['remaining'] == paused['remaining'] and 'Resume timer' in ui())
            own(); tap('Resume timer'); resumed = remember('RUNNING')
            check('resume_uses_new_generation', resumed['token'] != paused['token']
                  and resumed['remaining'] == paused['remaining'])
            cancel_owned()
            check('cancel_returns_timer_to_catalog', state()['phase'] == 'NONE' and 'Pause timer' not in ui())

            # Use a short custom timer for actual receiver delivery with Home paused.
            menu(); tap('Custom timer'); screenshot('custom')
            for _ in range(5): tap('Decrease minutes')
            for _ in range(12): tap('Increase seconds')
            tap('Start custom timer'); short = remember('RUNNING')
            check('custom_duration_is_exact', short['duration'] == 12_000)
            d.shell('am', 'start', '-W', '-a', 'android.settings.SETTINGS')
            deadline = time.monotonic() + 25
            while time.monotonic() < deadline:
                current = own()
                if current['phase'] == 'FINISHED' and current['notified']:
                    break
                time.sleep(.4)
            else: raise RuntimeError('Background timer did not finish and notify')
            check('background_alarm_delivered_once', notification_count() == 1)
            deck(); screenshot('finished')
            finished_ui = ui()
            check('finished_card_offers_restart', 'Restart timer' in finished_ui and '00:00' in finished_ui)
            d.home(); d.shell('am', 'force-stop', PACKAGE)
            # Force-stop administratively clears this package's notifications. Check
            # that platform cleanup before reopening; a persisted delivered timer
            # must not post the alert again after Home restarts.
            check('force_stop_clears_posted_notification', notification_count() == 0)
            deck()
            check('finished_restart_does_not_duplicate_notification', state()['notified'] and notification_count() == 0)
            cancel_owned()
            check('cancel_clears_timer_notification', notification_count() == 0)

            # Missing exact-alarm access must leave a new timer unstarted.
            d.shell('cmd', 'appops', 'set', PACKAGE, 'SCHEDULE_EXACT_ALARM', 'deny')
            try:
                menu(); tap('Start 1 minute timer')
                check('permission_denial_does_not_start_timer', state()['phase'] == 'NONE' and 'Custom timer' in ui())
            finally:
                d.shell('cmd', 'appops', 'set', PACKAGE, 'SCHEDULE_EXACT_ALARM', 'allow')

            if args.reboot:
                menu(); tap('Start 3 minute timer'); old = remember('RUNNING')
                reboot_and_wait(d)
                current = own()
                current_boot = int(d.shell('settings', 'get', 'global', 'boot_count'))
                check('reboot_recovers_timer_deadline', current['phase'] == 'RUNNING'
                      and current['boot'] == current_boot and current['boot'] != old['boot']
                      and 0 < current['remaining'] < old['remaining'])
                deck(); check('reboot_restores_live_card', 'Pause timer' in ui())
                cancel_owned()
    finally:
        current = state()
        if current['phase'] != 'NONE' and current['token'] in owned:
            cancel_owned()
        d.home()
        result['saved_notes_unchanged'] = d.notes() == before_notes
        result['media_volume_unchanged'] = volume(d) == before_volume
        result['microphone_idle'] = not d.microphone_active()
        (evidence / ('recovery-verification.json' if args.access_recovery else 'verification.json')).write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result, indent=2), flush=True)
    require(all(result.values()), 'Timer verification failed')


if __name__ == '__main__': main()
