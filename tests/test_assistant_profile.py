"""Host-only tests for the reversible Android Assistant profile."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import assistant_profile as profile


class FakeDevice:
    serial = 'synthetic-r1'

    def __init__(self):
        self.role_holders = ['com.original.assistant']
        self.settings = {
            ('secure', 'assistant'): 'com.original.assistant/.Assist',
            ('secure', 'voice_interaction_service'): 'com.original.assistant/.Voice',
            ('secure', 'voice_recognition_service'): 'com.original.recognizer/.Recognition',
            ('global', profile.POWER_KEY): '1',
        }
        self.events = []
        self.fail_power = False
        self.policy_assistant = False
        self.before_mutation = None

    def shell(self, *args):
        self.events.append(args)
        if args == ('getprop', 'ro.product.device'):
            return 'r1'
        if args == ('getprop', 'ro.build.version.release'):
            return '16'
        if args[:3] == ('cmd', 'package', 'resolve-activity'):
            return ('ActivityInfo{name=' + profile.PACKAGE + '.RecorderAssistActivity '
                    'permission=' + profile.ASSIST_PERMISSION
                    + ' enabled=true exported=true}')
        if args[:3] == ('cmd', 'package', 'query-activities'):
            return 'ActivityInfo{name=' + profile.PACKAGE + '.RecorderAssistActivity}'
        if args == ('dumpsys', 'package', profile.PACKAGE):
            return '\n'.join((profile.COMPONENT, '.RecorderAssistActivity',
                              profile.ASSIST_ACTION, profile.DEFAULT_CATEGORY,
                              profile.ASSIST_PERMISSION))
        if args[:3] == ('cmd', 'overlay', 'lookup'):
            return 'true'
        if args == ('cmd', 'role', 'get-role-holders', '--user', '0', profile.ROLE):
            return '\n'.join(self.role_holders)
        if args[:3] == ('cmd', 'role', 'add-role-holder'):
            if self.before_mutation:
                self.before_mutation(args)
            package = args[-1]
            self.role_holders = [package]
            if package == profile.PACKAGE:
                self.settings[('secure', 'assistant')] = profile.COMPONENT
                self.settings[('secure', 'voice_interaction_service')] = ''
                self.settings[('secure', 'voice_recognition_service')] = (
                    'com.android.speech/.RecognitionService')
            return ''
        if args[:3] == ('cmd', 'role', 'remove-role-holder'):
            package = args[-1]
            self.role_holders = [item for item in self.role_holders if item != package]
            return ''
        if args[:2] == ('settings', 'get'):
            return self.settings.get((args[2], args[3]), 'null')
        if args[:2] == ('settings', 'put'):
            if self.before_mutation:
                self.before_mutation(args)
            namespace, key, value = args[2:5]
            if namespace == 'global' and key == profile.POWER_KEY and self.fail_power:
                self.fail_power = False
                raise RuntimeError('injected power write failure')
            self.settings[(namespace, key)] = value
            if namespace == 'global' and key == profile.POWER_KEY:
                self.policy_assistant = value == profile.POWER_VALUE
            return ''
        if args[:2] == ('settings', 'delete'):
            self.settings.pop((args[2], args[3]), None)
            if args[2:] == ('global', profile.POWER_KEY):
                self.policy_assistant = False
            return ''
        if args == ('dumpsys', 'window', 'policy'):
            return 'LONG_PRESS_POWER_ASSISTANT' if self.policy_assistant else 'LONG_PRESS_POWER_GLOBAL_ACTIONS'
        raise AssertionError('Unexpected fake command: ' + repr(args))


class AssistantProfileTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='rabbit-assistant-test-')
        self.addCleanup(temporary.cleanup)
        self.snapshot = Path(temporary.name) / 'assistant-before.json'
        self.device = FakeDevice()
        self.subject = profile.AssistantProfile(
            self.device, self.snapshot, sleep=lambda _: None, poll_attempts=2)

    def test_apply_journals_before_mutations_and_captures_role_side_effects(self):
        observed = {}

        def inspect_journal(command):
            data = json.loads(self.snapshot.read_text())
            observed[command] = (data['state'], data['progress'])

        self.device.before_mutation = inspect_journal
        result = self.subject.apply()
        role_write = ('cmd', 'role', 'add-role-holder', '--user', '0', profile.ROLE,
                      profile.PACKAGE)
        power_write = ('settings', 'put', 'global', profile.POWER_KEY,
                       profile.POWER_VALUE)
        self.assertEqual(observed[role_write], ('applying', []))
        self.assertEqual(observed[power_write], ('applying', ['role']))
        self.assertEqual(result['state'], 'applied')
        self.assertEqual(result['applied']['settings']['secure:voice_interaction_service'],
                         {'exists': True, 'value': ''})

    def test_existing_applied_profile_is_a_noop(self):
        self.subject.apply()
        before = list(self.device.events)
        result = self.subject.apply()
        new_events = self.device.events[len(before):]
        self.assertEqual(result['state'], 'applied')
        self.assertFalse(any(event[:3] == ('cmd', 'role', 'add-role-holder')
                             for event in new_events))
        self.assertFalse(any(event[:2] == ('settings', 'put') for event in new_events))

    def test_apply_refuses_ready_or_restored_foreign_drift_without_writing(self):
        self.subject.backup()
        for state in ('ready', 'restored'):
            with self.subTest(state=state):
                journal = json.loads(self.snapshot.read_text())
                journal['state'] = state
                self.snapshot.write_text(json.dumps(journal))
                self.device.settings[('global', profile.POWER_KEY)] = 'foreign'
                before_events = len(self.device.events)
                before_journal = self.snapshot.read_text()
                with self.assertRaisesRegex(RuntimeError, 'saved original state'):
                    self.subject.apply()
                events = self.device.events[before_events:]
                self.assertEqual(self.snapshot.read_text(), before_journal)
                self.assertFalse(any(event[:3] in (
                    ('cmd', 'role', 'add-role-holder'),
                    ('cmd', 'role', 'remove-role-holder')) for event in events))
                self.assertFalse(any(event[:2] in (
                    ('settings', 'put'), ('settings', 'delete')) for event in events))
                self.device.settings[('global', profile.POWER_KEY)] = '1'

    def test_apply_requires_restore_for_interrupted_journal_states(self):
        self.subject.backup()
        for state in ('applying', 'restoring'):
            with self.subTest(state=state):
                journal = json.loads(self.snapshot.read_text())
                journal['state'] = state
                self.snapshot.write_text(json.dumps(journal))
                before_events = len(self.device.events)
                before_journal = self.snapshot.read_text()
                with self.assertRaisesRegex(RuntimeError, 'Complete restore'):
                    self.subject.apply()
                events = self.device.events[before_events:]
                self.assertEqual(self.snapshot.read_text(), before_journal)
                self.assertFalse(any(event[:3] in (
                    ('cmd', 'role', 'add-role-holder'),
                    ('cmd', 'role', 'remove-role-holder')) for event in events))
                self.assertFalse(any(event[:2] in (
                    ('settings', 'put'), ('settings', 'delete')) for event in events))

    def test_apply_failure_rolls_back_and_never_claims_applied(self):
        original_role = list(self.device.role_holders)
        original_settings = copy.deepcopy(self.device.settings)
        self.device.fail_power = True
        with self.assertRaisesRegex(RuntimeError, 'injected power write failure'):
            self.subject.apply()
        journal = json.loads(self.snapshot.read_text())
        self.assertEqual(journal['state'], 'ready')
        self.assertIsNone(journal['applied'])
        self.assertEqual(self.device.role_holders, original_role)
        self.assertEqual(self.device.settings, original_settings)

    def test_restore_refuses_foreign_drift_without_writing(self):
        self.subject.apply()
        self.device.settings[('global', profile.POWER_KEY)] = '3'
        before = len(self.device.events)
        with self.assertRaisesRegex(RuntimeError, 'foreign drift'):
            self.subject.restore()
        writes = self.device.events[before:]
        self.assertFalse(any(event[:3] in (
            ('cmd', 'role', 'add-role-holder'),
            ('cmd', 'role', 'remove-role-holder')) for event in writes))
        self.assertFalse(any(event[:2] in (('settings', 'put'), ('settings', 'delete'))
                             for event in writes))

    def test_restore_preserves_absent_and_empty_settings(self):
        self.device.settings.pop(('secure', 'voice_interaction_service'))
        self.device.settings[('secure', 'voice_recognition_service')] = ''
        self.device.settings.pop(('global', profile.POWER_KEY))
        self.subject.apply()
        self.subject.restore()
        self.assertNotIn(('secure', 'voice_interaction_service'), self.device.settings)
        self.assertEqual(self.device.settings[('secure', 'voice_recognition_service')], '')
        self.assertNotIn(('global', profile.POWER_KEY), self.device.settings)
        self.assertEqual(self.device.role_holders, ['com.original.assistant'])
        journal = json.loads(self.snapshot.read_text())
        self.assertEqual(journal['state'], 'restored')

    def test_restore_recovers_an_interrupted_role_apply(self):
        original = self.subject.backup()['original']
        journal = json.loads(self.snapshot.read_text())
        journal['state'] = 'applying'
        journal['progress'] = []
        self.snapshot.write_text(json.dumps(journal))
        self.device.shell('cmd', 'role', 'add-role-holder', '--user', '0',
                          profile.ROLE, profile.PACKAGE)
        self.subject.restore()
        self.assertEqual(self.subject._read_state(), original)
        self.assertEqual(json.loads(self.snapshot.read_text())['state'], 'restored')

    def test_wrong_device_snapshot_is_rejected_before_reads_or_writes(self):
        self.subject.backup()
        self.device.events.clear()
        self.device.serial = 'different-r1'
        with self.assertRaisesRegex(RuntimeError, 'different device'):
            self.subject.restore()
        self.assertEqual(self.device.events, [])


if __name__ == '__main__':
    unittest.main()
