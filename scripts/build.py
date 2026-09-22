#!/usr/bin/env python3
"""Build and sign Rabbit Phone using the pinned local Android SDK and JDK."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOLCHAIN = Path(os.environ.get('RABBIT_TOOLCHAIN_DIR', ROOT / '.toolchain')).expanduser().resolve()
TOOLS = json.loads((TOOLCHAIN / 'paths.json').read_text())
JAVA = Path(TOOLS['java_home'])
SDK = Path(TOOLS['build_tools'])
ANDROID = TOOLS['android_jar']
BUILD = ROOT / 'build'
ENV = dict(os.environ, JAVA_HOME=str(JAVA))
ENV['PATH'] = str(JAVA / 'bin') + os.pathsep + ENV['PATH']


def run(args):
    print('+ ' + ' '.join(str(a) for a in args), flush=True)
    subprocess.run([str(a) for a in args], check=True, cwd=ROOT, env=ENV)


if BUILD.exists():
    shutil.rmtree(BUILD)
for name in ['generated', 'classes', 'dex']:
    (BUILD / name).mkdir(parents=True, exist_ok=True)
run([SDK / 'aapt2', 'compile', '--dir', ROOT / 'app/res', '-o', BUILD / 'resources.zip'])
run([SDK / 'aapt2', 'link', '-o', BUILD / 'resources.apk', '-I', ANDROID,
     '--manifest', ROOT / 'app/AndroidManifest.xml', '--java', BUILD / 'generated',
     '--min-sdk-version', '26', '--target-sdk-version', '35', BUILD / 'resources.zip'])
sources = sorted((ROOT / 'app/src').rglob('*.java')) + sorted((BUILD / 'generated').rglob('*.java'))
assert sources, 'No Java source files'
run([JAVA / 'bin/javac', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
     '-bootclasspath', ANDROID, '-d', BUILD / 'classes', *sources])
classes = sorted((BUILD / 'classes').rglob('*.class'))
run([SDK / 'd8', '--lib', ANDROID, '--min-api', '26', '--output', BUILD / 'dex', *classes])
shutil.copyfile(BUILD / 'resources.apk', BUILD / 'unsigned.apk')
with zipfile.ZipFile(BUILD / 'unsigned.apk', 'a') as archive:
    for dex in sorted((BUILD / 'dex').glob('*.dex')):
        archive.write(dex, dex.name, compress_type=zipfile.ZIP_STORED)
run([SDK / 'zipalign', '-f', '-P', '16', '4', BUILD / 'unsigned.apk', BUILD / 'aligned.apk'])
local = ROOT / '.local'
local.mkdir(mode=0o700, exist_ok=True)
key = local / 'local-build.keystore'
if not key.exists():
    # Standard local Android debug-keystore password; key stays private on this Mac.
    run([JAVA / 'bin/keytool', '-genkeypair', '-keystore', key, '-storepass', 'android',
         '-keypass', 'android', '-alias', 'rabbitphone', '-keyalg', 'RSA', '-keysize', '2048',
         '-validity', '10000', '-dname', 'CN=Rabbit Phone Local Build', '-noprompt'])
    key.chmod(0o600)
apk = BUILD / 'rabbit-phone.apk'
run([SDK / 'apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:android',
     '--ks-key-alias', 'rabbitphone', '--out', apk, BUILD / 'aligned.apk'])
run([SDK / 'apksigner', 'verify', '--verbose', apk])
receipt = {'apk': str(apk), 'size': apk.stat().st_size,
           'sha256': hashlib.sha256(apk.read_bytes()).hexdigest()}
(BUILD / 'build-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
print(json.dumps(receipt, indent=2))
