#!/usr/bin/env python3
"""Local same-certificate APK themes; never uninstall, clear data or write /system.

Build is offline after public AOSP development signing keys have been cached.
Prepare only reads the selected R1 and creates immutable device-bound backups.
Update/restore install exactly one verified APK through a tracked replacement session.
Uncertain sessions block all further work until explicit authoritative settlement.
SystemUI uses Android's supported staged replacement: READY is not installed;
finalize requires a real reboot, APPLIED session status and the exact APK hash.
Private fonts, APKs, public development keys and receipts stay in ignored paths.
"""
import argparse
import base64
import contextlib
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import struct
import subprocess
import tempfile
import time
import urllib.request
import uuid
import zipfile
import zlib

from device import require_evidence_serial, select_r1
from contact_styles import patch_contact_styles

ROOT = Path(__file__).resolve().parents[1]
CERTS = {
    'platform': 'c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8',
    'shared': '28bbfe4a7b97e74681dc55c2fbb6ccb8d6c74963733f6af6ae74d8c3a6e879fd',
}
PACKAGES = {'com.android.systemui': 'platform', 'com.techyminati.pages': 'platform',
            'com.android.contacts': 'shared', 'com.bnyro.recorder': 'platform',
            'com.dot.gallery': 'platform', 'org.breezyweather': 'platform'}
STAGED_PACKAGE = 'com.android.systemui'
FONT_SHA = 'd6ad38cde62d42278205c5134c59a3b094d39d600e3bb42dc8c55d6241e1f343'
FONT_ENTRY = 'assets/flutter_assets/fonts/OPlusSans3-Light.ttf'
FONT_RECIPES = {
    'com.techyminati.pages': (FONT_ENTRY, '8c7cfc8bb17933e037b91f8fa81dc8eef577a44c8fe1a987e316b4e900a8119b'),
    'com.bnyro.recorder': ('res/Ww.ttf', 'acbf6c59d8c5765ffa9af2a839249e2800e0b107aad4045cf5c070765fa29322'),
    'com.dot.gallery': ('res/RV.ttf', 'acbf6c59d8c5765ffa9af2a839249e2800e0b107aad4045cf5c070765fa29322'),
    'org.breezyweather': ('res/Gw.ttf', '0ed4b4f29a0abed3a5049270f28d8e11c797edc7c0270cc51495c0578622f82a'),
}
LAYOUTS = ('res/layout/empty_home_view.xml', 'res/layout/empty_account_view.xml',
           'res/layout/empty_group_view.xml', 'res/layout-land/empty_group_view.xml')
OLD_COLOR, NEW_COLOR = b'#7E7EF7', b'#F5EFE1'


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def immutable_write(path, data):
    """Exclusive creation; reruns cannot replace a rollback artifact."""
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    Path(path).chmod(0o400)
    parent = os.open(Path(path).parent, os.O_RDONLY)
    try:
        os.fsync(parent)
    finally:
        os.close(parent)


def write_receipt(path, data):
    immutable_write(path, (json.dumps(data, indent=2, sort_keys=True) + '\n').encode())


def ignored_path(path, *areas):
    path = Path(path).resolve()
    require(any(path.is_relative_to((ROOT / area).resolve()) and path != (ROOT / area).resolve()
                for area in areas), 'Artifacts must stay in the ignored ' + ', '.join(areas))
    return path


def signature_entry(name):
    upper = name.upper()
    return upper.startswith('META-INF/') and ('/' not in upper[9:]) and (
        upper == 'META-INF/MANIFEST.MF' or upper.endswith(('.SF', '.RSA', '.DSA', '.EC'))
        or upper.startswith('META-INF/SIG-'))


def dex_color_patch(data):
    require(data[:4] == b'dex\n' and len(data) >= 112, 'Expected standard DEX')
    require(struct.unpack_from('<I', data, 32)[0] == len(data), 'DEX file size mismatch')
    require(data[12:32] == hashlib.sha1(data[32:]).digest(), 'Invalid original DEX SHA1')
    require(struct.unpack_from('<I', data, 8)[0] == zlib.adler32(data[12:]),
            'Invalid original DEX Adler32')
    require(data.count(OLD_COLOR) == 1, 'Expected exactly one old clock literal')
    offset = data.index(OLD_COLOR)
    # ASCII MUTF8 string_data_item: one-byte UTF16 length, seven bytes, NUL.
    require(data[offset - 1:offset + 8] == b'\x07' + OLD_COLOR + b'\0',
            'Clock literal is not the expected MUTF8 string')
    count, table = struct.unpack_from('<II', data, 56)
    require(table >= 112 and table + count * 4 <= len(data), 'Invalid DEX string table')
    string_offsets = [struct.unpack_from('<I', data, table + i * 4)[0] for i in range(count)]
    require(string_offsets.count(offset - 1) == 1, 'Clock literal missing from DEX string table')
    index = string_offsets.index(offset - 1)
    require(0 < index < count - 1, 'Expected clock literal string-table neighbors')

    def ascii_neighbor(position):
        start = string_offsets[position]
        require(start < len(data) and data[start] < 128, 'Unsupported neighbor string length')
        end = start + 1 + data[start]
        require(end < len(data) and data[end] == 0, 'Malformed neighbor MUTF8 string')
        value = data[start + 1:end]
        require(all(0 < byte < 128 for byte in value), 'Non-ASCII neighbor needs independent review')
        return value

    left, right = ascii_neighbor(index - 1), ascii_neighbor(index + 1)
    require(left < OLD_COLOR < right and left < NEW_COLOR < right,
            'Clock replacement would violate sorted DEX string IDs')
    result = bytearray(data)
    result[offset:offset + 7] = NEW_COLOR
    result[12:32] = hashlib.sha1(result[32:]).digest()
    struct.pack_into('<I', result, 8, zlib.adler32(result[12:]))
    require(len(result) == len(data) and result[:8] == data[:8]
            and result[32:offset] == data[32:offset] and result[offset + 7:] == data[offset + 7:],
            'Unexpected DEX change')
    return bytes(result)


def replacements(package, archive):
    if package == 'com.android.systemui':
        dexes = {name: archive.read(name) for name in archive.namelist()
                 if re.fullmatch(r'classes(?:[0-9]+)?\.dex', name)}
        require(sum(data.count(OLD_COLOR) for data in dexes.values()) == 1,
                'Expected exactly one clock literal across all DEX files')
        require('classes2.dex' in dexes and OLD_COLOR in dexes['classes2.dex'],
                'Clock literal moved from the verified classes2.dex')
        return {'classes2.dex': dex_color_patch(dexes['classes2.dex'])}
    if package in FONT_RECIPES:
        entry, original_hash = FONT_RECIPES[package]
        require(digest(archive.read(entry)) == original_hash,
                'Original text font asset hash mismatch: ' + entry)
        font = (ROOT / 'evidence/theme/stock-fonts/PowerGroteskRegular.otf').read_bytes()
        require(digest(font) == FONT_SHA, 'Private stock font hash mismatch')
        return {entry: font}
    require(package == 'com.android.contacts', 'Unsupported package')
    receipt = json.loads((ROOT / 'build/theme-layouts/receipt.json').read_text())
    require(receipt['schema'] == 1 and receipt['source_apk_sha256'] ==
            digest((ROOT / 'evidence/theme/apks/com.android.contacts.apk').read_bytes()),
            'Compiled Contacts layouts have a different source APK')
    result = {name: (ROOT / 'build/theme-layouts' / name).read_bytes() for name in LAYOUTS}
    require(receipt['layouts'] == {name: digest(data) for name, data in result.items()},
            'Compiled Contacts layout receipt/hash mismatch')
    for name, data in result.items():
        require(data[:4] == b'\x03\x00\x08\x00', 'Not compiled binary XML: ' + name)
    result['resources.arsc'] = patch_contact_styles(archive.read('resources.arsc'))
    return result


def repack(source, target, changes):
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(target, 'x') as output:
        names = original.namelist()
        require(len(names) == len(set(names)), 'Duplicate ZIP entries')
        require(set(changes) <= set(names), 'Replacement entry missing from original APK')
        output.comment = original.comment
        for info in original.infolist():
            if not signature_entry(info.filename):
                output.writestr(info, changes.get(info.filename, original.read(info.filename)))
    verify_archive(source, target, changes)


def verify_archive(source, target, changes):
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(target) as output:
        expected = {name for name in original.namelist() if not signature_entry(name)}
        actual = [name for name in output.namelist() if not signature_entry(name)]
        require(len(actual) == len(set(actual)) and set(actual) == expected,
                'APK entries changed outside the patch')
        for name in expected:
            require(output.read(name) == changes.get(name, original.read(name)),
                    'Unexpected APK content change: ' + name)
            require(output.getinfo(name).compress_type == original.getinfo(name).compress_type,
                    'APK compression method changed: ' + name)


class Toolchain:
    def __init__(self):
        paths = json.loads((ROOT / '.toolchain/paths.json').read_text())
        self.bin = Path(paths['build_tools'])
        self.env = dict(os.environ, JAVA_HOME=paths['java_home'])
        self.env['PATH'] = str(Path(paths['java_home']) / 'bin') + os.pathsep + self.env['PATH']

    def run(self, *args):
        return subprocess.check_output([str(arg) for arg in args], env=self.env,
                                       stderr=subprocess.PIPE, timeout=180).decode().strip()

    def metadata(self, apk, package):
        signature = self.run(self.bin / 'apksigner', 'verify', '--print-certs', apk)
        signers = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)$',
                             signature, re.M)
        require([value.lower() for value in signers] == [CERTS[PACKAGES[package]]],
                'APK signer mismatch: ' + package)
        badging = self.run(self.bin / 'aapt2', 'dump', 'badging', apk)
        match = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'(.*)$",
                          badging, re.M)
        require(match and match[1] == package, 'APK package mismatch')
        major = re.search(r"versionCodeMajor='([^']+)'", match[4])
        return {'package': package, 'version_code': match[2], 'version_name': match[3],
                'version_code_major': major[1] if major else '0',
                'signer_sha256': signers[0].lower(), 'sha256': digest(Path(apk).read_bytes())}

    def signing_files(self, package):
        identity = PACKAGES[package]
        directory = ROOT / '.local/aosp-signing'
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        directory.chmod(0o700)
        cert = ROOT / 'evidence/theme/aosp-certs' / (identity + '.x509.pem')
        key = directory / (identity + '.pk8')
        source = ('https://android.googlesource.com/platform/build/+/refs/heads/main/'
                  'target/product/security/' + identity)
        if not cert.exists():
            with urllib.request.urlopen(source + '.x509.pem?format=TEXT', timeout=30) as response:
                certificate = base64.b64decode(response.read(), validate=True)
            der = subprocess.check_output(['openssl', 'x509', '-outform', 'DER'], input=certificate)
            require(digest(der) == CERTS[identity], 'Downloaded AOSP certificate hash mismatch')
            cert.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            immutable_write(cert, certificate)
        der = subprocess.check_output(['openssl', 'x509', '-in', str(cert), '-outform', 'DER'])
        require(digest(der) == CERTS[identity], 'Public AOSP certificate hash mismatch')
        if not key.exists():
            url = source + '.pk8?format=TEXT'
            with urllib.request.urlopen(url, timeout=30) as response:
                immutable_write(key, base64.b64decode(response.read(), validate=True))
        key.chmod(0o600)
        cert_public = subprocess.check_output(['openssl', 'x509', '-in', str(cert), '-pubkey', '-noout'])
        key_public = subprocess.check_output(['openssl', 'pkey', '-inform', 'DER', '-in', str(key), '-pubout'])
        require(cert_public == key_public, 'Public AOSP key does not match certificate')
        return key, cert

    def session_helper(self):
        paths = json.loads((ROOT / '.toolchain/paths.json').read_text())
        output = ROOT / 'build/install-session-status'
        output.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=output) as temporary:
            temporary = Path(temporary)
            classes, dex = temporary / 'classes', temporary / 'dex'
            classes.mkdir()
            dex.mkdir()
            self.run(Path(paths['java_home']) / 'bin/javac', '-source', '8', '-target', '8',
                     '-bootclasspath', paths['android_jar'], '-d', classes,
                     ROOT / 'tools/InstallSessionStatus.java')
            self.run(self.bin / 'd8', '--lib', paths['android_jar'], '--min-api', '36',
                     '--output', dex, classes / 'InstallSessionStatus.class')
            jar = temporary / 'status.jar'
            with zipfile.ZipFile(jar, 'w', compression=zipfile.ZIP_STORED) as archive:
                archive.write(dex / 'classes.dex', 'classes.dex')
            data = jar.read_bytes()
            target = output / (digest(data) + '.jar')
            if not target.exists():
                immutable_write(target, data)
            require(target.read_bytes() == data, 'Session status helper hash mismatch')
        return target


def build(package, directory):
    directory = ignored_path(directory, 'build')
    directory.mkdir(parents=True, exist_ok=False, mode=0o700)
    source = ROOT / 'evidence/theme/apks' / (package + '.apk')
    tool = Toolchain()
    before = tool.metadata(source, package)
    key, cert = tool.signing_files(package)
    with zipfile.ZipFile(source) as archive:
        changes = replacements(package, archive)
    with tempfile.TemporaryDirectory(dir=directory) as temporary:
        unsigned, aligned = Path(temporary) / 'unsigned.apk', Path(temporary) / 'aligned.apk'
        repack(source, unsigned, changes)
        tool.run(tool.bin / 'zipalign', '-P', '16', '4', unsigned, aligned)
        target = directory / 'themed.apk'
        tool.run(tool.bin / 'apksigner', 'sign', '--key', key, '--cert', cert,
                 '--v4-signing-enabled', 'false', '--out', target, aligned)
    tool.run(tool.bin / 'zipalign', '-c', '-P', '16', '4', target)
    after = tool.metadata(target, package)
    require({k: v for k, v in before.items() if k != 'sha256'} ==
            {k: v for k, v in after.items() if k != 'sha256'}, 'APK identity/version changed')
    verify_archive(source, target, changes)
    target.chmod(0o400)
    write_receipt(directory / 'build.json', {'schema': 1, 'original': before, 'themed': after,
                  'changes': {name: digest(data) for name, data in changes.items()}})
    print('Built and verified ' + package + ' in ' + str(directory))


class Device:
    def __init__(self, mutation=False):
        self.serial = select_r1(mutation=mutation)

    def adb(self, *args):
        try:
            return subprocess.check_output(['adb', '-s', self.serial, *map(str, args)],
                                           stderr=subprocess.PIPE, timeout=180).decode().strip()
        except subprocess.CalledProcessError as failure:
            detail = (failure.output or b'') + (failure.stderr or b'')
            raise RuntimeError('ADB operation rejected: ' + detail.decode(errors='replace').strip()) from failure

    def installed(self, package, target, tool):
        paths = self.adb('shell', shlex.join(['pm', 'path', package])).splitlines()
        require(len(paths) == 1 and paths[0].startswith('package:/'),
                'Expected one installed base APK; split packages are unsupported')
        remote = paths[0][8:]
        self.adb('pull', remote, target)
        metadata = tool.metadata(target, package)
        current = self.adb('shell', shlex.join(['pm', 'path', package])).splitlines()
        require(current == paths, 'Installed package changed while reading it')
        remote_hash = self.adb('shell', shlex.join(['sha256sum', remote])).split()[0]
        require(remote_hash == metadata['sha256'], 'Installed APK changed during capture')
        return metadata

    def shell(self, *args):
        return self.adb('shell', shlex.join(map(str, args)))

    def stage_helper(self, tool):
        require(self.shell('id', '-u') == '0', 'Tracked install status requires root ADB')
        helper = tool.session_helper()
        sha = digest(helper.read_bytes())
        remote = '/data/local/tmp/rabbit-install-status-' + sha + '.jar'
        self.adb('push', helper, remote)
        require(self.shell('sha256sum', remote).split()[0] == sha, 'Remote helper hash mismatch')
        self.shell('chmod', '600', remote)
        return remote

    def session_absent(self, session, package, helper):
        value = self.shell('env', 'CLASSPATH=' + helper, 'app_process', '/system/bin',
                           'InstallSessionStatus', session, package)
        require(value in ('ABSENT ' + str(session), 'PRESENT ' + str(session)),
                'Unrecognized authoritative session status')
        return value == 'ABSENT ' + str(session)

    def await_session_absent(self, session, package, helper):
        for attempt in range(20):
            if self.session_absent(session, package, helper):
                return
            if attempt < 19:
                time.sleep(.25)
        raise RuntimeError('Install session is still present; receipt remains unresolved. Run settle later.')

    def boot_id(self):
        value = self.shell('cat', '/proc/sys/kernel/random/boot_id')
        require(re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', value),
                'Invalid boot ID; cannot prove a real reboot')
        return value

    def staged_status(self, session, package, helper):
        require(package == STAGED_PACKAGE, 'Staged installs are restricted to SystemUI')
        result = json.loads(self.shell('env', 'CLASSPATH=' + helper, 'app_process', '/system/bin',
                                       'InstallSessionStatus', session, package, 'staged'))
        require(result.get('session_id') == session and result.get('state') in
                ('ABSENT', 'PENDING', 'READY', 'FAILED', 'APPLIED'), 'Invalid staged session status')
        return result


def require_metadata(actual, expected, context):
    require(actual == expected, context + ': exact APK hash/package/version/signer mismatch. '
            'An unrelated update or Package Manager artifact rewrite needs independent review; '
            'no hash guard was relaxed.')


@contextlib.contextmanager
def transaction_lock(directory):
    lock = directory / '.lock'
    lock.mkdir(mode=0o700)  # A crash leaves the lock for explicit operator review.
    try:
        yield
    finally:
        lock.rmdir()


@contextlib.contextmanager
def package_lock(serial, package):
    # Separate transactions for the same device/package must not race each other.
    directory = ROOT / 'evidence/.theme-app-locks'
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    path = directory / digest((serial + '\0' + package).encode())
    with path.open('a') as stream:
        path.chmod(0o600)
        fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield


def unresolved_intents(serial, package):
    result = []
    for path in (ROOT / 'evidence').rglob('*-intent.json'):
        intent = json.loads(path.read_text())
        if intent.get('device_serial') == serial and intent.get('package') == package:
            terminal = path.with_name(path.name.replace('-intent.json', '-terminal.json'))
            if not terminal.exists():
                result.append(path)
            else:
                # A crash may leave an incomplete exclusively-created file. Its
                # mere presence is never completion evidence.
                data = json.loads(terminal.read_text())
                session = json.loads(path.with_name(path.name.replace('-intent.json', '-session.json')).read_text())
                valid = (data.get('device_serial') == serial and
                         data.get('session_id') == session.get('session_id'))
                if intent.get('staged'):
                    if data.get('session_status') == 'applied':
                        ready = json.loads(path.with_name(path.name.replace('-intent.json', '-ready.json')).read_text())
                        valid = valid and (data.get('status') == 'staged_applied'
                            and data.get('installed') == intent.get('expected_after')
                            and ready.get('device_serial') == serial
                            and ready.get('session_id') == data.get('session_id')
                            and ready.get('boot_id') == intent.get('boot_id')
                            and bool(data.get('boot_id')) and data.get('boot_id') != intent.get('boot_id'))
                    else:
                        valid = valid and (data.get('session_status') == 'failed'
                            and data.get('status') == 'staged_failed'
                            and data.get('installed') == intent.get('before'))
                else:
                    valid = valid and (data.get('session_status') == 'absent'
                        and data.get('status') in ('verified', 'settled')
                        and data.get('installed') in (intent.get('before'), intent.get('expected_after')))
                require(valid, 'Invalid terminal session receipt; manual review required')
    return sorted(result)


def require_no_pending(serial, package):
    require(not unresolved_intents(serial, package),
            'Unresolved install intent for this device/package; run settle on its transaction '
            'before update, restore, prepare, or any idempotent return.')


def capture(package):
    directory = ROOT / 'evidence/theme/apks'
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    target = directory / (package + '.apk')
    receipt = directory / (package + '-capture.json')
    require(not target.exists() and not receipt.exists(), 'Capture already exists; never overwrite source APKs')
    tool, device = Toolchain(), Device()
    with package_lock(device.serial, package):
        require_no_pending(device.serial, package)
        with tempfile.TemporaryDirectory(dir=directory) as temporary:
            current = Path(temporary) / 'current.apk'
            metadata = device.installed(package, current, tool)
            immutable_write(target, current.read_bytes())
        write_receipt(receipt, {'schema': 1, 'device_serial': device.serial, 'original': metadata})
    print('Captured and verified immutable source APK for ' + package)


def prepare(package, directory, build_directory):
    directory = ignored_path(directory, 'evidence')
    built = ignored_path(build_directory, 'build')
    plan = json.loads((built / 'build.json').read_text())
    require(plan['schema'] == 1 and plan['original']['package'] == package, 'Wrong build receipt')
    tool, device = Toolchain(), Device()
    with package_lock(device.serial, package):
        require_no_pending(device.serial, package)
        directory.mkdir(parents=True, exist_ok=False, mode=0o700)
        # Incomplete preparation deliberately cannot be reused as a valid transaction.
        with tempfile.TemporaryDirectory(dir=directory) as temporary:
            current = Path(temporary) / 'current.apk'
            original = device.installed(package, current, tool)
            require_metadata(original, plan['original'], 'Installed source differs from reviewed build')
            themed = tool.metadata(built / 'themed.apk', package)
            require_metadata(themed, plan['themed'], 'Local themed APK')
            immutable_write(directory / 'original.apk', current.read_bytes())
            immutable_write(directory / 'themed.apk', (built / 'themed.apk').read_bytes())
        write_receipt(directory / 'prepared.json', dict(plan, device_serial=device.serial))
    print('Prepared immutable transaction for ' + package + ' at ' + str(directory))


def deploy(action, package, directory):
    directory = ignored_path(directory, 'evidence')
    tool, device = Toolchain(), Device(mutation=True)
    with package_lock(device.serial, package), transaction_lock(directory):
        receipt = json.loads((directory / 'prepared.json').read_text())
        require(receipt['schema'] == 1 and receipt['original']['package'] == package,
                'Wrong package transaction')
        require_evidence_serial(receipt, device.serial, directory)
        require_no_pending(device.serial, package)
        for kind in ('original', 'themed'):
            require_metadata(tool.metadata(directory / (kind + '.apk'), package), receipt[kind],
                             'Saved ' + kind + ' APK')
        before_kind, after_kind = ('original', 'themed') if action == 'update' else ('themed', 'original')
        with tempfile.TemporaryDirectory(dir=directory) as temporary:
            current = Path(temporary) / 'current.apk'
            installed = device.installed(package, current, tool)
            if installed == receipt[after_kind]:
                print(package + ' already matches the exact ' + after_kind + ' APK')
                return
            require_metadata(installed, receipt[before_kind], 'Installed preflight APK')
            helper = device.stage_helper(tool)
            event = uuid.uuid4().hex
            staged = package == STAGED_PACKAGE
            intent = {'device_serial': device.serial, 'action': action, 'package': package,
                      'before': installed, 'expected_after': receipt[after_kind], 'staged': staged}
            if staged:
                intent['boot_id'] = device.boot_id()
            write_receipt(directory / (event + '-intent.json'), intent)
            # Keep every uncertain operation durable, including a create whose ID
            # never returned. Never infer completion from an unchanged APK hash.
            apk = directory / (after_kind + '.apk')
            flags = ['--staged'] if staged else []
            result = device.shell('pm', 'install-create', '-r', *flags, '--user', '0', '--pkg', package,
                                  '-S', apk.stat().st_size)
            match = re.fullmatch(r'Success: created install session \[(\d+)\]', result)
            require(match and int(match[1]) > 0, 'No authoritative created session ID; intent stays unresolved')
            session = int(match[1])
            write_receipt(directory / (event + '-session.json'), {
                'device_serial': device.serial, 'package': package, 'session_id': session})
            # A positive read proves the status interface can see this exact session
            # before a future ABSENT result can be used for reconciliation.
            if staged:
                require(device.staged_status(session, package, helper)['state'] == 'PENDING',
                        'New staged install session is not visible in its initial state')
            else:
                require(not device.session_absent(session, package, helper), 'New install session not visible')
            write_receipt(directory / (event + '-visible.json'), {'session_id': session})
            remote = '/data/local/tmp/rabbit-theme-' + event + '.apk'
            device.adb('push', apk, remote)
            require(device.shell('sha256sum', remote).split()[0] == receipt[after_kind]['sha256'],
                    'Staged APK hash mismatch; install session remains unresolved')
            result = device.shell('pm', 'install-write', '-S', apk.stat().st_size,
                                  session, 'base.apk', remote)
            require(result.startswith('Success'), 'Install-write failed; session remains unresolved')
            write_receipt(directory / (event + '-commit.json'), {'session_id': session})
            flags = ['--staged-ready-timeout', '60000'] if staged else []
            result = device.shell('pm', 'install-commit', *flags, session)
            require(result in ('Success', 'Success. Reboot device to apply staged session'),
                    'Install-commit did not confirm success; run settle: ' + result)
            if staged:
                reconcile_staged(device, tool, package, directory, event, intent, session, helper)
                device.shell('rm', '-f', remote)
                return
            device.await_session_absent(session, package, helper)
            after = device.installed(package, current, tool)
            write_receipt(directory / (event + '-observed.json'), {
                'device_serial': device.serial, 'action': action, 'installed': after})
            require_metadata(after, receipt[after_kind], 'Installed postflight APK')
            write_receipt(directory / (event + '-terminal.json'), {
                'device_serial': device.serial, 'session_id': session, 'installed': after,
                'status': 'verified', 'session_status': 'absent'})
            device.shell('rm', '-f', remote)
        print('Verified ' + action + ' for ' + package + '; replacement session preserves app data')


def reconcile_staged(device, tool, package, directory, event, intent, session, helper,
                     require_applied=False):
    """READY only journals preparation; APPLIED additionally needs a changed boot ID."""
    require(package == STAGED_PACKAGE and intent.get('staged'), 'Wrong staged transaction')
    status = device.staged_status(session, package, helper)
    state = status['state']
    require(state not in ('ABSENT', 'PENDING'),
            'Staged session is ' + state + '; cannot infer readiness or application from APK hashes')
    boot = device.boot_id()
    ready_path = directory / (event + '-ready.json')
    if state == 'READY':
        require(not require_applied, 'Staged session is READY, not APPLIED; a real reboot is required')
        require(boot == intent['boot_id'], 'READY observed after boot changed; manual review required')
        with tempfile.TemporaryDirectory(dir=directory) as temporary:
            installed = device.installed(package, Path(temporary) / 'current.apk', tool)
        require_metadata(installed, intent['before'], 'APK must remain at its preinstall state before staged reboot')
        ready = {'device_serial': device.serial, 'session_id': session, 'boot_id': boot,
                 'expected_after': intent['expected_after'], 'session_status': 'ready'}
        if ready_path.exists():
            require(json.loads(ready_path.read_text()) == ready, 'Staged READY receipt changed')
        else:
            write_receipt(ready_path, ready)
        print('SystemUI staged session is READY. Reboot explicitly, then run finalize; not yet installed.')
        return
    if state == 'APPLIED':
        require(ready_path.exists(), 'No durable pre-reboot READY receipt; manual review required')
        ready = json.loads(ready_path.read_text())
        require(ready.get('device_serial') == device.serial and ready.get('session_id') == session
                and ready.get('boot_id') == intent['boot_id'] and boot != intent['boot_id']
                and ready.get('session_status') == 'ready'
                and ready.get('expected_after') == intent['expected_after'],
                'A real reboot after the recorded READY state is required')
        expected = intent['expected_after']
    else:
        require(state == 'FAILED', 'Unknown staged state')
        expected = intent['before']
    with tempfile.TemporaryDirectory(dir=directory) as temporary:
        installed = device.installed(package, Path(temporary) / 'current.apk', tool)
    require_metadata(installed, expected, 'Staged ' + state + ' installed APK')
    write_receipt(directory / (event + '-terminal.json'), {
        'device_serial': device.serial, 'session_id': session, 'boot_id': boot, 'installed': installed,
        'status': 'staged_' + state.lower(), 'session_status': state.lower(), 'staged_status': status})
    if state == 'FAILED':
        raise RuntimeError('Android rejected staged SystemUI install: ' + json.dumps(status) +
                           '; exact preinstall APK remains installed')
    print('SystemUI staged session APPLIED after reboot; exact APK/signature/version verified')


def settle(package, directory, finalize=False):
    directory = ignored_path(directory, 'evidence')
    tool, device = Toolchain(), Device(mutation=True)
    with package_lock(device.serial, package), transaction_lock(directory):
        receipt = json.loads((directory / 'prepared.json').read_text())
        require_evidence_serial(receipt, device.serial, directory)
        require(receipt['schema'] == 1 and receipt['original']['package'] == package,
                'Wrong package transaction')
        pending = unresolved_intents(device.serial, package)
        require(len(pending) == 1 and pending[0].parent == directory,
                'Expected exactly one unresolved intent in this transaction')
        intent_path = pending[0]
        event = intent_path.name.removesuffix('-intent.json')
        intent = json.loads(intent_path.read_text())
        session_path = directory / (event + '-session.json')
        require(session_path.exists() and (directory / (event + '-visible.json')).exists(),
                'No recorded and positively observed session; manual review required, no hash-based settlement')
        session_data = json.loads(session_path.read_text())
        require_evidence_serial(session_data, device.serial, session_path)
        require(session_data['package'] == package, 'Session package mismatch')
        session = session_data['session_id']
        require(isinstance(session, int) and session > 0, 'Invalid recorded session ID')
        helper = device.stage_helper(tool)
        for kind in ('original', 'themed'):
            require_metadata(tool.metadata(directory / (kind + '.apk'), package), receipt[kind],
                             'Saved ' + kind + ' APK')
        if intent.get('staged'):
            reconcile_staged(device, tool, package, directory, event, intent, session, helper,
                             require_applied=finalize)
            return
        require(not finalize, 'Finalize is only for a staged SystemUI transaction')
        require(device.session_absent(session, package, helper),
                'Install session is still present; cannot settle or restore while it may commit')
        with tempfile.TemporaryDirectory(dir=directory) as temporary:
            installed = device.installed(package, Path(temporary) / 'current.apk', tool)
        require(installed in (intent['before'], intent['expected_after'])
                and installed in (receipt['original'], receipt['themed']),
                'Finished session has an unrelated or rewritten APK; manual review required')
        write_receipt(directory / (event + '-terminal.json'), {
            'device_serial': device.serial, 'session_id': session, 'installed': installed,
            'status': 'settled', 'session_status': 'absent'})
        print('Session is absent and exact installed APK reconciled; update/restore may now run')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('capture', 'build', 'prepare', 'update', 'restore', 'settle', 'finalize'))
    parser.add_argument('--package', choices=tuple(PACKAGES), required=True)
    parser.add_argument('--build-dir', type=Path, help='New build directory (or prepared build input)')
    parser.add_argument('--transaction', type=Path, help='New/read existing ignored evidence transaction')
    args = parser.parse_args()
    if args.action in ('build', 'prepare') and args.build_dir is None:
        parser.error('--build-dir is required for build/prepare')
    if args.action not in ('build', 'capture') and args.transaction is None:
        parser.error('--transaction is required for prepare/update/restore')
    if args.action == 'capture':
        capture(args.package)
    elif args.action == 'build':
        build(args.package, args.build_dir)
    elif args.action == 'prepare':
        prepare(args.package, args.transaction, args.build_dir)
    elif args.action in ('settle', 'finalize'):
        settle(args.package, args.transaction, finalize=args.action == 'finalize')
    else:
        deploy(args.action, args.package, args.transaction)


if __name__ == '__main__':
    main()
