"""Host-only regressions for bounded theme startup and race-free restoration."""
import contextlib
import io
import json
import os
from pathlib import Path
import runpy
import shlex
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]


class ThemeBootTests(unittest.TestCase):
    def run_boot(self, failures=0, missing_package='', clock_changed=False):
        with tempfile.TemporaryDirectory(prefix='rabbit-theme-boot-test-') as temporary:
            directory = Path(temporary)
            (directory / 'enabled').touch()
            for name in ('RabbitPhonePalette', 'RabbitPhoneCalendar',
                         'RabbitPhoneContacts', 'RabbitPhoneMessaging', 'RabbitPhoneFDroid'):
                (directory / (name + '.xml')).write_text('<overlay/>')
            source = (ROOT / 'theme/apply-at-boot.sh').read_text()
            declaration = 'THEME=/data/system/rabbit-phone-theme'
            self.assertEqual(source.count(declaration), 1)
            source = source.replace(declaration, 'THEME=' + shlex.quote(str(directory)))
            # Empty PATH prevents an unexpected command from reaching a real
            # Android tool. All expected commands are explicit shell functions.
            shim = r'''
sleep() { :; }
pidof() { [ "$*" = com.android.systemui ] && printf '4321\n'; }
kill() { printf 'kill %s\n' "$*" >> "$EVENT_LOG"; [ "$*" = '-TERM 4321' ]; }
app_process() {
    printf 'app_process %s\n' "$*" >> "$EVENT_LOG"
    [ "$*" = '/system/bin ClockOverlay' ]
}
pm() {
    [ "$1" = path ] || return 2
    [ "$2" != "$MISSING_PACKAGE" ]
}
cmd() {
    printf 'cmd %s\n' "$*" >> "$EVENT_LOG"
    [ "$1" = overlay ] || return 2
    case "$2" in
        fabricate)
            if [ "$4" = android ] && [ "$6" = RabbitPhonePalette ]; then
                palette_attempts=$((palette_attempts + 1))
                [ "$palette_attempts" -gt "$FAILURES" ] || return 1
            fi
            ;;
        enable) ;;
        lookup)
            case "$4" in
                android:color/system_primary_dark) printf '#ffff5a1f\n' ;;
                android:color/system_background_dark) printf '#ff0a0a09\n' ;;
                com.android.systemui:dimen/keyguard_clock_line_spacing_scale)
                    if [ "$CLOCK_CHANGED" = 1 ]; then printf '0.7\n'; else printf '1.0\n'; fi ;;
                com.android.systemui:dimen/small_clock_text_size) printf '68.0dip\n' ;;
                *) return 2 ;;
            esac
            ;;
        *) return 2 ;;
    esac
    return 0
}
palette_attempts=0
'''
            events = directory / 'events'
            env = dict(os.environ, PATH='', EVENT_LOG=str(events),
                       FAILURES=str(failures), MISSING_PACKAGE=missing_package,
                       CLOCK_CHANGED='1' if clock_changed else '0')
            result = subprocess.run(['/bin/sh', '-c', shim + source], env=env,
                                    capture_output=True, text=True, timeout=5)
            self.assertEqual(result.stderr, '')
            return result.returncode, events.read_text().splitlines()

    @staticmethod
    def fabrication_count(events, package, name):
        prefix = 'cmd overlay fabricate --target ' + package + ' --name ' + name + ' '
        return sum(event.startswith(prefix) for event in events)

    def test_transient_fabrication_failure_retries_then_succeeds(self):
        status, events = self.run_boot(failures=1)
        self.assertEqual(status, 0)
        self.assertEqual(self.fabrication_count(events, 'android', 'RabbitPhonePalette'), 2)
        self.assertEqual(events.count('app_process /system/bin ClockOverlay'), 2)
        self.assertIn('cmd overlay enable --user 0 com.android.shell:RabbitPhonePalette', events)

    def test_persistent_failure_is_bounded_and_does_not_block_later_apps(self):
        status, events = self.run_boot(failures=99)
        self.assertEqual(status, 1)
        self.assertEqual(self.fabrication_count(events, 'android', 'RabbitPhonePalette'), 3)
        self.assertEqual(self.fabrication_count(events, 'org.fdroid.fdroid', 'RabbitPhoneFDroid'), 3)
        self.assertEqual(events.count('app_process /system/bin ClockOverlay'), 3)
        self.assertNotIn('cmd overlay enable --user 0 com.android.shell:RabbitPhonePalette', events)

    def test_removed_optional_app_does_not_block_remaining_apps(self):
        status, events = self.run_boot(missing_package='com.android.calendar')
        self.assertEqual(status, 0)
        self.assertEqual(self.fabrication_count(events, 'com.android.calendar', 'RabbitPhoneCalendar'), 0)
        self.assertNotIn('cmd overlay enable --user 0 com.android.shell:RabbitPhoneCalendar', events)
        self.assertEqual(self.fabrication_count(events, 'org.fdroid.fdroid', 'RabbitPhoneFDroid'), 1)
        self.assertIn('cmd overlay enable --user 0 com.android.shell:RabbitPhoneFDroid', events)

    def test_clock_recreation_happens_once_before_palette_and_not_on_retry(self):
        status, events = self.run_boot(failures=1, clock_changed=True)
        self.assertEqual(status, 0)
        self.assertEqual(events.count('kill -TERM 4321'), 1)
        palette = next(i for i, value in enumerate(events) if 'fabricate --target android --name RabbitPhonePalette ' in value)
        self.assertLess(events.index('kill -TERM 4321'), palette)
        status, events = self.run_boot(clock_changed=False)
        self.assertEqual(status, 0)
        self.assertFalse(any(value.startswith('kill ') for value in events))


class ThemeRestoreTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='rabbit-theme-restore-test-')
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        (self.directory / 'before.json').write_text(json.dumps({
            'device_serial': 'test-r1', 'settings': {}}))
        with mock.patch.object(sys, 'path', [str(ROOT / 'scripts'), *sys.path]), \
                mock.patch.object(sys, 'dont_write_bytecode', True):
            namespace = runpy.run_path(str(ROOT / 'scripts/theme_profile.py'),
                                      run_name='theme_profile_test')
        profile_type = namespace['Profile']
        self.profile = profile_type.__new__(profile_type)
        self.profile.serial = 'test-r1'
        patcher = mock.patch.dict(profile_type.restore.__globals__, EVIDENCE=self.directory)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.calls = []
        self.states_seen = []
        self.states = iter(['running', 'stopping', 'stopped'])
        self.never_stops = False
        self.clock_installed = False
        self.profile.shell = self.shell
        self.overlay = namespace['OVERLAY']

    def shell(self, *args):
        self.calls.append(args)
        if args == ('getprop', 'init.svc.rabbit-phone-theme'):
            state = 'running' if self.never_stops else next(self.states)
            self.states_seen.append(state)
            return state
        if args == ('cmd', 'overlay', 'list'):
            self.assertTrue(self.states_seen, 'Overlay access happened before waiting for stop')
            self.assertEqual(self.states_seen[-1], 'stopped')
            return self.overlay + ('\ncom.android.shell:RabbitPhoneClock' if self.clock_installed else '')
        if args == ('pidof', 'com.android.systemui'):
            return '4321'
        return ''

    def restore(self):
        with mock.patch('time.sleep'), contextlib.redirect_stdout(io.StringIO()):
            self.profile.restore()

    def test_restore_waits_for_service_stop_before_disabling_overlays(self):
        self.restore()
        self.assertEqual(self.calls[:2], [
            ('rm', '-f', '/data/system/rabbit-phone-theme/enabled'),
            ('setprop', 'ctl.stop', 'rabbit-phone-theme')])
        self.assertEqual(self.states_seen, ['running', 'stopping', 'stopped'])
        disable = ('cmd', 'overlay', 'disable', '--user', '0', self.overlay)
        self.assertIn(disable, self.calls)
        stop_checks = [i for i, call in enumerate(self.calls) if call[0] == 'getprop']
        self.assertGreater(self.calls.index(disable), stop_checks[-1])

    def test_restore_stop_timeout_refuses_overlay_changes(self):
        self.never_stops = True
        with self.assertRaisesRegex(RuntimeError, 'Theme service did not stop'):
            self.restore()
        self.assertEqual(len(self.states_seen), 40)
        self.assertEqual(self.calls[0], ('rm', '-f', '/data/system/rabbit-phone-theme/enabled'))
        self.assertIn(('setprop', 'ctl.stop', 'rabbit-phone-theme'), self.calls)
        self.assertFalse(any(call[:2] == ('cmd', 'overlay') for call in self.calls))

    def test_restore_recreates_clock_after_its_overlay_is_disabled(self):
        self.clock_installed = True
        self.restore()
        disable = ('cmd', 'overlay', 'disable', '--user', '0', 'com.android.shell:RabbitPhoneClock')
        refresh = ('kill', '-TERM', '4321')
        self.assertIn(disable, self.calls)
        self.assertIn(refresh, self.calls)
        self.assertLess(self.calls.index(disable), self.calls.index(refresh))


if __name__ == '__main__':
    unittest.main()
