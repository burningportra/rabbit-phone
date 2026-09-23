#!/usr/bin/env python3
"""Separate signed instrumentation for app-owned synthetic gallery fixtures, never camera capture."""
import json
import re

from verify_recorder import PACKAGE, ROOT
from verify_card_flows import snapshot_timer
from verify_playback import playback_line

HARNESS = 'com.kevtrinh.rabbitphone.gallerytest'
COMPONENT = HARNESS + '/.GalleryFixtures'
APK = ROOT / '.local/gallery-harness/gallery-test.apk'


def safe_to_instrument(device):
    if device.microphone_active():
        raise RuntimeError('Finish recording before gallery instrumentation')
    state = snapshot_timer(device)
    if state and json.loads(state).get('phase') != 'NONE':
        raise RuntimeError('An existing timer is active; instrumentation will not stop its app')
    uid = re.search(r'uid:(\d+)', device.shell('cmd', 'package', 'list', 'packages', '-U', PACKAGE)).group(1)
    if playback_line(device, uid):
        raise RuntimeError('Stop playback before gallery instrumentation')


def install(device):
    safe_to_instrument(device)
    if not APK.is_file():
        raise RuntimeError('Build scripts/build_gallery_harness.py first')
    device.adb('install', '-r', '--no-incremental', APK)


def invoke(device, action, token=None):
    safe_to_instrument(device)
    if action not in ('snapshot', 'seed', 'cleanup'):
        raise ValueError('Unknown fixture action')
    if action in ('seed', 'cleanup') and (token is None or not re.fullmatch(r'[a-f0-9]{32}', token)):
        raise ValueError('A private 32-character fixture token is required')
    arguments = ['am', 'instrument', '-w', '-e', 'action', action]
    if token:
        arguments += ['-e', 'token', token]
    output = device.shell(*arguments, COMPONENT)
    match = re.search(r'^INSTRUMENTATION_RESULT: result=(.+)$', output, re.M)
    if not match:
        raise RuntimeError('Gallery instrumentation did not return a result')
    result = json.loads(match.group(1))
    if not result.get('ok'):
        raise RuntimeError('Gallery instrumentation failed: ' + result.get('error', 'unknown'))
    return result


def inventory(rows):
    """Ignore provider bookkeeping that may change while scanning thumbnails."""
    return {str(row['id']): {key: row[key] for key in ('name', 'size', 'favorite', 'sha256')}
            for row in rows}


def uninstall(device):
    device.adb('uninstall', HARNESS)
