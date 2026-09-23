#!/usr/bin/env python3
"""Guarded R1 system font transaction. The owner's private font is never bundled.

prepare stages backups, same-length replacements and an Android raster probe;
apply stops Android before writing existing inodes; restore uses the saved journal.
No boot images, kernel, SELinux policy, aliases or unrelated font files are changed.
Backups must remain under this repository's ignored evidence/ directory.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
import zipfile

from device import require_evidence_serial, select_r1

ROOT = Path(__file__).resolve().parents[1]
STOCK_SHA256 = 'd6ad38cde62d42278205c5134c59a3b094d39d600e3bb42dc8c55d6241e1f343'
FONT_SIZES = {'/system/fonts/Roboto-Regular.ttf': 2371712,
              '/product/fonts/realchoice-sans.ttf': 267712}
TARGETS = tuple(FONT_SIZES) + ('/system/etc/fonts.xml',
                             '/system/etc/font_fallback.xml',
                             '/product/etc/fonts_customization.xml')
MOUNTS = ('/', '/product')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def padded_font(font, size):
    require(digest(font) == STOCK_SHA256, 'Not the verified private stock Rabbit font')
    require(len(font) <= size, 'Font will not fit the existing allocation')
    return font + bytes(size - len(font))


def patch_xml(original, product=False):
    parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
    root = ET.fromstring(original, parser=parser)
    changed = 0
    clock = False
    filename = 'realchoice-sans.ttf' if product else 'Roboto-Regular.ttf'
    for family in root.iter('family'):
        fonts = list(family.findall('font'))
        is_clock = product and family.get('name') == 'cipher-clock'
        if is_clock or any((font.text or '').strip() == filename for font in fonts):
            require(fonts, 'Affected family has no fonts')
            insertion = list(family).index(fonts[0])
            for font in fonts:
                family.remove(font)
            # Static Power Grotesk has no variation axes. Keep family attributes,
            # aliases, unrelated families and their language/emoji fallbacks.
            replacement = ET.Element('font', {'weight': '400', 'style': 'normal'})
            replacement.text = filename
            family.insert(insertion, replacement)
            changed += 1
            clock = clock or is_clock
    require(changed > 0 and (not product or clock), 'Expected font families missing')
    result = ET.tostring(root, encoding='utf-8', xml_declaration=True)
    if len(result) > len(original):
        for item in root.iter():
            if item.text and not item.text.strip():
                item.text = None
            if item.tail and not item.tail.strip():
                item.tail = None
        result = ET.tostring(root, encoding='utf-8', xml_declaration=True)
    require(len(result) <= len(original), 'Edited XML will not fit existing allocation')
    return result + b' ' * (len(original) - len(result))


class Device:
    def __init__(self, serial):
        self.serial = serial

    def adb(self, *args, binary=False, check=True):
        result = subprocess.run(['adb', '-s', self.serial, *args], capture_output=True,
                                timeout=90, text=not binary)
        if check and result.returncode:
            raise RuntimeError('ADB operation failed: ' + repr(result.stderr))
        return result.stdout if binary else result.stdout.strip()

    def shell(self, *args):
        return self.adb('shell', shlex.join(args))

    def read(self, path):
        return self.adb('exec-out', 'cat', path, binary=True)

    def metadata(self, path):
        require(self.shell('readlink', '-f', path) == path, 'Refusing symlink: ' + path)
        fields = self.shell('stat', '-c', '%d:%i:%s:%a:%u:%g', path).split(':')
        require(len(fields) == 6, 'Unexpected file metadata')
        return {'stat': fields, 'label': self.shell('ls', '-Zd', path).split()[0]}

    def sha(self, path):
        return self.shell('sha256sum', path).split()[0]

    def preflight(self):
        require(self.shell('id', '-u') == '0', 'Requires independently running root adbd')
        require(self.shell('getprop', 'init.svc.adbd') == 'running', 'adbd not running')
        require(self.shell('getenforce') == 'Enforcing', 'Expected enforcing SELinux')
        require(self.shell('getprop', 'ro.build.type') == 'userdebug', 'Expected userdebug')
        require('androidboot.verifiedbootstate=orange' in self.shell('cat', '/proc/cmdline'),
                'Expected the previously verified owner-unlocked installation')

    def readonly(self):
        mounts = self.shell('cat', '/proc/mounts').splitlines()
        for path in MOUNTS:
            matches = [line.split() for line in mounts if line.split()[1] == path]
            require(len(matches) == 1 and 'ro' in matches[0][3].split(','),
                    'Mount must be read-only: ' + path)

    def stopped(self):
        self.preflight()
        for service in ('zygote', 'zygote_secondary'):
            require(self.shell('getprop', 'init.svc.' + service) in ('', 'stopped'),
                    service + ' is still running')
        running = self.adb('shell', 'pidof zygote zygote64 system_server || true')
        require(not running, 'Framework processes are still running')
        # A process outside the framework could still hold mmap-backed font pages.
        patterns = ' '.join('-e ' + shlex.quote(path) for path in FONT_SIZES)
        command = ('for maps in /proc/[0-9]*/maps; do '
                   'hit=$(grep -lF ' + patterns + ' "$maps" 2>/dev/null); result=$?; '
                   'if [ "$result" = 0 ]; then echo "$hit"; '
                   'elif [ "$result" != 1 ] && [ -e "$maps" ]; then '
                   'echo "unreadable:$maps"; fi; done')
        require(not self.adb('shell', command), 'Font remains mapped or process maps are unreadable')


def build_probe(output):
    output.mkdir(parents=True, exist_ok=True)
    toolchain = Path(os.environ.get('RABBIT_TOOLCHAIN_DIR', ROOT / '.toolchain'))
    paths = json.loads((toolchain / 'paths.json').read_text())
    java = Path(paths['java_home'])
    sdk = Path(paths['build_tools'])
    classes, dex = output / 'classes', output / 'dex'
    classes.mkdir(exist_ok=True)
    dex.mkdir(exist_ok=True)
    env = dict(os.environ, JAVA_HOME=str(java))
    env['PATH'] = str(java / 'bin') + os.pathsep + env['PATH']
    subprocess.run([str(java / 'bin/javac'), '-source', '8', '-target', '8',
                    '-bootclasspath', paths['android_jar'], '-d', str(classes),
                    str(ROOT / 'tools/FontProbe.java')], check=True, env=env)
    subprocess.run([str(sdk / 'd8'), '--lib', paths['android_jar'], '--min-api', '29',
                    '--output', str(dex), str(classes / 'FontProbe.class')],
                   check=True, env=env)
    jar = output / 'font-probe.jar'
    with zipfile.ZipFile(jar, 'w') as archive:
        archive.write(dex / 'classes.dex', 'classes.dex')
    return jar


def transaction_path(path):
    path = path.resolve()
    require(path.is_relative_to((ROOT / 'evidence').resolve()),
            'Transactions must be inside ignored evidence/')
    return path


def save(directory, record):
    tmp = directory / 'transaction.json.tmp'
    with tmp.open('w') as handle:
        json.dump(record, handle, indent=2)
        handle.write('\n')
        handle.flush()
        os.fsync(handle.fileno())
    tmp.replace(directory / 'transaction.json')
    descriptor = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def prepare(directory, font_path, device):
    require(not directory.exists(), 'Transaction directory already exists; never overwrite backups')
    font = font_path.read_bytes()
    require(digest(font) == STOCK_SHA256, 'Incorrect private stock font')
    device.preflight()
    device.readonly()
    directory.mkdir(mode=0o700, parents=True)
    record = {'version': 1, 'device_serial': device.serial,
              'fingerprint': device.shell('getprop', 'ro.build.fingerprint'),
              'id': uuid.uuid4().hex, 'phase': 'preparing', 'pending': [], 'files': []}
    save(directory, record)
    for index, path in enumerate(TARGETS):
        metadata = device.metadata(path)
        original = device.read(path)
        require(device.metadata(path) == metadata and device.sha(path) == digest(original),
                'Source changed during backup: ' + path)
        require(int(metadata['stat'][2]) == len(original), 'Backup length mismatch')
        if path in FONT_SIZES:
            require(len(original) == FONT_SIZES[path], 'Unexpected stock allocation: ' + path)
            desired = padded_font(font, len(original))
        else:
            desired = patch_xml(original, path.startswith('/product/'))
        for suffix, data in [('before', original), ('after', desired)]:
            destination = directory / (str(index) + '.' + suffix)
            with destination.open('xb') as handle:
                handle.write(data)
                handle.flush()
                os.fsync(handle.fileno())
        record['files'].append({'path': path, 'metadata': metadata,
                                'before': digest(original), 'after': digest(desired)})
        save(directory, record)
    jar = build_probe(directory / 'probe')
    remote = '/data/local/tmp/rabbit-font-' + record['id']
    device.shell('mkdir', '-m', '700', remote)
    try:
        device.adb('push', str(jar), remote + '/probe.jar')
        require(device.sha(remote + '/probe.jar') == digest(jar.read_bytes()), 'Probe copy mismatch')
        font_paths = []
        for index in range(len(FONT_SIZES)):
            target = remote + '/' + str(index) + '.font'
            device.adb('push', str(directory / (str(index) + '.after')), target)
            require(device.sha(target) == record['files'][index]['after'], 'Probe font mismatch')
            font_paths.append(target)
        output = device.shell('env', 'CLASSPATH=' + remote + '/probe.jar',
                              'app_process', '/system/bin', 'FontProbe', *font_paths)
        (directory / 'probe-output.txt').write_text(output + '\n')
        require('PROBE_OK' in output.splitlines(), 'Android font raster probe did not pass')
        record['probe_sha256'] = digest(jar.read_bytes())
        record['phase'] = 'prepared'
        save(directory, record)
    finally:
        device.shell('rm', '-rf', remote)
    print('Prepared and Android-probed. No system files changed. Transaction: ' + str(directory))


def load(directory, device):
    record = json.loads((directory / 'transaction.json').read_text())
    require_evidence_serial(record, device.serial, directory)
    require(record['version'] == 1 and len(record['files']) == len(TARGETS), 'Incomplete transaction')
    require(record['fingerprint'] == device.shell('getprop', 'ro.build.fingerprint'),
            'Firmware differs from the saved transaction')
    require(len(record['id']) == 32 and all(c in '0123456789abcdef' for c in record['id']),
            'Invalid transaction identifier')
    for index, item in enumerate(record['files']):
        require(item['path'] == TARGETS[index], 'Unexpected transaction target')
        require(device.metadata(item['path']) == item['metadata'], 'File metadata changed: ' + item['path'])
        for suffix in ('before', 'after'):
            data = (directory / (str(index) + '.' + suffix)).read_bytes()
            require(digest(data) == item[suffix] and len(data) == int(item['metadata']['stat'][2]),
                    'Backup or payload corrupted: ' + str(index) + '.' + suffix)
    return record


def check_current(directory, device, record, restoring):
    for index, item in enumerate(record['files']):
        current = device.read(item['path'])
        expected = (item['before'], item['after']) if restoring else (item['before'],)
        if digest(current) in expected:
            continue
        require(restoring and index in record.get('pending', []),
                'Untracked font/config modification: ' + item['path'])
        before = (directory / (str(index) + '.before')).read_bytes()
        after = (directory / (str(index) + '.after')).read_bytes()
        # Interrupted dd can leave an old/new mixture. Only the journaled active
        # inode may use this recovery path; unrelated modifications are rejected.
        require(len(current) == len(before) and
                all(value == old or value == new for value, old, new in zip(current, before, after)),
                'Active file contains bytes outside the recorded transaction')


def change(directory, device, restoring=False):
    device.preflight()
    record = load(directory, device)
    if not restoring:
        require(record['phase'] == 'prepared' and record.get('probe_sha256'),
                'Apply requires a fresh prepared transaction and successful device probe')
        device.readonly()
    check_current(directory, device, record, restoring)
    suffix = 'before' if restoring else 'after'
    remote = '/data/local/tmp/rabbit-font-' + record['id']
    device.shell('mkdir', '-p', remote)
    device.shell('chmod', '700', remote)
    for index, item in enumerate(record['files']):
        target = remote + '/' + str(index)
        device.adb('push', str(directory / (str(index) + '.' + suffix)), target)
        require(device.sha(target) == item[suffix], 'Staging checksum mismatch')
    # Keep every unresolved write across recovery attempts: restoring an earlier
    # file must not discard a later file's interrupted-write authorization.
    record['phase'] = 'restoring' if restoring else 'applying'
    save(directory, record)
    finished = False
    readonly = False
    try:
        device.shell('stop')
        deadline = time.monotonic() + 15
        while True:
            try:
                device.stopped()
                break
            except RuntimeError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(.5)
        check_current(directory, device, record, restoring)
        for mount in MOUNTS:
            device.shell('mount', '-o', 'remount,rw', mount)
        for index, item in enumerate(record['files']):
            if index not in record['pending']:
                record['pending'].append(index)
            save(directory, record)
            require(device.metadata(item['path']) == item['metadata'], 'Inode metadata changed')
            device.shell('dd', 'if=' + remote + '/' + str(index), 'of=' + item['path'],
                         'bs=4096', 'conv=notrunc,fsync')
            require(device.sha(item['path']) == item[suffix], 'In-place write checksum mismatch')
            require(device.metadata(item['path']) == item['metadata'], 'In-place write changed metadata')
            record['pending'].remove(index)
            save(directory, record)
        device.shell('sync')
        for item in record['files']:
            require(device.sha(item['path']) == item[suffix], 'Final checksum mismatch')
        finished = True
    finally:
        # Try every mount even if one fails. A failure must leave Android stopped;
        # rerun restore with this transaction after recovering the ADB connection.
        errors = []
        for mount in reversed(MOUNTS):
            try:
                device.shell('mount', '-o', 'remount,ro', mount)
            except Exception as error:
                errors.append(str(error))
        if not errors:
            device.readonly()
            readonly = True
        if finished and readonly:
            record['pending'] = []
            record['phase'] = 'restored' if restoring else 'applied'
            save(directory, record)
            device.shell('start')
            device.shell('rm', '-rf', remote)
        else:
            print('Android left stopped for recovery. Run restore with ' + str(directory), flush=True)
        require(not errors, 'Could not restore read-only mounts: ' + '; '.join(errors))
    print(('Restored' if restoring else 'Applied') + ' verified files; Android restarted. UI/reboot verification required.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    offline = sub.add_parser('build-probe', help='Compile the Java probe locally; no ADB')
    offline.add_argument('--output', type=Path, default=ROOT / 'evidence/font-probe-build')
    prepare_parser = sub.add_parser('prepare', help='Back up, stage and raster-probe; no system writes')
    prepare_parser.add_argument('--font', type=Path, required=True)
    prepare_parser.add_argument('--transaction', type=Path, required=True)
    for action in ('apply', 'restore'):
        command = sub.add_parser(action)
        command.add_argument('--transaction', type=Path, required=True)
    args = parser.parse_args()
    if args.action == 'build-probe':
        print(build_probe(args.output.resolve()))
        return
    directory = transaction_path(args.transaction)
    device = Device(select_r1(mutation=True))
    if args.action == 'prepare':
        prepare(directory, args.font.resolve(), device)
    else:
        change(directory, device, restoring=args.action == 'restore')


if __name__ == '__main__':
    main()
