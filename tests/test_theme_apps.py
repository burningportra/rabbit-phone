"""Host regressions for exact APK mutations and fail-closed transaction guards."""
import contextlib
import base64
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1]
with mock.patch.object(sys, 'path', [str(ROOT / 'scripts'), *sys.path]):
    spec = importlib.util.spec_from_file_location('theme_apps', ROOT / 'scripts/theme_apps.py')
    theme = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(theme)


def dex(strings):
    data = bytearray(112 + 4 * len(strings))
    data[:8] = b'dex\n039\0'
    struct.pack_into('<II', data, 56, len(strings), 112)
    for index, value in enumerate(strings):
        struct.pack_into('<I', data, 112 + 4 * index, len(data))
        data.extend(bytes([len(value)]) + value + b'\0')
    struct.pack_into('<I', data, 32, len(data))
    data[12:32] = hashlib.sha1(data[32:]).digest()
    struct.pack_into('<I', data, 8, zlib.adler32(data[12:]))
    return bytes(data)


class DexTests(unittest.TestCase):
    def test_exact_string_patch_recalculates_both_checksums(self):
        before = dex([b'#%08x', theme.OLD_COLOR, b'#FoldUnfoldTransitionInProgress'])
        after = theme.dex_color_patch(before)
        self.assertEqual(len(after), len(before))
        self.assertEqual(after[12:32], hashlib.sha1(after[32:]).digest())
        self.assertEqual(struct.unpack_from('<I', after, 8)[0], zlib.adler32(after[12:]))
        self.assertEqual(after[32:], before[32:].replace(theme.OLD_COLOR, theme.NEW_COLOR))
        self.assertEqual(after[:8], before[:8])

    def test_replacement_cannot_cross_neighbor_string_id(self):
        before = dex([b'#%08x', theme.OLD_COLOR, b'#800000'])
        with self.assertRaisesRegex(RuntimeError, 'sorted DEX string IDs'):
            theme.dex_color_patch(before)

    def test_missing_duplicate_corrupt_and_non_string_literals_refused(self):
        examples = [dex([b'other']), dex([theme.OLD_COLOR, theme.OLD_COLOR]),
                    dex([b'x' + theme.OLD_COLOR])]
        corrupt = bytearray(dex([theme.OLD_COLOR]))
        corrupt[-1] ^= 1
        examples.append(bytes(corrupt))
        for data in examples:
            with self.subTest(data=data[-20:]), self.assertRaises(RuntimeError):
                theme.dex_color_patch(data)

    def test_literal_in_multiple_dex_files_refused(self):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as archive:
            archive.writestr('classes.dex', dex([theme.OLD_COLOR]))
            archive.writestr('classes2.dex', dex([theme.OLD_COLOR]))
        with zipfile.ZipFile(stream) as archive, self.assertRaisesRegex(RuntimeError, 'across all DEX'):
            theme.replacements('com.android.systemui', archive)


class ArchiveTests(unittest.TestCase):
    def test_repack_preserves_all_other_entries_compression_and_font_manifest(self):
        with tempfile.TemporaryDirectory() as temporary:
            original, target = Path(temporary) / 'original.apk', Path(temporary) / 'themed.apk'
            entries = {'AndroidManifest.xml': b'package', 'resources.arsc': b'resources',
                       theme.FONT_ENTRY: b'original-font',
                       'assets/flutter_assets/FontManifest.json': b'unchanged-family-and-key',
                       'META-INF/services/keep': b'service', 'META-INF/KEEP.kotlin_module': b'keep',
                       'META-INF/CERT.RSA': b'old-cert', 'META-INF/CERT.SF': b'old-signature',
                       'META-INF/MANIFEST.MF': b'old-manifest'}
            with zipfile.ZipFile(original, 'w') as archive:
                archive.comment = b'comment'
                for i, (name, data) in enumerate(entries.items()):
                    archive.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED if i % 2 else zipfile.ZIP_STORED)
            theme.repack(original, target, {theme.FONT_ENTRY: b'new-font'})
            with zipfile.ZipFile(original) as source, zipfile.ZipFile(target) as output:
                self.assertEqual(output.comment, b'comment')
                self.assertEqual(output.read(theme.FONT_ENTRY), b'new-font')
                for name, data in entries.items():
                    if theme.signature_entry(name):
                        self.assertNotIn(name, output.namelist())
                    else:
                        self.assertEqual(output.getinfo(name).compress_type, source.getinfo(name).compress_type)
                        if name != theme.FONT_ENTRY:
                            self.assertEqual(output.read(name), data)

    def test_duplicate_entries_refused(self):
        import warnings
        with tempfile.TemporaryDirectory() as temporary:
            source, target = Path(temporary) / 'source', Path(temporary) / 'target'
            with warnings.catch_warnings():
                warnings.simplefilter('ignore', UserWarning)
                with zipfile.ZipFile(source, 'w') as archive:
                    archive.writestr('entry', b'a')
                    archive.writestr('entry', b'b')
            with self.assertRaisesRegex(RuntimeError, 'Duplicate'):
                theme.repack(source, target, {'entry': b'c'})

    def test_font_recipe_rejects_unverified_original_or_private_font(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            font = root / 'evidence/theme/stock-fonts/PowerGroteskRegular.otf'
            font.parent.mkdir(parents=True)
            font.write_bytes(b'wrong-private-font')
            archive = mock.Mock()
            archive.read.return_value = b'original-font'
            with mock.patch.object(theme, 'ROOT', root):
                with self.assertRaisesRegex(RuntimeError, 'Original text font'):
                    theme.replacements('com.techyminati.pages', archive)
                recipes = {'com.techyminati.pages': (theme.FONT_ENTRY, theme.digest(b'original-font'))}
                with mock.patch.object(theme, 'FONT_RECIPES', recipes):
                    with self.assertRaisesRegex(RuntimeError, 'Private stock font hash'):
                        theme.replacements('com.techyminati.pages', archive)
                    with mock.patch.object(theme, 'FONT_SHA', theme.digest(font.read_bytes())):
                        self.assertEqual(theme.replacements('com.techyminati.pages', archive),
                                         {theme.FONT_ENTRY: b'wrong-private-font'})


class GuardTests(unittest.TestCase):
    def test_missing_public_certificate_is_pinned_before_saving(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            key = root / '.local/aosp-signing/platform.pk8'
            key.parent.mkdir(parents=True)
            key.write_bytes(b'test-key')
            tool = theme.Toolchain.__new__(theme.Toolchain)
            response = mock.MagicMock()
            response.__enter__.return_value.read.return_value = base64.b64encode(b'certificate')
            def openssl(args, **kwargs):
                return b'der' if '-outform' in args else b'public-key'
            with mock.patch.object(theme, 'ROOT', root), \
                    mock.patch.object(theme.urllib.request, 'urlopen', return_value=response) as download, \
                    mock.patch.object(theme.subprocess, 'check_output', side_effect=openssl):
                with self.assertRaisesRegex(RuntimeError, 'Downloaded AOSP certificate hash'):
                    tool.signing_files('com.techyminati.pages')
                cert = root / 'evidence/theme/aosp-certs/platform.x509.pem'
                self.assertFalse(cert.exists())
                with mock.patch.dict(theme.CERTS, platform=theme.digest(b'der')):
                    actual_key, actual_cert = tool.signing_files('com.techyminati.pages')
                self.assertEqual(actual_key, key)
                self.assertEqual(actual_cert.read_bytes(), b'certificate')
                self.assertEqual(key.stat().st_mode & 0o777, 0o600)
                self.assertTrue(download.call_args.args[0].startswith('https://android.googlesource.com/'))

    def test_signer_mismatch_and_multiple_signers_refused_before_manifest_read(self):
        tool = theme.Toolchain.__new__(theme.Toolchain)
        tool.bin = Path('/unused')
        cert = theme.CERTS['platform']
        for output in ('Signer #1 certificate SHA-256 digest: ' + '0' * 64,
                       'Signer #1 certificate SHA-256 digest: ' + cert + '\n'
                       'Signer #2 certificate SHA-256 digest: ' + cert):
            tool.run = mock.Mock(return_value=output)
            with self.assertRaisesRegex(RuntimeError, 'signer mismatch'):
                tool.metadata(Path('/unused.apk'), 'com.android.systemui')
            self.assertEqual(tool.run.call_count, 1)

    def test_immutable_backup_cannot_be_replaced(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / 'original.apk'
            theme.immutable_write(target, b'original')
            with self.assertRaises(FileExistsError):
                theme.immutable_write(target, b'replacement')
            self.assertEqual(target.read_bytes(), b'original')
            self.assertEqual(target.stat().st_mode & 0o777, 0o400)

    def test_all_metadata_fields_are_guarded(self):
        expected = {'package': 'pkg', 'version_code': '1', 'version_name': '1',
                    'version_code_major': '0', 'signer_sha256': 'cert', 'sha256': 'apk'}
        for field in expected:
            with self.subTest(field=field), self.assertRaisesRegex(RuntimeError, 'exact APK'):
                theme.require_metadata(dict(expected, **{field: 'changed'}), expected, 'test')


class TransactionTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.directory = self.root / 'evidence/transaction'
        self.directory.mkdir(parents=True)
        self.package = 'com.techyminati.pages'
        identity = {'package': self.package, 'version_code': '1', 'version_name': '1',
                    'version_code_major': '0', 'signer_sha256': theme.CERTS['platform']}
        self.original = dict(identity, sha256=theme.digest(b'original'))
        self.themed = dict(identity, sha256=theme.digest(b'themed'))
        self.receipt = {'schema': 1, 'device_serial': 'test-r1',
                        'original': self.original, 'themed': self.themed}
        theme.write_receipt(self.directory / 'prepared.json', self.receipt)
        for kind in ('original', 'themed'):
            theme.immutable_write(self.directory / (kind + '.apk'), kind.encode())
        self.device = mock.Mock(serial='test-r1')
        self.device.stage_helper.return_value = '/remote/helper.jar'
        self.device.session_absent.return_value = False
        self.remote_sha = ''
        self.device.adb.side_effect = self.adb
        self.device.shell.side_effect = self.shell
        self.tool = mock.Mock()
        self.tool.metadata.side_effect = lambda path, package: dict(
            identity, sha256=theme.digest(Path(path).read_bytes()))
        for patcher in (mock.patch.object(theme, 'ROOT', self.root),
                        mock.patch.object(theme, 'Device', return_value=self.device),
                        mock.patch.object(theme, 'Toolchain', return_value=self.tool)):
            patcher.start()
            self.addCleanup(patcher.stop)

    def adb(self, *args):
        if args[0] == 'push':
            self.remote_sha = theme.digest(Path(args[1]).read_bytes())
        return ''

    def shell(self, *args):
        if args[:2] == ('pm', 'install-create'):
            return 'Success: created install session [123]'
        if args[0] == 'sha256sum':
            return self.remote_sha + '  remote.apk'
        if args[:2] in (('pm', 'install-write'), ('pm', 'install-commit')):
            return 'Success'
        if args[:2] == ('rm', '-f'):
            return ''
        raise AssertionError('Unexpected device shell call: ' + repr(args))

    def deploy(self, action):
        with contextlib.redirect_stdout(io.StringIO()):
            theme.deploy(action, self.package, self.directory)

    def test_update_and_restore_use_tracked_replacement_sessions_with_exact_backups(self):
        before_bytes = (self.directory / 'prepared.json').read_bytes()
        for action, before, after, kind in [('update', self.original, self.themed, 'themed'),
                                            ('restore', self.themed, self.original, 'original')]:
            self.device.installed.side_effect = [before, after]
            self.deploy(action)
            push = self.device.adb.call_args_list[-1].args
            self.assertEqual(push[:2], ('push', self.directory / (kind + '.apk')))
            self.device.shell.assert_any_call('pm', 'install-create', '-r', '--user', '0',
                '--pkg', self.package, '-S', len(kind))
            self.device.shell.assert_any_call('pm', 'install-commit', 123)
        self.assertEqual(self.device.adb.call_count, 2)
        self.assertEqual((self.directory / 'prepared.json').read_bytes(), before_bytes)
        self.assertEqual(len(list(self.directory.glob('*-intent.json'))), 2)
        self.assertEqual(len(list(self.directory.glob('*-observed.json'))), 2)
        self.assertEqual(len(list(self.directory.glob('*-terminal.json'))), 2)
        self.assertEqual(self.device.await_session_absent.call_count, 2)

    def test_unrelated_update_blocks_restore_before_mutation(self):
        self.device.installed.return_value = dict(self.themed, sha256='unrelated')
        with self.assertRaisesRegex(RuntimeError, 'Installed preflight'):
            self.deploy('restore')
        self.device.adb.assert_not_called()

    def test_wrong_device_blocks_before_mutation(self):
        self.device.serial = 'other-device'
        with self.assertRaisesRegex(RuntimeError, 'different device'):
            self.deploy('update')
        self.device.adb.assert_not_called()
        self.device.installed.assert_not_called()

    def test_tampered_backup_blocks_before_mutation(self):
        original = self.directory / 'original.apk'
        original.chmod(0o600)
        original.write_bytes(b'changed')
        with self.assertRaisesRegex(RuntimeError, 'Saved original'):
            self.deploy('restore')
        self.device.adb.assert_not_called()

    def test_postinstall_representation_change_fails_without_weakening_receipt(self):
        self.device.installed.side_effect = [self.original, dict(self.themed, sha256='rewritten')]
        with self.assertRaisesRegex(RuntimeError, 'artifact rewrite'):
            self.deploy('update')
        self.assertEqual(json.loads((self.directory / 'prepared.json').read_text()), self.receipt)
        self.assertEqual(len(list(self.directory.glob('*-observed.json'))), 1)

    def test_already_restored_is_idempotent_without_install(self):
        self.device.installed.return_value = self.original
        self.deploy('restore')
        self.device.adb.assert_not_called()

    def test_prepare_refuses_existing_transaction_without_overwriting_backup(self):
        built = self.root / 'build/local'
        built.mkdir(parents=True)
        (built / 'build.json').write_text(json.dumps(self.receipt))
        with self.assertRaises(FileExistsError):
            theme.prepare(self.package, self.directory, built)
        self.assertEqual((self.directory / 'original.apk').read_bytes(), b'original')

    def test_readonly_capture_is_device_bound_and_cannot_overwrite_source(self):
        def installed(package, path, tool):
            path.write_bytes(b'original')
            return self.original
        self.device.installed.side_effect = installed
        with contextlib.redirect_stdout(io.StringIO()):
            theme.capture(self.package)
        source = self.root / 'evidence/theme/apks' / (self.package + '.apk')
        self.assertEqual(source.read_bytes(), b'original')
        receipt = json.loads(source.with_name(self.package + '-capture.json').read_text())
        self.assertEqual(receipt['device_serial'], 'test-r1')
        self.assertEqual(receipt['original'], self.original)
        self.device.adb.assert_not_called()
        self.device.shell.assert_not_called()
        with self.assertRaisesRegex(RuntimeError, 'never overwrite'):
            theme.capture(self.package)

    def timeout_commit(self):
        self.device.installed.side_effect = None
        self.device.installed.return_value = self.original
        def shell(*args):
            if args[:2] == ('pm', 'install-commit'):
                raise subprocess.TimeoutExpired('pm install-commit', 180)
            return self.shell(*args)
        self.device.shell.side_effect = shell
        with self.assertRaises(subprocess.TimeoutExpired):
            self.deploy('update')
        self.device.shell.side_effect = self.shell

    def test_timed_out_commit_blocks_false_restore_success_while_original_is_visible(self):
        self.timeout_commit()
        self.device.installed.reset_mock()
        with self.assertRaisesRegex(RuntimeError, 'Unresolved install intent'):
            self.deploy('restore')
        self.device.installed.assert_not_called()
        self.assertEqual(len(list(self.directory.glob('*-terminal.json'))), 0)

    def test_settle_refuses_present_session_even_with_original_hash(self):
        self.timeout_commit()
        self.device.installed.reset_mock()
        with self.assertRaisesRegex(RuntimeError, 'still present'):
            theme.settle(self.package, self.directory)
        self.device.installed.assert_not_called()

    def test_eventual_commit_then_authoritative_settlement_allows_safe_restore(self):
        self.timeout_commit()
        self.device.session_absent.return_value = True
        self.device.installed.return_value = self.themed
        with contextlib.redirect_stdout(io.StringIO()):
            theme.settle(self.package, self.directory)
        self.assertFalse(theme.unresolved_intents('test-r1', self.package))
        self.device.session_absent.return_value = False
        self.device.installed.side_effect = [self.themed, self.original]
        self.deploy('restore')
        self.assertEqual(len(list(self.directory.glob('*-terminal.json'))), 2)

    def test_pending_intent_in_another_transaction_also_blocks_noop_restore(self):
        other = self.root / 'evidence/other'
        other.mkdir()
        theme.write_receipt(other / 'other-intent.json', {'device_serial': 'test-r1',
                                                       'package': self.package})
        self.device.installed.return_value = self.original
        with self.assertRaisesRegex(RuntimeError, 'Unresolved install intent'):
            self.deploy('restore')
        self.device.adb.assert_not_called()

    def test_create_timeout_without_recorded_id_cannot_settle_from_hash(self):
        self.device.installed.return_value = self.original
        self.device.shell.side_effect = subprocess.TimeoutExpired('install-create', 180)
        with self.assertRaises(subprocess.TimeoutExpired):
            self.deploy('update')
        with self.assertRaisesRegex(RuntimeError, 'No recorded and positively observed session'):
            theme.settle(self.package, self.directory)

    def test_partial_terminal_receipt_never_unblocks_false_restore(self):
        self.timeout_commit()
        intent = next(self.directory.glob('*-intent.json'))
        terminal = intent.with_name(intent.name.replace('-intent.json', '-terminal.json'))
        terminal.write_text('')
        with self.assertRaises(json.JSONDecodeError):
            self.deploy('restore')
        terminal.write_text('{}')
        with self.assertRaisesRegex(RuntimeError, 'Invalid terminal'):
            self.deploy('restore')

    def use_systemui(self):
        self.package = theme.STAGED_PACKAGE
        self.original = dict(self.original, package=self.package)
        self.themed = dict(self.themed, package=self.package)
        self.receipt.update(original=self.original, themed=self.themed)
        path = self.directory / 'prepared.json'
        path.chmod(0o600)
        path.write_text(json.dumps(self.receipt))
        self.tool.metadata.side_effect = lambda path, package: dict(
            self.original, sha256=theme.digest(Path(path).read_bytes()))
        self.device.boot_id.return_value = '00000000-0000-0000-0000-000000000001'

    def stage_systemui(self, action='update'):
        before = self.original if action == 'update' else self.themed
        self.device.installed.side_effect = [before, before]
        self.device.staged_status.side_effect = [
            {'session_id': 123, 'state': 'PENDING'}, {'session_id': 123, 'state': 'READY'}]
        self.deploy(action)
        self.device.staged_status.side_effect = None

    def test_systemui_only_stages_ready_and_never_reboots_or_claims_applied(self):
        self.use_systemui()
        self.stage_systemui()
        self.device.shell.assert_any_call('pm', 'install-create', '-r', '--staged', '--user', '0',
                                         '--pkg', self.package, '-S', len(b'themed'))
        self.device.shell.assert_any_call('pm', 'install-commit', '--staged-ready-timeout', '60000', 123)
        self.assertEqual(len(list(self.directory.glob('*-ready.json'))), 1)
        self.assertEqual(len(list(self.directory.glob('*-terminal.json'))), 0)
        self.assertTrue(theme.unresolved_intents('test-r1', self.package))
        self.device.await_session_absent.assert_not_called()
        self.device.session_absent.assert_not_called()
        self.assertFalse(any('reboot' in call.args for call in self.device.shell.call_args_list))

    def test_staged_applied_without_real_reboot_is_refused(self):
        self.use_systemui()
        self.stage_systemui()
        self.device.staged_status.return_value = {'session_id': 123, 'state': 'APPLIED'}
        with self.assertRaisesRegex(RuntimeError, 'real reboot'):
            theme.settle(self.package, self.directory, finalize=True)
        self.assertEqual(len(list(self.directory.glob('*-terminal.json'))), 0)

    def test_staged_finalize_requires_applied_then_exact_hash_after_reboot(self):
        self.use_systemui()
        self.stage_systemui()
        self.device.boot_id.return_value = '00000000-0000-0000-0000-000000000002'
        self.device.staged_status.return_value = {'session_id': 123, 'state': 'APPLIED'}
        self.device.installed.side_effect = None
        self.device.installed.return_value = dict(self.themed, sha256='unrelated')
        with self.assertRaisesRegex(RuntimeError, 'Staged APPLIED installed APK'):
            theme.settle(self.package, self.directory, finalize=True)
        self.assertTrue(theme.unresolved_intents('test-r1', self.package))
        self.device.installed.return_value = self.themed
        with contextlib.redirect_stdout(io.StringIO()):
            theme.settle(self.package, self.directory, finalize=True)
        self.assertFalse(theme.unresolved_intents('test-r1', self.package))
        terminal = json.loads(next(self.directory.glob('*-terminal.json')).read_text())
        self.assertEqual(terminal['status'], 'staged_applied')
        self.stage_systemui(action='restore')
        self.assertEqual(len(list(self.directory.glob('*-ready.json'))), 2)
        self.assertTrue(theme.unresolved_intents('test-r1', self.package))
        self.device.boot_id.return_value = '00000000-0000-0000-0000-000000000003'
        self.device.staged_status.return_value = {'session_id': 123, 'state': 'APPLIED'}
        self.device.installed.side_effect = None
        self.device.installed.return_value = self.original
        with contextlib.redirect_stdout(io.StringIO()):
            theme.settle(self.package, self.directory, finalize=True)
        self.assertFalse(theme.unresolved_intents('test-r1', self.package))
        self.assertEqual(len(list(self.directory.glob('*-terminal.json'))), 2)

    def test_staged_absent_or_pending_is_never_treated_as_ready_or_applied(self):
        self.use_systemui()
        self.stage_systemui()
        for state in ('ABSENT', 'PENDING', 'READY'):
            self.device.staged_status.return_value = {'session_id': 123, 'state': state}
            with self.subTest(state=state), self.assertRaises(RuntimeError):
                theme.settle(self.package, self.directory, finalize=True)
        self.assertEqual(len(list(self.directory.glob('*-terminal.json'))), 0)

    def test_staged_failure_only_settles_with_exact_preinstall_apk_and_reports_reason(self):
        self.use_systemui()
        self.stage_systemui()
        self.device.staged_status.return_value = {'session_id': 123, 'state': 'FAILED',
                                                 'error_code': 1, 'error_message': 'Policy denied'}
        self.device.installed.side_effect = None
        self.device.installed.return_value = self.themed
        with self.assertRaisesRegex(RuntimeError, 'Staged FAILED installed APK'):
            theme.settle(self.package, self.directory)
        self.device.installed.return_value = self.original
        with self.assertRaisesRegex(RuntimeError, 'Policy denied'):
            theme.settle(self.package, self.directory)
        self.assertFalse(theme.unresolved_intents('test-r1', self.package))

    def test_staged_commit_timeout_blocks_restore_then_can_record_ready(self):
        self.use_systemui()
        self.device.staged_status.return_value = {'session_id': 123, 'state': 'PENDING'}
        self.timeout_commit()
        with self.assertRaisesRegex(RuntimeError, 'Unresolved install intent'):
            self.deploy('restore')
        self.device.staged_status.return_value = {'session_id': 123, 'state': 'READY'}
        with contextlib.redirect_stdout(io.StringIO()):
            theme.settle(self.package, self.directory)
        self.assertEqual(len(list(self.directory.glob('*-ready.json'))), 1)
        self.assertEqual(len(list(self.directory.glob('*-terminal.json'))), 0)

    def test_staged_applied_without_ready_receipt_is_refused(self):
        self.use_systemui()
        self.device.staged_status.return_value = {'session_id': 123, 'state': 'PENDING'}
        self.timeout_commit()
        self.device.boot_id.return_value = '00000000-0000-0000-0000-000000000002'
        self.device.staged_status.return_value = {'session_id': 123, 'state': 'APPLIED'}
        with self.assertRaisesRegex(RuntimeError, 'No durable pre-reboot READY'):
            theme.settle(self.package, self.directory, finalize=True)


class DeviceStatusTests(unittest.TestCase):
    def test_staged_parser_is_typed_and_restricted_to_systemui(self):
        device = theme.Device.__new__(theme.Device)
        device.shell = mock.Mock()
        for state in ('ABSENT', 'PENDING', 'READY', 'APPLIED', 'FAILED'):
            device.shell.return_value = json.dumps({'session_id': 123, 'state': state})
            self.assertEqual(device.staged_status(123, theme.STAGED_PACKAGE, '/helper')['state'], state)
        device.shell.return_value = json.dumps({'session_id': 124, 'state': 'READY'})
        with self.assertRaisesRegex(RuntimeError, 'Invalid staged session status'):
            device.staged_status(123, theme.STAGED_PACKAGE, '/helper')
        with self.assertRaisesRegex(RuntimeError, 'restricted to SystemUI'):
            device.staged_status(123, 'com.techyminati.pages', '/helper')

    def test_adb_rejection_keeps_platform_reason_visible(self):
        device = theme.Device.__new__(theme.Device)
        device.serial = 'test-r1'
        failure = subprocess.CalledProcessError(1, ['adb'], output=b'Failure [Installer not allowed]', stderr=b'')
        with mock.patch.object(theme.subprocess, 'check_output', side_effect=failure):
            with self.assertRaisesRegex(RuntimeError, 'Installer not allowed'):
                device.adb('shell', 'pm install-create --staged')


if __name__ == '__main__':
    unittest.main()
