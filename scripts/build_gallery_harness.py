#!/usr/bin/env python3
"""Build a separate, same-signer instrumentation APK for synthetic gallery fixtures."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOLCHAIN = Path(os.environ.get('RABBIT_TOOLCHAIN_DIR', ROOT / '.toolchain')).expanduser().resolve()
paths = json.loads((TOOLCHAIN / 'paths.json').read_text())
java, sdk = Path(paths['java_home']), Path(paths['build_tools'])
android = paths['android_jar']
out = ROOT / '.local/gallery-harness'
key = ROOT / '.local/local-build.keystore'
if not key.is_file():
    raise SystemExit('Build the app first; the existing app signing key is required.')
if out.exists():
    shutil.rmtree(out)
for name in ('classes', 'dex', 'generated'):
    (out / name).mkdir(parents=True, exist_ok=True)
env = dict(os.environ, JAVA_HOME=str(java))
env['PATH'] = str(java / 'bin') + os.pathsep + env['PATH']

def run(args):
    subprocess.run(list(map(str, args)), check=True, cwd=ROOT, env=env)

run([sdk / 'aapt2', 'link', '-o', out / 'resources.apk', '-I', android,
     '--manifest', ROOT / 'tests/gallery/AndroidManifest.xml', '--java', out / 'generated',
     '--min-sdk-version', '30', '--target-sdk-version', '35'])
sources = sorted((ROOT / 'tests/gallery/src').rglob('*.java'))
run([java / 'bin/javac', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
     '-bootclasspath', android, '-d', out / 'classes', *sources])
run([sdk / 'd8', '--lib', android, '--min-api', '30', '--output', out / 'dex', *sorted((out / 'classes').rglob('*.class'))])
shutil.copyfile(out / 'resources.apk', out / 'unsigned.apk')
with zipfile.ZipFile(out / 'unsigned.apk', 'a') as z:
    for dex in (out / 'dex').glob('*.dex'):
        z.write(dex, dex.name, compress_type=zipfile.ZIP_STORED)
run([sdk / 'zipalign', '-f', '-P', '16', '4', out / 'unsigned.apk', out / 'aligned.apk'])
apk = out / 'gallery-test.apk'
run([sdk / 'apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:android',
     '--ks-key-alias', 'rabbitphone', '--out', apk, out / 'aligned.apk'])
run([sdk / 'apksigner', 'verify', apk])
print(json.dumps({'apk': str(apk), 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest()}))
