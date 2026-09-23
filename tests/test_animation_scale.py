"""Host regressions for SettingsProvider versus Android's cached animator scale."""
import importlib
from pathlib import Path
import sys
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
with mock.patch.object(sys, 'path', [str(ROOT / 'scripts'), *sys.path]):
    animation_scale = importlib.import_module('animation_scale')
    card_flows = importlib.import_module('verify_card_flows')


class FakeAndroid:
    """Deleting a setting retains WMS's cached value; app callbacks are async."""
    def __init__(self, persisted='null', effective=1.0, delay=0):
        self.persisted = persisted
        self.effective = effective
        self.delay = delay
        self.pending = None
        self.reads_left = 0
        self.writes = []
        self.diagnostic = None
        self.on_diagnostic = None

    def shell(self, *args):
        if args == ('settings', 'get', 'global', animation_scale.SETTING):
            return self.persisted
        if args[:4] == ('settings', 'put', 'global', animation_scale.SETTING):
            self.writes.append(args)
            self.persisted = args[4]
            self.pending = float(args[4])
            self.reads_left = self.delay
            return ''
        if args == ('settings', 'delete', 'global', animation_scale.SETTING):
            self.writes.append(args)
            self.persisted = 'null'
            # The absent key resolves to the current WMS cached value.
            self.pending = self.effective
            self.reads_left = self.delay
            return 'Deleted 1 rows'
        if args == ('dumpsys', 'activity', animation_scale.PACKAGE + '/.HomeActivity', 'rabbit-navigation'):
            if self.pending is not None:
                if self.reads_left:
                    self.reads_left -= 1
                else:
                    self.effective, self.pending = self.pending, None
            if self.on_diagnostic is not None:
                self.on_diagnostic(self)
            if self.diagnostic is not None:
                return self.diagnostic
            return ('    rabbit_animator_duration_scale=' + str(self.effective)
                    + '\n    rabbit_animators_enabled=' + str(self.effective > 0).lower() + '\n')
        raise AssertionError('Unexpected device command: ' + repr(args))


class AnimationScaleTests(unittest.TestCase):
    def setUp(self):
        patcher = mock.patch.object(animation_scale.time, 'sleep')
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_old_delete_only_restore_leaves_animations_disabled(self):
        device = FakeAndroid()
        device.shell('settings', 'put', 'global', animation_scale.SETTING, '0')
        self.assertEqual(animation_scale.runtime_scale(device).scale, 0)
        device.shell('settings', 'delete', 'global', animation_scale.SETTING)
        self.assertEqual(device.persisted, 'null')
        self.assertEqual(animation_scale.runtime_scale(device), animation_scale.RuntimeScale(0, False))

    def test_absent_setting_restores_live_default_before_delete(self):
        device = FakeAndroid(delay=2)
        guard = animation_scale.AnimationScaleGuard(device)
        for value in ('5', '20', '0'):
            guard.set(value)
            self.assertEqual(device.effective, float(value))
        self.assertTrue(guard.restore())
        self.assertEqual(device.persisted, 'null')
        self.assertEqual(device.effective, 1)
        self.assertEqual(device.writes[-2][-1], '1.0')
        self.assertEqual(device.writes[-1][1], 'delete')

    def test_explicit_zero_and_nondefault_scales_are_preserved(self):
        for original in ('0', '0.5', '2.50'):
            with self.subTest(original=original):
                device = FakeAndroid(original, float(original), delay=1)
                guard = animation_scale.AnimationScaleGuard(device)
                guard.set('20')
                self.assertTrue(guard.restore())
                self.assertEqual(device.persisted, original)
                self.assertEqual(device.effective, float(original))
                self.assertTrue(all(write[1] == 'put' for write in device.writes))

    def test_absent_setting_keeps_original_live_zero_or_nondefault(self):
        for effective in (0, .75, 3):
            with self.subTest(effective=effective):
                device = FakeAndroid(effective=effective)
                guard = animation_scale.AnimationScaleGuard(device)
                guard.set('5')
                self.assertTrue(guard.restore())
                self.assertEqual(device.persisted, 'null')
                self.assertEqual(device.effective, effective)

    def test_external_change_is_never_overwritten(self):
        device = FakeAndroid()
        guard = animation_scale.AnimationScaleGuard(device)
        guard.set('0')
        device.persisted, device.effective = '2', 2
        writes = list(device.writes)
        self.assertFalse(guard.restore())
        with self.assertRaisesRegex(RuntimeError, 'outside this test'):
            guard.set('5')
        self.assertEqual(device.writes, writes)

    def test_original_persisted_value_alone_does_not_prove_restoration(self):
        device = FakeAndroid()
        guard = animation_scale.AnimationScaleGuard(device)
        guard.set('0')
        device.persisted = 'null'
        writes = list(device.writes)
        self.assertFalse(guard.restore())
        self.assertEqual(device.writes, writes)

    def test_already_restored_state_succeeds_without_writes(self):
        device = FakeAndroid()
        guard = animation_scale.AnimationScaleGuard(device)
        guard.set('0')
        device.persisted, device.effective = 'null', 1
        writes = list(device.writes)
        self.assertTrue(guard.restore())
        self.assertEqual(device.writes, writes)

    def test_external_change_during_restore_prevents_delete(self):
        device = FakeAndroid(delay=1)
        guard = animation_scale.AnimationScaleGuard(device)
        guard.set('0')
        def external_change(current):
            if current.effective == 1:
                current.persisted = '2'
        device.on_diagnostic = external_change
        self.assertFalse(guard.restore())
        self.assertEqual(device.persisted, '2')
        self.assertFalse(any(write[1] == 'delete' for write in device.writes))

    def test_missing_ambiguous_and_invalid_diagnostic_fail_before_writes(self):
        diagnostics = ['', 'No activities match', 'rabbit_animators_enabled=true',
                       'rabbit_animator_duration_scale=1\nrabbit_animators_enabled=unknown']
        for value in ('nan', 'inf', '-1', 'nope', '1e309'):
            diagnostics.append('rabbit_animator_duration_scale=' + value
                               + '\nrabbit_animators_enabled=true')
        diagnostics.append('rabbit_animator_duration_scale=1\nrabbit_animator_duration_scale=2'
                           + '\nrabbit_animators_enabled=true')
        for diagnostic in diagnostics:
            with self.subTest(diagnostic=diagnostic):
                device = FakeAndroid()
                device.diagnostic = diagnostic
                with self.assertRaises(RuntimeError):
                    animation_scale.AnimationScaleGuard(device)
                self.assertEqual(device.writes, [])

    def test_invalid_persisted_setting_and_test_values_fail_before_writes(self):
        for value in ('nan', 'inf', '-1', 'bad'):
            with self.subTest(value=value):
                device = FakeAndroid(persisted=value)
                with self.assertRaises(RuntimeError):
                    animation_scale.AnimationScaleGuard(device)
                self.assertEqual(device.writes, [])
                device = FakeAndroid()
                guard = animation_scale.AnimationScaleGuard(device)
                with self.assertRaises(RuntimeError):
                    guard.set(value)
                self.assertEqual(device.writes, [])

    def test_setting_change_during_capture_fails_before_writes(self):
        device = FakeAndroid()
        device.on_diagnostic = lambda current: setattr(current, 'persisted', '2')
        with self.assertRaisesRegex(RuntimeError, 'during capture'):
            animation_scale.AnimationScaleGuard(device)
        self.assertEqual(device.writes, [])

    def test_runtime_timeout_still_allows_restoration(self):
        device = FakeAndroid(delay=1)
        guard = animation_scale.AnimationScaleGuard(device, timeout=0)
        with self.assertRaisesRegex(RuntimeError, 'did not receive'):
            guard.set('0')
        # The timed-out test callback can arrive before cleanup starts.
        self.assertEqual(animation_scale.runtime_scale(device).scale, 0)
        device.delay = 0
        self.assertTrue(guard.restore())
        self.assertEqual(device.effective, 1)

    def test_restoration_runtime_timeout_does_not_delete_setting(self):
        device = FakeAndroid()
        guard = animation_scale.AnimationScaleGuard(device, timeout=0)
        guard.set('0')
        device.delay = 10
        with self.assertRaisesRegex(RuntimeError, 'did not receive'):
            guard.restore()
        self.assertEqual(device.persisted, '1.0')
        self.assertFalse(any(write[1] == 'delete' for write in device.writes))

    def test_interruption_failure_runs_restoration(self):
        device = FakeAndroid()
        result = {}
        with mock.patch.object(card_flows, 'home', side_effect=RuntimeError('navigation failed')):
            with self.assertRaisesRegex(RuntimeError, 'navigation failed'):
                card_flows.interrupt_flow(device, 'wheel', 'power', result)
        self.assertTrue(result['animator_duration_scale_restored'])
        self.assertEqual(device.persisted, 'null')
        self.assertEqual(device.effective, 1)

    def test_interruption_flow_reports_failed_restoration(self):
        device = FakeAndroid()
        result = {}
        def interrupted(_):
            device.persisted, device.effective = '2', 2
            raise RuntimeError('navigation failed')
        with mock.patch.object(card_flows, 'home', side_effect=interrupted):
            with self.assertRaisesRegex(RuntimeError, 'changed externally'):
                card_flows.interrupt_flow(device, 'wheel', 'power', result)
        self.assertFalse(result['animator_duration_scale_restored'])
        self.assertEqual(device.persisted, '2')


if __name__ == '__main__':
    unittest.main()
