#!/usr/bin/env python3
"""Opt-in raw-wheel and side-button navigation checks without recording or playback."""
import argparse
import json
import re
import shlex
import time
import xml.etree.ElementTree as ET

from verify_recorder import Device, PACKAGE, ROOT
from verify_playback import volume, wheel_down, wheel_driver, wheel_up


MAX_TICKS = 20


class SnapshotUnavailable(RuntimeError):
    pass


def dump_ui(device):
    path = '/data/local/tmp/rabbit-card-flows-ui.xml'
    try:
        output = device.shell('uiautomator', 'dump', '--compressed', path)
        if path not in output:
            # UiAutomator can exit successfully without a root node while an
            # Activity/window is being replaced. Never read a missing/stale file.
            raise SnapshotUnavailable('Android did not provide a UI snapshot')
        return device.shell('cat', path)
    finally:
        device.shell('rm', '-f', path)


def selected(ui):
    return next((node.get('content-desc') for node in ET.fromstring(ui).iter('node')
                 if ', card ' in node.get('content-desc', '')), '')


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def side_click(device, driver):
    driver = shlex.quote(driver)
    release = 'sendevent ' + driver + ' 1 116 0; sendevent ' + driver + ' 0 0 0'
    device.adb('shell', 'trap ' + shlex.quote(release) + ' EXIT; sendevent ' + driver
               + ' 1 116 1; sendevent ' + driver + ' 0 0 0; sleep .075; ' + release)


def wait_for(device, predicate, message, timeout=12):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            ui = dump_ui(device)
        except SnapshotUnavailable:
            time.sleep(.2)
            continue
        if predicate(ui):
            return ui
        time.sleep(.12)
    raise RuntimeError(message)


def home(device):
    device.home()
    return wait_for(device, lambda ui: 'Rabbit home.' in ui, 'Rabbit Home did not become visible')


def open_deck(device, wheel):
    wheel_down(device, wheel)
    return wait_for(device, lambda ui: bool(selected(ui)), 'Raw wheel did not open card deck')


def select_card(device, wheel, card_id):
    current = re.search(r', card (\d+) of (\d+)', selected(dump_ui(device)))
    require(current is not None, 'Selected card position is unavailable')
    for _ in range(min(MAX_TICKS, int(current.group(1)) - 1)):
        wheel_up(device, wheel)
        time.sleep(.04)
    for _ in range(MAX_TICKS):
        ui = dump_ui(device)
        if selected(ui).startswith(card_id + ','):
            return ui
        wheel_down(device, wheel)
        time.sleep(.1)
    raise RuntimeError('Raw wheel did not select ' + card_id)


def snapshot_timer(device):
    path = '/data/user/0/' + PACKAGE + '/files/timer-state.json'
    return device.shell('cat', path, check=False)


def setup_pair(ui):
    descriptions = [n.get('content-desc', '') for n in ET.fromstring(ui).iter('node')]
    source = next((v[len('language a, '):] for v in descriptions if v.startswith('language a, ')), '')
    target = next((v[len('language b, '):] for v in descriptions if v.startswith('language b, ')), '')
    return (source, target) if source and target else None


def is_picker(ui):
    return setup_pair(ui) is None and any(n.get('selected') == 'true'
        and n.get('class') == 'android.widget.TextView' for n in ET.fromstring(ui).iter('node'))


def translator_flow(device, wheel, driver):
    home(device)
    lease = device.lease()
    open_deck(device, wheel)
    select_card(device, wheel, 'translator')
    side_click(device, driver)
    ui = wait_for(device, setup_pair, 'Translator setup did not open')
    pair = setup_pair(ui)
    side_click(device, driver)
    ui = wait_for(device, is_picker, 'Source language picker did not open')
    # Move away and return without selecting a different persisted language.
    chosen = next(n for n in ET.fromstring(ui).iter('node') if n.get('selected') == 'true')
    first, second = (wheel_up, wheel_down) if chosen.get('text') == 'ukrainian' else (wheel_down, wheel_up)
    first(device, wheel); second(device, wheel)
    side_click(device, driver)
    ui = wait_for(device, setup_pair, 'Original source language did not return to setup')
    require(setup_pair(ui) == pair, 'Language pair changed unexpectedly')
    wheel_down(device, wheel)
    side_click(device, driver)
    wait_for(device, is_picker, 'Target language picker did not open')
    device.shell('input', 'keyevent', 'KEYCODE_BACK')
    ui = wait_for(device, setup_pair, 'Back did not return from target picker to setup')
    require(setup_pair(ui) == pair, 'Picker Back changed the language pair')
    require(device.lease() == lease, 'Translator replaced the Home hardware lease')
    device.shell('input', 'keyevent', 'KEYCODE_BACK')
    wait_for(device, lambda ui: 'Rabbit home.' in ui, 'Translator Back did not return Home')
    require(device.lease() == lease, 'Translator Back replaced the Home hardware lease')


def timer_flow(device, wheel, driver):
    home(device)
    open_deck(device, wheel)
    select_card(device, wheel, 'timer')
    side_click(device, driver)
    # The card's own accessibility title remains visible before its 600ms route
    # transition completes, so do not treat an immediate dump as feature proof.
    time.sleep(.8)
    ui = wait_for(device, lambda value: 'Custom timer' in value and 'Start 1 minute timer' in value,
                  'Timer setup did not open')
    # A running/paused timer is user state. Do not overwrite it merely to reach setup.
    require('start custom timer' in ui or 'Start 1 minute timer' in ui,
            'Existing timer state prevents the non-mutating setup flow check')
    device.shell('input', 'keyevent', 'KEYCODE_BACK')
    return wait_for(device, lambda value: 'Rabbit home.' in value, 'Timer Back did not return Home')


def recorder_flow(device, wheel, driver):
    home(device)
    open_deck(device, wheel)
    select_card(device, wheel, 'recorder')
    side_click(device, driver)
    wait_for(device, lambda ui: 'Voice recorder' in ui and 'Media volume' in ui,
             'Recorder library did not open')
    require(not device.microphone_active(), 'Recorder library unexpectedly activated microphone')
    device.shell('input', 'keyevent', 'KEYCODE_BACK')
    return wait_for(device, lambda ui: 'Rabbit home.' in ui, 'Recorder Back did not return Home')


def interrupt_flow(device, wheel, driver, result):
    original = device.shell('settings', 'get', 'global', 'animator_duration_scale')
    last_set = None
    def scale(value):
        nonlocal last_set
        device.shell('settings', 'put', 'global', 'animator_duration_scale', value)
        last_set = value
        time.sleep(.25)
    def start():
        home(device); open_deck(device, wheel); select_card(device, wheel, 'translator')
        side_click(device, driver)
        # Single is decided after the 320ms double-click window. At scale5 this
        # leaves over two seconds before the reveal; never dump during this wait.
        time.sleep(.8)
    def check(name, condition):
        print(name + ': ' + str(bool(condition)), flush=True)
        result[name] = bool(condition)
        require(condition, name)
    try:
        scale('5')
        start()
        device.shell('input', 'keyevent', 'KEYCODE_BACK')
        time.sleep(3.6)
        check('back_cancels_open_without_late_route', selected(dump_ui(device)).startswith('translator,'))
        start()
        wheel_down(device, wheel)
        time.sleep(3.6)
        ui = dump_ui(device)
        check('wheel_cancels_open_and_moves_selection', bool(selected(ui)) and not selected(ui).startswith('translator,'))
        start()
        device.shell('cmd', 'statusbar', 'expand-notifications')
        time.sleep(.3)
        device.shell('cmd', 'statusbar', 'collapse')
        time.sleep(3.6)
        check('focus_loss_cancels_open_without_late_route', bool(selected(dump_ui(device))))
        # Fully open before interrupting the return animation.
        home(device); open_deck(device, wheel); select_card(device, wheel, 'translator')
        side_click(device, driver); time.sleep(4.2)
        wait_for(device, setup_pair, 'Scaled translator setup did not open')
        device.shell('input', 'keyevent', 'KEYCODE_BACK'); time.sleep(.7)
        wheel_down(device, wheel); time.sleep(6.2)
        check('exit_wheel_returns_home_then_opens_stack', bool(selected(dump_ui(device))))
        scale('0')
        home(device); open_deck(device, wheel); select_card(device, wheel, 'translator')
        side_click(device, driver)
        wait_for(device, setup_pair, 'Reduced-motion translator setup did not open')
        device.shell('input', 'keyevent', 'KEYCODE_BACK')
        wait_for(device, lambda ui: 'Rabbit home.' in ui, 'Reduced-motion Back did not return Home')
        check('reduced_motion_back_returns_home', True)
    finally:
        current = device.shell('settings', 'get', 'global', 'animator_duration_scale')
        if current == last_set:
            if original != 'null': device.shell('settings', 'put', 'global', 'animator_duration_scale', original)
            else: device.shell('settings', 'delete', 'global', 'animator_duration_scale')
        result['animator_duration_scale_restored'] = (
            device.shell('settings', 'get', 'global', 'animator_duration_scale') == original)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device-test', action='store_true', help='Authorize navigation checks on the selected R1')
    parser.add_argument('--interruptions', action='store_true',
                        help='Also exercise scaled and reduced-motion transition cancellation')
    args = parser.parse_args()
    if not args.device_test:
        parser.error('--device-test is required because this check drives raw R1 input')

    device = Device()
    evidence = ROOT / 'evidence/card-flows'
    evidence.mkdir(parents=True, exist_ok=True)
    result = {'passed': False, 'interruptions_requested': args.interruptions}
    notes_before = volume_before = timer_before = None
    try:
        require(not device.microphone_active(), 'Finish an active recording before card-flow verification')
        notes_before = device.notes()
        volume_before = volume(device)
        timer_before = snapshot_timer(device)
        wheel = wheel_driver(device)
        driver = device.power_driver()
        translator_flow(device, wheel, driver)
        result['translator_wheel_side_picker_back_flow'] = True
        print('Translator picker, Back and hardware lease passed.', flush=True)
        timer_flow(device, wheel, driver)
        result['timer_setup_back_flow'] = True
        print('Timer setup and Back passed.', flush=True)
        recorder_flow(device, wheel, driver)
        result['recorder_library_back_flow'] = True
        print('Recorder library and Back passed.', flush=True)
        if args.interruptions:
            interrupt_flow(device, wheel, driver, result)
        result['passed'] = True
    except Exception as error:
        try:
            (evidence / 'failure.xml').write_text(dump_ui(device))
            device.screenshot(evidence / 'failure.png')
        except Exception:
            pass
        result['failure'] = {'type': type(error).__name__,
                             'message': str(error).replace(device.serial, '[selected R1]')}
    finally:
        if notes_before is not None:
            result['notes_unchanged'] = device.notes() == notes_before
            result['microphone_idle'] = not device.microphone_active()
        if volume_before is not None:
            result['media_volume_unchanged'] = volume(device) == volume_before
        if timer_before is not None:
            result['timer_state_unchanged'] = snapshot_timer(device) == timer_before
        try:
            device.home()
        except Exception:
            result['returned_home'] = False
        result['passed'] &= all(result.get(name, False) for name in (
            'notes_unchanged', 'microphone_idle', 'media_volume_unchanged', 'timer_state_unchanged'))
        (evidence / 'verification.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2), flush=True)
    raise SystemExit(0 if result['passed'] else 1)


if __name__ == '__main__':
    main()
