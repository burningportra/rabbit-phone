import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from device import wake_for_ui


class WakeDevice:
    def __init__(self, states, secure=False):
        self.states = iter(states)
        self.secure = secure
        self.calls = []

    def shell(self, *args):
        self.calls.append(args)
        if args == ('dumpsys', 'power'):
            return '  mWakefulness=Awake\n'
        if args == ('dumpsys', 'window', 'policy'):
            showing = next(self.states)
            return ('KeyguardServiceDelegate\n showing=' + showing
                    + '\n inputRestricted=' + showing + '\n secure='
                    + str(self.secure).lower() + '\nKeyguardStateMonitor\n')
        if args in (('input', 'keyevent', 'KEYCODE_WAKEUP'), ('wm', 'dismiss-keyguard')):
            return ''
        raise AssertionError('Unexpected command: ' + repr(args))


class WakeTests(unittest.TestCase):
    @mock.patch('device.time.sleep')
    def test_already_unlocked_requires_two_observations(self, _):
        d = WakeDevice(['false', 'false'])
        wake_for_ui(d)
        self.assertEqual(d.calls.count(('dumpsys', 'window', 'policy')), 2)
        self.assertNotIn(('wm', 'dismiss-keyguard'), d.calls)

    @mock.patch('device.time.sleep')
    def test_late_keyguard_appearance_is_dismissed_before_success(self, _):
        d = WakeDevice(['false', 'true', 'false', 'false'])
        wake_for_ui(d)
        self.assertEqual(d.calls.count(('dumpsys', 'window', 'policy')), 4)
        self.assertEqual(d.calls.count(('wm', 'dismiss-keyguard')), 1)

    def test_authentication_lock_is_never_dismissed(self):
        d = WakeDevice(['true'], secure=True)
        with self.assertRaisesRegex(RuntimeError, 'Unlock the R1 normally'):
            wake_for_ui(d)
        self.assertNotIn(('wm', 'dismiss-keyguard'), d.calls)

    @mock.patch('device.time.sleep')
    @mock.patch('device.time.monotonic', side_effect=[0, .1, 1])
    def test_missing_unlock_evidence_times_out(self, *_):
        d = WakeDevice(['unknown'])
        with self.assertRaisesRegex(RuntimeError, 'confirmed awake, unlocked'):
            wake_for_ui(d, timeout=.5)
        self.assertNotIn(('wm', 'dismiss-keyguard'), d.calls)


if __name__ == '__main__':
    unittest.main()
