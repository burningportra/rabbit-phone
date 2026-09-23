"""Host-only font transaction tests; no ADB, private font, SDK or device required."""
import contextlib
import copy
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import install_system_font as installer


def sha(data):
    return hashlib.sha256(data).hexdigest()


class SimulatedDevice:
    """Model the storage and framework safety constraints, with write faults."""
    serial = 'synthetic-r1'

    def __init__(self, entries, before, after):
        self.files = dict(zip(installer.TARGETS, before))
        self.before = dict(self.files)
        self.after = dict(zip(installer.TARGETS, after))
        self.saved_metadata = {item['path']: copy.deepcopy(item['metadata']) for item in entries}
        self.mounts = {mount: 'ro' for mount in installer.MOUNTS}
        self.staged = {}
        self.verified = {}
        self.events = []
        self.running = True
        self.stop_verified = False
        self.fail_write = None
        self.fail_readonly_mount = None

    def preflight(self):
        pass

    def readonly(self):
        if any(state != 'ro' for state in self.mounts.values()):
            raise RuntimeError('Mount is still writable')

    def stopped(self):
        if self.running:
            raise AssertionError('Framework has not stopped')
        self.stop_verified = True

    def metadata(self, path):
        result = copy.deepcopy(self.saved_metadata[path])
        result['stat'][2] = str(len(self.files[path]))
        return result

    def read(self, path):
        return self.files[path]

    def sha(self, path):
        if path in self.files:
            self.verified[path] = sha(self.files[path])
            return self.verified[path]
        return sha(self.staged[path])

    def adb(self, *args):
        if args[0] != 'push':
            raise AssertionError('Unexpected simulated ADB operation: ' + repr(args))
        self.staged[args[2]] = Path(args[1]).read_bytes()
        return ''

    def shell(self, *args):
        self.events.append(args)
        if args == ('getprop', 'ro.build.fingerprint'):
            return 'synthetic-build'
        if args[0] in ('mkdir', 'chmod', 'rm', 'sync'):
            return ''
        if args == ('stop',):
            self.running = False
            self.stop_verified = False
            return ''
        if args == ('start',):
            self.readonly()
            if self.files not in (self.before, self.after):
                raise AssertionError('Restart with mixed or partially written font/config files')
            if any(self.verified.get(path) != sha(data) for path, data in self.files.items()):
                raise AssertionError('Restart before verifying every final file')
            self.running = True
            return ''
        if args[0] == 'mount':
            state = args[2].split(',')[1]
            mount = args[-1]
            if state == 'rw' and (self.running or not self.stop_verified):
                raise AssertionError('Writable remount before confirming the framework stopped')
            if state == 'ro' and mount == self.fail_readonly_mount:
                self.fail_readonly_mount = None
                raise RuntimeError('Injected read-only remount failure')
            self.mounts[mount] = state
            return ''
        if args[0] == 'dd':
            options = dict(arg.split('=', 1) for arg in args[1:])
            path = options['of']
            mount = '/product' if path.startswith('/product/') else '/'
            if self.running or not self.stop_verified or self.mounts[mount] != 'rw':
                raise AssertionError('Write without a stopped framework and writable mount')
            data = self.staged[options['if']]
            if 'notrunc' not in options.get('conv', '').split(','):
                raise AssertionError('A font transaction must not truncate an existing inode')
            if len(data) != len(self.files[path]):
                raise AssertionError('A font transaction must preserve allocated file lengths')
            self.verified.pop(path, None)
            if path == self.fail_write:
                self.fail_write = None
                self.files[path] = data[:7] + self.files[path][7:]
                raise RuntimeError('Injected partial write')
            self.files[path] = data
            return ''
        raise AssertionError('Unexpected simulated shell operation: ' + repr(args))


class SystemFontTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='rabbit-font-test-')
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        # An accidental use of the real Device class must fail before spawning ADB.
        no_processes = patch.object(installer.subprocess, 'run',
                                    side_effect=AssertionError('Host tests must not launch processes'))
        no_processes.start()
        self.addCleanup(no_processes.stop)
        self.output = io.StringIO()
        output_capture = contextlib.redirect_stdout(self.output)
        output_capture.__enter__()
        self.addCleanup(output_capture.__exit__, None, None, None)

    def transaction(self):
        record = {'version': 1, 'device_serial': SimulatedDevice.serial,
                  'fingerprint': 'synthetic-build', 'id': 'a' * 32,
                  'phase': 'prepared', 'pending': [], 'probe_sha256': 'synthetic-probe', 'files': []}
        before, after = [], []
        for index, path in enumerate(installer.TARGETS):
            original = bytes([65 + index]) * 32
            replacement = bytes([97 + index]) * 32
            before.append(original)
            after.append(replacement)
            record['files'].append({
                'path': path,
                'metadata': {'stat': ['1', str(index + 100), '32', '644', '0', '0'],
                             'label': 'u:object_r:system_file:s0'},
                'before': sha(original), 'after': sha(replacement)})
            (self.directory / (str(index) + '.before')).write_bytes(original)
            (self.directory / (str(index) + '.after')).write_bytes(replacement)
        # Write a persisted transaction independently of the installer journal writer.
        (self.directory / 'transaction.json').write_text(json.dumps(record))
        return SimulatedDevice(record['files'], before, after)

    def change(self, device, restoring=False):
        installer.change(self.directory, device, restoring=restoring)

    def test_padding_preserves_length_and_rejects_unverified_or_oversized_fonts(self):
        # Synthetic bytes keep this suite distributable without the owner's font.
        font = b'synthetic font fixture'
        with patch.object(installer, 'STOCK_SHA256', sha(font)):
            result = installer.padded_font(font, 64)
            self.assertEqual(result, font + bytes(64 - len(font)))
            with self.assertRaises(RuntimeError):
                installer.padded_font(b'unverified font', 64)
            with self.assertRaises(RuntimeError):
                installer.padded_font(font, len(font) - 1)

    def test_xml_preserves_fallbacks_aliases_and_static_clock_families(self):
        system = b'''<familyset>
          <family name="sans-serif"><font supportedAxes="wght">Roboto-Regular.ttf<axis tag="wght" stylevalue="700"/></font><font weight="700">Other.ttf</font></family>
          <family lang="ja"><font>JP.otf</font></family>
          <family lang="und-Zsye"><font>Emoji.ttf</font></family>
          <alias name="body" to="sans-serif"/>
        </familyset>''' + b' ' * 256
        result = installer.patch_xml(system)
        self.assertEqual(len(result), len(system))
        root = ET.fromstring(result)
        fonts = root.find('family').findall('font')
        self.assertEqual(len(fonts), 1)
        self.assertEqual(fonts[0].text, 'Roboto-Regular.ttf')
        self.assertEqual(fonts[0].attrib, {'weight': '400', 'style': 'normal'})
        self.assertEqual(list(fonts[0]), [])
        self.assertEqual(root.find("family[@lang='ja']/font").text, 'JP.otf')
        self.assertEqual(root.find("family[@lang='und-Zsye']/font").text, 'Emoji.ttf')
        self.assertEqual(root.find('alias').attrib, {'name': 'body', 'to': 'sans-serif'})

        product = b'''<fonts-modification>
          <family name="rmsans" customizationType="new-named-family"><font>realchoice-sans.ttf<axis tag="wght" stylevalue="100"/></font><font>realchoice-sans.ttf</font></family>
          <family name="cipher-clock"><font>oplus-sans.ttf</font></family>
          <family name="other"><font>Other.ttf</font></family>
        </fonts-modification>''' + b' ' * 256
        result = installer.patch_xml(product, product=True)
        self.assertEqual(len(result), len(product))
        root = ET.fromstring(result)
        for name in ('rmsans', 'cipher-clock'):
            with self.subTest(family=name):
                fonts = root.find("family[@name='" + name + "']").findall('font')
                self.assertEqual(len(fonts), 1)
                self.assertEqual(fonts[0].text, 'realchoice-sans.ttf')
                self.assertEqual(fonts[0].attrib, {'weight': '400', 'style': 'normal'})
                self.assertEqual(list(fonts[0]), [])
        self.assertEqual(root.find("family[@name='rmsans']").get('customizationType'),
                         'new-named-family')
        self.assertEqual(root.find("family[@name='other']/font").text, 'Other.ttf')
        with self.assertRaises(RuntimeError):
            installer.patch_xml(b'<familyset/>')

    def test_apply_and_restore_verify_files_before_readonly_restart(self):
        device = self.transaction()
        metadata = {path: device.metadata(path) for path in installer.TARGETS}
        self.change(device)
        self.assertEqual(device.files, device.after)
        self.assertTrue(device.running)
        device.readonly()
        self.change(device, restoring=True)
        self.assertEqual(device.files, device.before)
        self.assertTrue(device.running)
        device.readonly()
        self.assertEqual({path: device.metadata(path) for path in installer.TARGETS}, metadata)
        self.assertEqual(device.events.count(('stop',)), 2)
        self.assertEqual(device.events.count(('start',)), 2)

    def test_interrupted_apply_stays_stopped_and_can_restore_all_files(self):
        device = self.transaction()
        damaged = installer.TARGETS[3]
        device.fail_write = damaged
        with self.assertRaisesRegex(RuntimeError, 'Injected partial write'):
            self.change(device)
        self.assertNotIn(device.files[damaged], (device.before[damaged], device.after[damaged]))
        self.assertFalse(device.running)
        self.assertNotIn(('start',), device.events)
        device.readonly()
        self.change(device, restoring=True)
        self.assertEqual(device.files, device.before)
        self.assertTrue(device.running)

    def test_interrupted_restore_retains_recovery_for_later_partial_file(self):
        device = self.transaction()
        device.fail_write = installer.TARGETS[3]
        with self.assertRaisesRegex(RuntimeError, 'Injected partial write'):
            self.change(device)
        later_partial = device.files[installer.TARGETS[3]]
        device.fail_write = installer.TARGETS[0]
        with self.assertRaisesRegex(RuntimeError, 'Injected partial write'):
            self.change(device, restoring=True)
        self.assertEqual(device.files[installer.TARGETS[3]], later_partial)
        self.assertFalse(device.running)
        device.readonly()
        # A new invocation must recover both interrupted writes from the disk journal.
        self.change(device, restoring=True)
        self.assertEqual(device.files, device.before)
        self.assertTrue(device.running)

    def test_external_modification_and_corrupt_backup_refuse_before_stop(self):
        for fault in ('external modification', 'corrupt backup'):
            with self.subTest(fault=fault):
                device = self.transaction()
                if fault == 'external modification':
                    device.files[installer.TARGETS[0]] = b'x' * 32
                else:
                    (self.directory / '1.before').write_bytes(b'x' * 32)
                previous = dict(device.files)
                with self.assertRaises(RuntimeError):
                    self.change(device)
                self.assertEqual(device.files, previous)
                self.assertTrue(device.running)
                self.assertNotIn(('stop',), device.events)
                self.assertFalse(any(event[0] in ('mount', 'dd') for event in device.events))

    def test_serial_mismatch_refuses_before_stop(self):
        device = self.transaction()
        device.serial = 'different-synthetic-r1'
        with self.assertRaisesRegex(RuntimeError, 'different device'):
            self.change(device)
        self.assertEqual(device.files, device.before)
        self.assertTrue(device.running)
        self.assertNotIn(('stop',), device.events)

    def test_readonly_remount_failure_attempts_other_mount_and_prevents_restart(self):
        device = self.transaction()
        device.fail_readonly_mount = '/product'
        with self.assertRaisesRegex(RuntimeError, 'Could not restore read-only mounts'):
            self.change(device)
        self.assertFalse(device.running)
        self.assertNotIn(('start',), device.events)
        self.assertEqual(device.mounts['/'], 'ro')
        self.assertEqual(device.mounts['/product'], 'rw')
        self.change(device, restoring=True)
        self.assertEqual(device.files, device.before)
        self.assertTrue(device.running)
        device.readonly()


if __name__ == '__main__':
    unittest.main()
