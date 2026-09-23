#!/usr/bin/env python3
"""Compile the finite Android 16 clock-spacing utility; no font assets included."""
import json
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def build():
    toolchain = Path(os.environ.get('RABBIT_TOOLCHAIN_DIR', ROOT / '.toolchain'))
    paths = json.loads((toolchain / 'paths.json').read_text())
    java, sdk = Path(paths['java_home']), Path(paths['build_tools'])
    output = ROOT / 'build/clock'
    classes, dex = output / 'classes', output / 'dex'
    classes.mkdir(parents=True, exist_ok=True)
    dex.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, JAVA_HOME=str(java))
    env['PATH'] = str(java / 'bin') + os.pathsep + env['PATH']
    subprocess.run([str(java / 'bin/javac'), '-source', '8', '-target', '8',
                    '-bootclasspath', paths['android_jar'], '-d', str(classes),
                    str(ROOT / 'tools/ClockOverlay.java')], check=True, env=env)
    subprocess.run([str(sdk / 'd8'), '--lib', paths['android_jar'], '--min-api', '36',
                    '--output', str(dex), str(classes / 'ClockOverlay.class')], check=True, env=env)
    unaligned = output / 'unaligned.jar'
    with zipfile.ZipFile(unaligned, 'w', compression=zipfile.ZIP_STORED) as archive:
        archive.write(dex / 'classes.dex', 'classes.dex')
    jar = ROOT / 'build/clock-overlay.jar'
    subprocess.run([str(sdk / 'zipalign'), '-f', '4', str(unaligned), str(jar)], check=True, env=env)
    return jar


if __name__ == '__main__':
    print(build())
