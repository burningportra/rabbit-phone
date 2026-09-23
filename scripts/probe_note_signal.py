#!/usr/bin/env python3
"""Measure private voice-note signal levels on the R1 without exporting audio."""
import argparse
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import uuid

from verify_recorder import Device, ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--analyze-notes', action='store_true',
                        help='Decode existing notes on-device and return only level statistics')
    if not parser.parse_args().analyze_notes:
        parser.error('--analyze-notes is required')
    toolchain = Path(os.environ.get('RABBIT_TOOLCHAIN_DIR', ROOT / '.toolchain'))
    paths = json.loads((toolchain / 'paths.json').read_text())
    environment = dict(os.environ, JAVA_HOME=paths['java_home'])
    environment['PATH'] = paths['java_home'] + '/bin' + os.pathsep + environment['PATH']
    device = Device()
    remote = '/data/local/tmp/rabbit-note-signal-' + uuid.uuid4().hex + '.dex'
    results = []
    with tempfile.TemporaryDirectory(prefix='rabbit-note-signal-') as temporary:
        directory = Path(temporary)
        (directory / 'classes').mkdir()
        (directory / 'dex').mkdir()
        subprocess.run([
            paths['java_home'] + '/bin/javac', '-source', '8', '-target', '8',
            '-bootclasspath', paths['android_jar'], '-d', directory / 'classes',
            ROOT / 'scripts/probes/NoteSignalProbe.java'], check=True, env=environment)
        subprocess.run([
            paths['build_tools'] + '/d8', '--lib', paths['android_jar'], '--min-api', '26',
            '--output', directory / 'dex', directory / 'classes/NoteSignalProbe.class'],
            check=True, env=environment)
        device.adb('push', directory / 'dex/classes.dex', remote)
        try:
            for note in device.notes():
                if not note.endswith('.m4a'):
                    continue
                output = device.adb('shell', 'CLASSPATH=' + shlex.quote(remote)
                                    + ' app_process /system/bin NoteSignalProbe ' + shlex.quote(note))
                results.append(json.loads(next(line for line in output.splitlines()
                                               if line.startswith('{'))))
        finally:
            device.shell('rm', '-f', remote)
    print(json.dumps(results, indent=2))


if __name__ == '__main__':
    main()
