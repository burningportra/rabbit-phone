#!/usr/bin/env python3
"""Run the Beats sorter and chopper over real catches on the Mac, and optionally render a preview.

Examples:
  python3 scripts/taste_test.py /Volumes/1tb/r1-firmware/beats-backup/<date>/beats/sounds
  python3 scripts/taste_test.py --raw <raw folder> --render /tmp/preview.wav --beat 7
Recordings stay where they are; nothing here writes into the repository.
"""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
TOOLCHAIN = Path(os.environ.get('RABBIT_TOOLCHAIN_DIR', ROOT / '.toolchain')).expanduser().resolve()


def main():
    if len(sys.argv) < 2 or sys.argv[1] in ('-h', '--help'):
        print(__doc__)
        return
    java = Path(json.loads((TOOLCHAIN / 'paths.json').read_text())['java_home']) / 'bin'
    sources = sorted((ROOT / 'app/src/com/kevtrinh/rabbitphone/beats').glob('*.java'))
    with tempfile.TemporaryDirectory(prefix='beats-taste-') as classes:
        subprocess.run([str(java / 'javac'), '-encoding', 'UTF-8', '-d', classes,
                        *map(str, sources), str(ROOT / 'tests/BeatsTaste.java')], check=True)
        subprocess.run([str(java / 'java'), '-cp', classes,
                        'com.kevtrinh.rabbitphone.beats.BeatsTaste', *sys.argv[1:]], check=True)


if __name__ == '__main__':
    main()
