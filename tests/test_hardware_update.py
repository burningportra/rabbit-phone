import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
import install_hardware as helper


def digest(data):
    return hashlib.sha256(data).hexdigest()


class HardwareUpdateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'evidence').mkdir()
        (self.root / 'hardware').mkdir()
        (self.root / 'hardware/rabbit-hardware').write_bytes(b'new helper')
        self.startup = b'unchanged reviewed init'
        self.files = {helper.BIN: b'old helper'}
        self.receipt = self.root / 'evidence/hardware-install.json'
        self.receipt.write_text(json.dumps({
            'device_serial': 'test-r1', 'binary': helper.BIN, 'startup_file': helper.RC,
            'sha256': digest(b'old helper'), 'installed_startup_sha256': digest(self.startup),
            'boot_startup_verified': True,
        }))
        self.calls = []
        self.fail_start = False
        self.addCleanup(patch.stopall)
        for name, value in [('ROOT', self.root), ('SERIAL', None)]:
            patch.object(helper, name, value).start()
        patch.object(helper, 'select_r1', return_value='test-r1').start()
        patch.object(helper, 'stop_helper', side_effect=lambda: self.calls.append(('stop',))).start()
        patch.object(helper.time, 'sleep').start()
        patch.object(helper, 'adb', side_effect=self.adb).start()
        patch.object(helper.subprocess, 'check_output', side_effect=self.capture).start()

    def capture(self, command):
        if command[-1] == helper.RC:
            return self.startup
        return self.files[command[-1]]

    def adb(self, *args, **kwargs):
        self.calls.append(args)
        if args == ('shell', 'id', '-u'):
            return '0'
        if args[0] == 'push':
            self.files[args[2]] = Path(args[1]).read_bytes()
        elif args[:2] == ('shell', 'sha256sum'):
            return digest(self.files[args[2]]) + ' ' + args[2]
        elif args[:2] == ('shell', 'mv'):
            self.files[args[3]] = self.files.pop(args[2])
        elif args[:2] == ('shell', 'getprop'):
            return 'stopped' if self.fail_start else 'running'
        return ''

    def test_update_preserves_startup_and_retains_exact_rollback_binary(self):
        helper.update()
        self.assertEqual(self.files[helper.BIN], b'new helper')
        saved = json.loads(self.receipt.read_text())
        self.assertEqual(saved['sha256'], digest(b'new helper'))
        self.assertFalse(saved['boot_startup_verified'])
        self.assertEqual((self.root / saved['previous_binary_backup']).read_bytes(), b'old helper')
        self.assertFalse(any('mount' in call or 'dd' in call or 'truncate' in call
                             for call in self.calls))

    def test_foreign_startup_and_binary_refuse_before_stopping_service(self):
        for target in ['startup', 'binary']:
            with self.subTest(target=target):
                self.startup = b'foreign' if target == 'startup' else b'unchanged reviewed init'
                self.files[helper.BIN] = b'foreign' if target == 'binary' else b'old helper'
                with self.assertRaises(RuntimeError):
                    helper.update()
                self.assertNotIn(('stop',), self.calls)

    def test_failed_start_restores_original_binary_without_changing_receipt(self):
        before = self.receipt.read_bytes()
        self.fail_start = True
        with self.assertRaisesRegex(RuntimeError, 'did not start'):
            helper.update()
        self.assertEqual(self.files[helper.BIN], b'old helper')
        self.assertEqual(self.receipt.read_bytes(), before)

    def test_device_mismatch_refuses_before_mutation(self):
        with patch.object(helper, 'select_r1', return_value='another-r1'):
            with self.assertRaises(RuntimeError):
                helper.update()
        self.assertNotIn(('stop',), self.calls)


if __name__ == '__main__':
    unittest.main()
