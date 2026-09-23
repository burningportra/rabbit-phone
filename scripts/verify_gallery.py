#!/usr/bin/env python3
"""Exercise native Gallery with app-owned generated images; never capture photos or audio."""
import argparse
import json
import re
import shutil
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

from device import require_evidence_serial
from verify_recorder import Device, ROOT, PACKAGE
from verify_playback import volume, wheel_driver, wheel_down, wheel_up, tap_node
from verify_card_flows import home, open_deck, select_card, side_click, wait_for, dump_ui, snapshot_timer
from gallery_fixtures import install, invoke, inventory, uninstall, safe_to_instrument


def nodes(ui): return list(ET.fromstring(ui).iter('node'))


def node(ui, description):
    found = [n for n in nodes(ui) if n.get('content-desc') == description]
    if len(found) != 1: raise RuntimeError('Expected one control: ' + description)
    return found[0]


def has(ui, description): return any(n.get('content-desc') == description for n in nodes(ui))


def overview(ui): return has(ui, 'Magic gallery') and has(ui, 'Favorites')


def viewer(ui): return has(ui, 'Photo viewer') and 'Loading photo' not in ui


def thumbnail_colors_match(path, ui, names):
    palette = ((26, 246, 255), (255, 0, 159), (107, 99, 255))
    for filename in names:
        bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node(ui, 'Photo ' + filename).get('bounds', ''))
        if bounds is None: return False
        left, top, right, bottom = map(int, bounds.groups())
        x, y = left + (right - left) // 6, top + (bottom - top) // 6
        pixel = subprocess.check_output(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-i', str(path),
            '-vf', f'crop=1:1:{x}:{y}', '-frames:v', '1', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'])
        index = int(filename.rsplit('-', 1)[1].split('.')[0])
        if len(pixel) != 3 or any(abs(actual - expected) > 12 for actual, expected in zip(pixel, palette[index])):
            return False
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device-test', action='store_true')
    if not parser.parse_args().device_test:
        parser.error('--device-test is required; only generated fixture photos are deleted')
    if shutil.which('ffmpeg') is None:
        raise RuntimeError('ffmpeg is required to verify actual thumbnail pixels')
    d = Device(); safe_to_instrument(d)
    before_notes, before_volume, before_timer = d.notes(), volume(d), snapshot_timer(d)
    evidence = ROOT / 'evidence/gallery'; evidence.mkdir(parents=True, exist_ok=True)
    token = uuid.uuid4().hex
    journal = evidence / 'active-fixture.json'
    if journal.exists():
        previous = json.loads(journal.read_text()); require_evidence_serial(previous, d.serial, journal)
        if previous.get('phase') != 'cleaned':
            raise RuntimeError('Recover the recorded gallery fixture token before starting another run')
    original = None
    entry_lease = None
    result = {}
    installed = seeded = False

    def check(name, value):
        result[name] = bool(value)
        if not value: raise RuntimeError(name)
        print(name + ': passed', flush=True)

    def open_gallery():
        nonlocal entry_lease
        home(d); entry_lease = d.lease()
        open_deck(d, wheel); select_card(d, wheel, 'magic gallery'); side_click(d, power)
        return wait_for(d, overview, 'Native Magic Gallery did not open')

    def tap(description):
        ui = wait_for(d, lambda value: has(value, description), 'Control not visible: ' + description)
        tap_node(d, node(ui, description))

    def view_photo(filename):
        tap('Photo ' + filename)
        ui = wait_for(d, lambda value: viewer(value) and has(value, 'Viewing photo ' + filename),
                      'The intended fixture photo did not open')
        return ui

    def back_to_overview():
        d.shell('input', 'keyevent', 'KEYCODE_BACK')
        return wait_for(d, overview, 'Back did not restore gallery overview')

    def snapshot():
        return invoke(d, 'snapshot')['photos']

    try:
        install(d); installed = True
        original = snapshot()
        state = {'device_serial': d.serial, 'token': token, 'phase': 'prepared', 'original': original}
        journal.write_text(json.dumps(state, indent=2) + '\n')
        seeded = True  # Even an interrupted seed needs recovery using this recorded token.
        seeded_rows = invoke(d, 'seed', token)['photos']
        check('three_app_owned_fixture_photos_created', len(seeded_rows) == 3)
        state['phase'] = 'seeded'; state['fixtures'] = seeded_rows
        journal.write_text(json.dumps(state, indent=2) + '\n')
        names = [row['name'] for row in reversed(seeded_rows)]
        wheel, power = wheel_driver(d), d.power_driver()
        ui = open_gallery()
        ui = wait_for(d, lambda value: overview(value) and all(has(value, 'Photo ' + n) for n in names),
                      'Fixture thumbnails did not load')
        check('native_grid_shows_real_media_rows', True)
        check('gallery_keeps_foreground_hardware_lease', d.lease() == entry_lease)
        ready = False
        for _ in range(5):
            d.screenshot(evidence / 'overview.png')
            if thumbnail_colors_match(evidence / 'overview.png', ui, names):
                ready = True; break
            time.sleep(.4)
        check('generated_thumbnail_pixels_are_rendered', ready)
        # Initial hardware selection is Favorites. One wheel tick selects the first photo.
        wheel_down(d, wheel); side_click(d, power)
        ui = wait_for(d, lambda value: viewer(value) and has(value, 'Viewing photo ' + names[0]),
                      'Wheel and side button did not open the first fixture')
        check('wheel_and_side_open_photo', True)
        d.screenshot(evidence / 'viewer.png')
        wheel_down(d, wheel)
        wait_for(d, lambda value: viewer(value) and has(value, 'Viewing photo ' + names[1]),
                 'Viewer wheel did not browse the next photo')
        wheel_up(d, wheel)
        wait_for(d, lambda value: viewer(value) and has(value, 'Viewing photo ' + names[0]),
                 'Viewer wheel did not browse back')
        check('viewer_wheel_browses_photos', True)
        side_click(d, power)
        wait_for(d, lambda value: viewer(value) and has(value, 'Remove from favorites'),
                 'Side button did not favorite the selected fixture')
        check('side_button_favorites_current_photo', True)
        back_to_overview(); tap('Favorites')
        ui = wait_for(d, lambda value: has(value, 'Favorites gallery') and has(value, 'Photo ' + names[0]),
                      'Favorites did not contain the saved fixture')
        check('favorites_filter_excludes_unmarked_fixtures', not any(has(ui, 'Photo ' + n) for n in names[1:]))
        view_photo(names[0]); tap('Remove from favorites')
        ui = wait_for(d, lambda value: has(value, 'Favorites gallery') and not has(value, 'Photo ' + names[0]),
                      'Unfavoriting did not return to the filtered collection')
        check('unfavorite_updates_favorites_collection', True)
        back_to_overview(); view_photo(names[0]); tap('Add to favorites')
        wait_for(d, lambda value: has(value, 'Remove from favorites'), 'Favorite update did not settle')
        home(d); d.shell('am', 'force-stop', PACKAGE)
        open_gallery(); tap('Favorites')
        wait_for(d, lambda value: has(value, 'Favorites gallery') and has(value, 'Photo ' + names[0]),
                 'Favorite did not survive app restart')
        check('favorite_survives_app_restart', True)
        back_to_overview(); view_photo(names[1]); tap('Delete photo')
        ui = wait_for(d, lambda value: has(value, 'Cancel deletion'), 'Delete prompt did not open')
        check('delete_prompt_defaults_to_cancel', node(ui, 'Cancel deletion').get('selected') == 'true')
        check('delete_prompt_hides_underlying_photo_controls', not has(ui, 'Photo viewer') and not has(ui, 'Delete photo'))
        side_click(d, power)
        wait_for(d, viewer, 'Side-button default did not cancel deletion')
        check('side_button_cancel_keeps_photo', has(dump_ui(d), 'Viewing photo ' + names[1]))
        tap('Delete photo'); wait_for(d, lambda value: has(value, 'Cancel deletion'), 'Delete prompt missing')
        d.shell('cmd', 'statusbar', 'expand-notifications'); time.sleep(.3)
        d.shell('cmd', 'statusbar', 'collapse')
        ui = wait_for(d, viewer, 'Focus loss did not cancel the delete prompt')
        check('focus_loss_cancels_delete_confirmation', not has(ui, 'Confirm deletion'))
        tap('Delete photo'); wait_for(d, lambda value: has(value, 'Cancel deletion'), 'Delete prompt missing')
        d.shell('input', 'swipe', 240, 12, 240, 245, 300)
        wait_for(d, lambda value: 'Quick settings' in value, 'Gallery top edge did not open Quick Settings')
        d.shell('input', 'keyevent', 'KEYCODE_BACK')
        ui = wait_for(d, viewer, 'Quick Settings did not restore the photo')
        check('quick_settings_cancels_delete_confirmation', not has(ui, 'Confirm deletion'))
        # Only a fixture whose filename was verified in the viewer is confirmed for deletion.
        check('delete_target_is_owned_fixture', has(ui, 'Viewing photo ' + names[1]))
        tap('Delete photo'); wait_for(d, lambda value: has(value, 'Cancel deletion'), 'Delete prompt missing')
        wheel_down(d, wheel)
        ui = dump_ui(d)
        check('wheel_selects_explicit_delete', node(ui, 'Confirm deletion').get('selected') == 'true')
        side_click(d, power)
        ui = wait_for(d, lambda value: overview(value) and not has(value, 'Photo ' + names[1]),
                      'Confirmed fixture deletion did not update the grid')
        check('confirmed_delete_removes_only_selected_fixture_from_grid',
              has(ui, 'Photo ' + names[0]) and has(ui, 'Photo ' + names[2]))
        d.shell('input', 'keyevent', 'KEYCODE_BACK')
        wait_for(d, lambda value: 'Rabbit home.' in value, 'Gallery root Back did not return Home')
        check('gallery_back_returns_home', True)
        after = snapshot()
        check('provider_confirms_favorite_and_target_deletion',
              next(row for row in after if row['name'] == names[0])['favorite']
              and not any(row['name'] == names[1] for row in after))
    except Exception as error:
        result['failure'] = str(error).replace(d.serial, '[selected R1]')
        try:
            (evidence / 'failure.xml').write_text(dump_ui(d)); d.screenshot(evidence / 'failure.png')
        except Exception:
            pass
        raise
    finally:
        try:
            if seeded:
                invoke(d, 'cleanup', token)
                cleaned = snapshot()
                result['original_photos_unchanged'] = inventory(cleaned) == inventory(original)
                result['fixture_photos_removed'] = not any(token in row['name'] for row in cleaned)
                if result['fixture_photos_removed']:
                    state['phase'] = 'cleaned'; journal.write_text(json.dumps(state, indent=2) + '\n')
            if installed:
                uninstall(d)
            d.home()
        finally:
            result['saved_notes_unchanged'] = d.notes() == before_notes
            result['media_volume_unchanged'] = volume(d) == before_volume
            result['timer_unchanged'] = snapshot_timer(d) == before_timer
            result['microphone_idle'] = not d.microphone_active()
            (evidence / 'verification.json').write_text(json.dumps(result, indent=2) + '\n')
            print(json.dumps(result, indent=2), flush=True)
    if not all(value is True for value in result.values()):
        raise RuntimeError('Gallery verification failed')


if __name__ == '__main__': main()
