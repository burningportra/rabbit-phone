#!/usr/bin/env python3
"""Install/restore the unchanged, verified public guide mascot in private app storage."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import struct
import subprocess
import tempfile
from urllib.request import urlopen
import uuid

from device import require_evidence_serial, select_r1

ROOT = Path(__file__).resolve().parents[1]
SOURCE_URL = 'https://www.rabbit.tech/media/r1-user-guide/1-1.png'
SOURCE_PAGE = 'https://www.rabbit.tech/r1-user-guide'
EXPECTED_SHA256 = 'a98649570dd00094d1a95c468f607b5cb3102f94203bc8a1b2aa501ee68165fa'
DIMENSIONS = (1040, 1044)
MAX_BYTES = 262_144
APP = '/data/user/0/com.kevtrinh.rabbitphone'
DIRECTORY = APP + '/files/theme'
DESTINATION = DIRECTORY + '/rabbit-head.png'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def verify_png(data):
    if (len(data) < 24 or len(data) > MAX_BYTES or data[:8] != b'\x89PNG\r\n\x1a\n'
            or data[12:16] != b'IHDR' or struct.unpack('>II', data[16:24]) != DIMENSIONS
            or digest(data) != EXPECTED_SHA256):
        raise RuntimeError('Not the verified, unchanged 1040x1044 Rabbit user-guide PNG')
    return data


def read_source(source):
    if source == SOURCE_URL:
        with urlopen(SOURCE_URL, timeout=30) as response:
            return verify_png(response.read(MAX_BYTES + 1))
    if '://' in source:
        raise RuntimeError('Use the pinned official URL or a local copy of its PNG')
    with Path(source).expanduser().open('rb') as image:
        return verify_png(image.read(MAX_BYTES + 1))


class Device:
    def __init__(self, serial):
        self.serial = serial
        if self.shell('id', '-u') != '0':
            raise RuntimeError('Private mascot installation requires root ADB on the selected R1')
        self.owner = self.shell('stat', '-c', '%u:%g', APP)
        if not re.fullmatch(r'\d+:\d+', self.owner) or int(self.owner.split(':')[0]) < 10000:
            raise RuntimeError('Rabbit Phone private app directory is unavailable')

    def adb(self, *args, check=True):
        result = subprocess.run(['adb', '-s', self.serial, *map(str, args)],
                                capture_output=True, timeout=30)
        if check and result.returncode:
            raise RuntimeError('Mascot ADB operation failed')
        return result

    def shell(self, *args):
        return self.adb('shell', shlex.join(map(str, args))).stdout.decode().strip()

    def test(self, *args):
        result = self.adb('shell', shlex.join(['test', *map(str, args)]), check=False)
        if result.returncode not in (0, 1):
            raise RuntimeError('Could not inspect the mascot destination')
        return result.returncode == 0

    def inspect(self):
        if any(self.test('-L', path) for path in (APP, APP + '/files', DIRECTORY, DESTINATION)):
            raise RuntimeError('Refusing a symbolic-link mascot destination')
        if self.test('-e', DIRECTORY):
            if not self.test('-d', DIRECTORY) or self.shell('stat', '-c', '%u:%g', DIRECTORY) != self.owner:
                raise RuntimeError('Unexpected private theme directory owner/type')
        if not self.test('-e', DESTINATION):
            return None
        if not self.test('-f', DESTINATION):
            raise RuntimeError('Mascot destination is not a regular file')
        uid, gid, mode, links, size = self.shell('stat', '-c', '%u:%g:%a:%h:%s', DESTINATION).split(':')
        if links != '1' or not re.fullmatch(r'[0-7]{1,3}', mode) or not 0 < int(size) <= MAX_BYTES:
            raise RuntimeError('Unexpected mascot file size, permissions, or links')
        return {'sha256': self.shell('sha256sum', DESTINATION).split()[0],
                'owner': uid + ':' + gid, 'mode': mode}

    def install_bytes(self, data, owner, mode, expected):
        if self.inspect() != expected:
            raise RuntimeError('Mascot changed before installation; leaving it intact')
        if not self.test('-d', DIRECTORY):
            self.shell('mkdir', '-p', DIRECTORY)
            self.shell('chown', self.owner, DIRECTORY)
            self.shell('chmod', '700', DIRECTORY)
        stage = DIRECTORY + '/.rabbit-head-' + uuid.uuid4().hex + '.tmp'
        with tempfile.TemporaryDirectory(prefix='rabbit-mascot-') as temporary:
            source = Path(temporary) / 'source.png'
            source.write_bytes(data)
            source.chmod(0o600)
            try:
                self.adb('push', source, stage)
                if self.shell('sha256sum', stage).split()[0] != digest(data):
                    raise RuntimeError('Staged mascot checksum mismatch')
                self.shell('chown', owner, stage)
                self.shell('chmod', mode, stage)
                self.shell('restorecon', '-F', stage)
                if self.inspect() != expected:
                    raise RuntimeError('Mascot changed during staging; leaving it intact')
                self.shell('mv', stage, DESTINATION)
                self.shell('restorecon', '-F', DESTINATION)
                if self.inspect() != {'sha256': digest(data), 'owner': owner, 'mode': mode}:
                    raise RuntimeError('Installed mascot verification failed')
            finally:
                self.adb('shell', shlex.join(['rm', '-f', stage]), check=False)


def save_record(path, record):
    temporary = path.with_suffix('.tmp')
    with temporary.open('w') as output:
        os.chmod(temporary, 0o600)
        json.dump(record, output, indent=2)
        output.write('\n')
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest='action', required=True)
    install = actions.add_parser('install', help='Install the pinned original PNG privately')
    install.add_argument('source', nargs='?', default=SOURCE_URL, help='Official URL or unchanged local PNG')
    actions.add_parser('restore', help='Restore this device\'s exact prior mascot, or remove the optional file')
    args = parser.parse_args()
    data = read_source(args.source) if args.action == 'install' else None
    serial = select_r1(mutation=True)
    device = Device(serial)
    private = ROOT / '.local' / 'mascot' / digest(serial.encode())
    private.mkdir(parents=True, mode=0o700, exist_ok=True)
    for directory in (private, private.parent, private.parent.parent):
        directory.chmod(0o700)
    journal = private / 'transaction.json'
    original = private / 'original.png'
    current = device.inspect()
    if journal.exists():
        record = json.loads(journal.read_text())
        require_evidence_serial(record, serial, journal)
        if record.get('version') != 1 or record.get('app_owner') != device.owner:
            raise RuntimeError('Mascot backup belongs to another app installation or schema')
        if (record.get('installed_sha256') != EXPECTED_SHA256 or record.get('source_url') != SOURCE_URL
                or record.get('dimensions') != list(DIMENSIONS)
                or record.get('phase') not in ('prepared', 'installed', 'restoring', 'restored')):
            raise RuntimeError('Mascot installation journal is not the expected transaction')
        baseline = record['original']
        if baseline is not None:
            if (not isinstance(baseline, dict) or set(baseline) != {'sha256', 'owner', 'mode'}
                    or not re.fullmatch(r'[a-f0-9]{64}', baseline['sha256'])
                    or not re.fullmatch(r'\d+:\d+', baseline['owner'])
                    or not re.fullmatch(r'[0-7]{1,3}', baseline['mode'])):
                raise RuntimeError('Original mascot metadata is invalid')
            if (not original.is_file() or not 0 < original.stat().st_size <= MAX_BYTES
                    or digest(original.read_bytes()) != baseline['sha256']):
                raise RuntimeError('Original mascot backup is missing or changed')
    elif args.action == 'restore':
        raise RuntimeError('No private mascot backup exists for this R1')
    else:
        baseline = current
        if baseline is not None:
            backup = device.adb('exec-out', 'cat', DESTINATION).stdout
            if digest(backup) != baseline['sha256']:
                raise RuntimeError('Mascot changed while its original backup was captured')
            with original.open('wb') as output:
                os.chmod(original, 0o600)
                output.write(backup)
                output.flush()
                os.fsync(output.fileno())
        record = {'version': 1, 'device_serial': serial, 'app_owner': device.owner,
                  'original': baseline, 'directory_created': not device.test('-d', DIRECTORY),
                  'source_url': SOURCE_URL, 'source_page': SOURCE_PAGE,
                  'installed_sha256': EXPECTED_SHA256, 'dimensions': list(DIMENSIONS), 'phase': 'prepared'}
        save_record(journal, record)  # Durable backup before the first device write.
    current_hash = current['sha256'] if current else None
    baseline_hash = baseline['sha256'] if baseline else None
    if current_hash not in (EXPECTED_SHA256, baseline_hash):
        raise RuntimeError('Mascot changed outside this installation; refusing to overwrite it')
    if args.action == 'install':
        record['phase'] = 'prepared'
        save_record(journal, record)
        device.install_bytes(data, device.owner, '600', current)
        record['phase'] = 'installed'
    else:
        record['phase'] = 'restoring'
        save_record(journal, record)
        if baseline is None:
            if device.inspect() != current:
                raise RuntimeError('Mascot changed before restoration; leaving it intact')
            device.shell('rm', '-f', DESTINATION)
            if device.inspect() is not None:
                raise RuntimeError('Optional mascot removal did not finish')
            if record['directory_created']:
                device.adb('shell', shlex.join(['rmdir', DIRECTORY]), check=False)
        else:
            device.install_bytes(original.read_bytes(), baseline['owner'], baseline['mode'], current)
        record['phase'] = 'restored'
    save_record(journal, record)
    print('Private mascot ' + record['phase'] + '. Reopen Home or restart the app to refresh its cached image.')


if __name__ == '__main__':
    main()
