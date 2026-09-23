#!/usr/bin/env python3
"""Opt-in short recorder motion demo. Exports screen video only; removes test audio."""
import argparse
import json
import re
import subprocess
import time
import uuid

from verify_recorder import Device, NOTES, ROOT
from verify_assistant_recorder import wait_for, focused, asleep


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--record-test', action='store_true')
    parser.add_argument('--from-standby', action='store_true')
    args = parser.parse_args()
    if not args.record_test:
        parser.error('--record-test is required; a short local microphone take is made and deleted')
    device = Device()
    if device.microphone_active():
        raise RuntimeError('Finish the active recording first')
    # Require the decoder before any microphone or screen recording begins.
    subprocess.run(['ffprobe', '-version'], check=True, stdout=subprocess.DEVNULL)
    subprocess.run(['ffmpeg', '-version'], check=True, stdout=subprocess.DEVNULL)
    initial = device.notes()
    driver = device.power_driver()
    directory = ROOT / 'evidence/craft'
    directory.mkdir(parents=True, exist_ok=True)
    remote = '/data/local/tmp/rabbit-reels-demo-' + uuid.uuid4().hex + '.mp4'
    video = directory / 'reels-demo.mp4'
    process = None
    held = False
    test_file = None
    result = {'from_standby': args.from_standby}
    device.home()
    if args.from_standby:
        device.shell('am', 'start', '-W', '-a', 'android.settings.SETTINGS')
        device.shell('input', 'keyevent', 'KEYCODE_SLEEP')
        if not wait_for(lambda: asleep(device)):
            raise RuntimeError('Device did not reach sleep')
    try:
        device.edge(driver, True)
        held = True
        if not wait_for(device.microphone_active, 6):
            raise RuntimeError('Held button did not start the microphone')
        if args.from_standby and '.RecorderAssistActivity' not in focused(device):
            raise RuntimeError('Android did not launch the recorder assistant')
        # Start only after the opaque recorder is foreground, so no lock-screen
        # notifications or unrelated apps enter the exported demonstration.
        process = subprocess.Popen([
            'adb', '-s', device.serial, 'shell', 'screenrecord', '--time-limit', '6',
            '--bit-rate', '1500000', remote], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        time.sleep(1)
        device.screenshot(directory / 'recorder-final-a.png')
        time.sleep(1)
        device.screenshot(directory / 'recorder-final-b.png')
        time.sleep(.5)
        device.edge(driver, False)
        held = False
        if not wait_for(lambda: not device.microphone_active()):
            raise RuntimeError('Microphone did not stop on release')
        created = set(device.notes()) - set(initial)
        if len(created) != 1:
            raise RuntimeError('Expected exactly one new test take')
        candidate = created.pop()
        if not re.fullmatch(re.escape(NOTES) + r'/voice-note-[0-9-]+\.m4a', candidate):
            raise RuntimeError('Unexpected note name; preserving it')
        test_file = candidate
        device.screenshot(directory / 'recorder-final-saved.png')
        _, error = process.communicate(timeout=15)
        if process.returncode:
            raise RuntimeError('Screen recording failed: ' + error.decode(errors='replace'))
        device.adb('pull', remote, video)
        metadata = json.loads(subprocess.check_output([
            'ffprobe', '-v', 'error', '-show_entries',
            'format=duration:stream=codec_type,width,height,avg_frame_rate',
            '-of', 'json', str(video)], text=True))
        result['captured_stream_types'] = [stream['codec_type'] for stream in metadata['streams']]
        if 'video' not in result['captured_stream_types'] or 'audio' in result['captured_stream_types']:
            video.unlink()
            raise RuntimeError('Screen demo unexpectedly contained audio')
        # Android may add frame-timing data tracks. Keep the unchanged H.264 video
        # stream alone for a portable, explicitly silent demonstration.
        if result['captured_stream_types'] != ['video']:
            portable = directory / 'reels-demo-video.mp4'
            subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(video),
                            '-map', '0:v:0', '-c:v', 'copy', '-an', '-dn',
                            '-movflags', '+faststart', str(portable)], check=True)
            portable.replace(video)
            metadata = json.loads(subprocess.check_output([
                'ffprobe', '-v', 'error', '-show_entries',
                'format=duration:stream=codec_type,width,height,avg_frame_rate',
                '-of', 'json', str(video)], text=True))
        result['video_only'] = [stream['codec_type'] for stream in metadata['streams']] == ['video']
        result['video'] = metadata
        result['microphone_stopped'] = not device.microphone_active()
    finally:
        if held:
            device.edge(driver, False)
        # Its own six-second time limit bounds the capture before leaving the
        # recorder; never target another process with a global kill command.
        if process is not None and process.poll() is None:
            process.communicate(timeout=15)
        device.home()
        if test_file is not None:
            device.shell('rm', '-f', test_file)
        device.shell('rm', '-f', remote)
        result['test_removed_notes_unchanged'] = device.notes() == initial
        (directory / 'demo-verification.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
    if not result['test_removed_notes_unchanged']:
        raise RuntimeError('Test take cleanup did not restore original notes')


if __name__ == '__main__':
    main()
