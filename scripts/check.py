#!/usr/bin/env python3
"""Build the R1 helper and run source-only native, gesture, and card-navigation checks."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
TOOLCHAIN = Path(os.environ.get('RABBIT_TOOLCHAIN_DIR', ROOT / '.toolchain')).expanduser().resolve()


def run(args, cwd=ROOT, env=None, capture=False):
    print('+ ' + ' '.join(str(value) for value in args), flush=True)
    return subprocess.run([str(value) for value in args], cwd=cwd, env=env,
                          text=True, capture_output=capture, check=True)


def ndk_clang():
    manifest = json.loads((ROOT / 'toolchain-manifest.json').read_text())
    if 'ndk;27.2.12479018' not in manifest:
        raise RuntimeError('Pinned NDK 27.2.12479018 is missing from toolchain-manifest.json')
    prebuilt = list((TOOLCHAIN / 'ndk-27.2.12479018').glob(
        '*/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android26-clang'))
    if len(prebuilt) != 1:
        raise RuntimeError('Run scripts/prepare_toolchain.py to install pinned NDK 27.2.12479018')
    return prebuilt[0]


def main():
    parser = argparse.ArgumentParser(
        description='Build the Android helper and run host C sanitizer plus Java navigation tests.')
    parser.parse_args()

    output = ROOT / 'hardware/rabbit-hardware'
    run([ndk_clang(), '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
         '-fPIE', '-pie', ROOT / 'hardware/rabbit_hardware.c', '-o', output])

    tools = json.loads((TOOLCHAIN / 'paths.json').read_text())
    java = Path(tools['java_home']) / 'bin'
    host_cc = os.environ.get('CC') or shutil.which('cc')
    if not host_cc:
        raise RuntimeError('A host C compiler is required for sanitizer tests')

    with tempfile.TemporaryDirectory(prefix='rabbit-phone-check-') as temporary:
        temp = Path(temporary)
        native_test = temp / 'rabbit-bridge-test'
        run([host_cc, '-std=c11', '-Wall', '-Wextra', '-Werror', '-pedantic',
             '-fsanitize=address,undefined', '-fno-omit-frame-pointer',
             ROOT / 'hardware/test_core.c', '-o', native_test])
        sanitizer_env = dict(os.environ)
        leak_setting = '0' if sys.platform == 'darwin' else '1'
        sanitizer_env['ASAN_OPTIONS'] = 'halt_on_error=1:detect_leaks=' + leak_setting
        sanitizer_env['UBSAN_OPTIONS'] = 'halt_on_error=1:print_stacktrace=1'
        run([native_test], env=sanitizer_env)

        java_classes = temp / 'java'
        java_classes.mkdir()
        run([java / 'javac', '-encoding', 'UTF-8', '-d', java_classes,
             ROOT / 'app/src/com/kevtrinh/rabbitphone/ButtonGestures.java',
             ROOT / 'tests/ButtonGesturesTest.java',
             ROOT / 'app/src/com/kevtrinh/rabbitphone/CardNavigation.java',
             ROOT / 'tests/CardNavigationTest.java'])
        gesture = run([java / 'java', '-cp', java_classes, 'ButtonGesturesTest'], capture=True)
        navigation = run([java / 'java', '-cp', java_classes, 'CardNavigationTest'], capture=True)
        print(gesture.stdout, end='')
        print(navigation.stdout, end='')
        if '15 gesture cases passed' not in gesture.stdout:
            raise RuntimeError('ButtonGestures test receipt did not report all 15 cases')
        if 'Card navigation cases passed' not in navigation.stdout:
            raise RuntimeError('CardNavigation test receipt did not report all cases')

    run([sys.executable, '-m', 'unittest', 'discover', '-s', 'tests', '-p', 'test_*.py', '-v'])
    print('Checks passed: NDK helper, native sanitizers, gestures, card navigation, font/theme recovery tests.')


if __name__ == '__main__':
    main()
