#!/usr/bin/env python3
"""Transactionally assign the Android Assistant role to Rabbit Phone."""
import argparse
import datetime
import json
import os
from pathlib import Path
import shlex
import subprocess
import time

from device import require_evidence_serial, select_r1


ROOT = Path(__file__).resolve().parents[1]
SNAPSHOT = ROOT / 'evidence/assistant-before.json'
USER = '0'
ROLE = 'android.app.role.ASSISTANT'
PACKAGE = 'com.kevtrinh.rabbitphone'
COMPONENT = PACKAGE + '/.RecorderAssistActivity'
ASSIST_ACTION = 'android.intent.action.ASSIST'
DEFAULT_CATEGORY = 'android.intent.category.DEFAULT'
ASSIST_PERMISSION = 'android.permission.ACCESS_VOICE_INTERACTION_SERVICE'
SECURE_KEYS = ('assistant', 'voice_interaction_service', 'voice_recognition_service')
POWER_KEY = 'power_button_long_press'
POWER_VALUE = '5'


class AdbDevice:
    def __init__(self, mutation=False):
        self.serial = select_r1(mutation=mutation)

    def shell(self, *args):
        command = ['adb', '-s', self.serial, 'shell', shlex.join(args)]
        result = subprocess.run(command, capture_output=True, text=True,
                                timeout=20, check=True)
        return result.stdout.rstrip('\r\n')


def _setting(output):
    """Represent an Android setting without losing an intentionally empty value."""
    return {'exists': output != 'null', 'value': None if output == 'null' else output}


def _atomic_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name('.' + path.name + '.tmp-' + str(os.getpid()))
    try:
        temporary.write_text(json.dumps(data, indent=2, sort_keys=True) + '\n')
        temporary.chmod(0o600)
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


class AssistantProfile:
    def __init__(self, device, snapshot=SNAPSHOT, sleep=time.sleep, poll_attempts=40):
        self.device = device
        self.snapshot = Path(snapshot)
        self.sleep = sleep
        self.poll_attempts = poll_attempts

    def shell(self, *args):
        return self.device.shell(*args)

    def _holders(self):
        output = self.shell('cmd', 'role', 'get-role-holders', '--user', USER, ROLE)
        return [line.strip() for line in output.splitlines() if line.strip()]

    def _read_settings(self):
        settings = {}
        for key in SECURE_KEYS:
            settings['secure:' + key] = _setting(
                self.shell('settings', 'get', 'secure', key))
        settings['global:' + POWER_KEY] = _setting(
            self.shell('settings', 'get', 'global', POWER_KEY))
        return settings

    def _read_state(self):
        return {'role_holders': self._holders(), 'settings': self._read_settings()}

    def _load(self):
        if not self.snapshot.exists():
            raise RuntimeError('No assistant backup exists at ' + str(self.snapshot))
        data = json.loads(self.snapshot.read_text())
        require_evidence_serial(data, self.device.serial, self.snapshot)
        return data

    def _save(self, data):
        _atomic_json(self.snapshot, data)

    def backup(self):
        if self.snapshot.exists():
            return self._load()
        data = {
            'version': 1,
            'created': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'device_serial': self.device.serial,
            'state': 'ready',
            'original': self._read_state(),
            'applied': None,
            'progress': [],
        }
        self._save(data)
        print('Saved original Assistant role and power settings to ' + str(self.snapshot))
        return data

    def _preflight(self):
        if self.shell('getprop', 'ro.product.device') != 'r1':
            raise RuntimeError('Selected device is not a Rabbit R1')
        if self.shell('getprop', 'ro.build.version.release') != '16':
            raise RuntimeError('Assistant profile requires the selected R1 to run Android 16')

        resolve = self.shell(
            'cmd', 'package', 'resolve-activity', '--user', USER,
            '-a', ASSIST_ACTION, '-c', DEFAULT_CATEGORY, '-n', COMPONENT)
        query = self.shell(
            'cmd', 'package', 'query-activities', '--user', USER,
            '-a', ASSIST_ACTION, '-c', DEFAULT_CATEGORY, '-n', COMPONENT)
        package = self.shell('dumpsys', 'package', PACKAGE)
        activity_name = PACKAGE + '.RecorderAssistActivity'
        if activity_name not in resolve or activity_name not in query:
            raise RuntimeError('RecorderAssistActivity is not installed for ACTION_ASSIST')
        resolved_contract = (activity_name, 'permission=' + ASSIST_PERMISSION,
                             'enabled=true', 'exported=true')
        if any(item not in resolve for item in resolved_contract):
            raise RuntimeError('Installed RecorderAssistActivity does not match its protected contract')
        package_contract = ('.RecorderAssistActivity', ASSIST_ACTION, DEFAULT_CATEGORY)
        if any(item not in package for item in package_contract):
            raise RuntimeError('Package dump does not expose the RecorderAssistActivity intent')

        supported = self.shell(
            'cmd', 'overlay', 'lookup', 'android',
            'android:bool/config_supportLongPressPowerWhenNonInteractive')
        if supported.strip().lower() != 'true':
            raise RuntimeError('This build does not support long-press power while non-interactive')

    def _put_setting(self, compound, saved):
        namespace, key = compound.split(':', 1)
        if saved['exists']:
            self.shell('settings', 'put', namespace, key, saved['value'])
        else:
            self.shell('settings', 'delete', namespace, key)

    def _set_holders(self, holders):
        current = self._holders()
        for package in current:
            if package not in holders:
                self.shell('cmd', 'role', 'remove-role-holder', '--user', USER,
                           ROLE, package)
        for package in holders:
            if package not in self._holders():
                self.shell('cmd', 'role', 'add-role-holder', '--user', USER,
                           ROLE, package)

    def _wait_for_assistant(self):
        for _ in range(self.poll_attempts):
            holders = self._holders()
            assistant = _setting(self.shell('settings', 'get', 'secure', 'assistant'))
            if holders == [PACKAGE] and assistant == {'exists': True, 'value': COMPONENT}:
                return
            self.sleep(.1)
        raise RuntimeError('Assistant role did not converge to ' + COMPONENT)

    def _policy_is_assistant(self):
        return 'LONG_PRESS_POWER_ASSISTANT' in self.shell('dumpsys', 'window', 'policy')

    def _verify_applied(self):
        state = self._read_state()
        if state['role_holders'] != [PACKAGE]:
            raise RuntimeError('Assistant role readback does not name Rabbit Phone exclusively')
        if state['settings']['secure:assistant'] != {'exists': True, 'value': COMPONENT}:
            raise RuntimeError('Secure assistant readback is not the recorder activity')
        if state['settings']['global:' + POWER_KEY] != {'exists': True, 'value': POWER_VALUE}:
            raise RuntimeError('Power long-press setting readback is not Assistant behavior')
        if not self._policy_is_assistant():
            raise RuntimeError('Window policy did not report LONG_PRESS_POWER_ASSISTANT')
        return state

    def _restore_original(self, data):
        self._set_holders(data['original']['role_holders'])
        # Role updates may rewrite all three values, so restore secure settings afterward.
        for compound, saved in data['original']['settings'].items():
            if compound.startswith('secure:'):
                self._put_setting(compound, saved)
        self._put_setting('global:' + POWER_KEY,
                          data['original']['settings']['global:' + POWER_KEY])

    def apply(self):
        data = self.backup()
        self._preflight()
        current = self._read_state()
        if data['state'] == 'applied' and current == data['applied']:
            self._verify_applied()
            print('Assistant profile is already applied.')
            return data
        if data['state'] == 'applied':
            raise RuntimeError('Assistant profile drifted after apply; refusing to overwrite it')
        if data['state'] in ('applying', 'restoring'):
            raise RuntimeError('Complete restore before applying the Assistant profile again')
        if data['state'] not in ('ready', 'restored'):
            raise RuntimeError('Unknown assistant journal state: ' + repr(data['state']))
        if current != data['original']:
            raise RuntimeError(
                'Device drifted from the saved original state; refusing to apply')

        data['state'] = 'applying'
        data['progress'] = []
        data.pop('last_error', None)
        self._save(data)
        try:
            self.shell('cmd', 'role', 'add-role-holder', '--user', USER, ROLE, PACKAGE)
            self._wait_for_assistant()
            data['progress'] = ['role']
            self._save(data)

            self.shell('settings', 'put', 'global', POWER_KEY, POWER_VALUE)
            data['progress'].append('power')
            self._save(data)

            data['applied'] = self._verify_applied()
            data['state'] = 'applied'
            data['progress'] = ['role', 'power', 'verified']
            self._save(data)
            print('Applied Rabbit Phone as the long-press power Assistant.')
            return data
        except Exception as error:
            try:
                self._restore_original(data)
                if self._read_state() != data['original']:
                    raise RuntimeError('rollback readback differs from the saved original state')
                data['state'] = 'ready'
                data['progress'] = []
            except Exception as rollback_error:
                data['state'] = 'applying'
                data['last_error'] = str(error) + '; rollback failed: ' + str(rollback_error)
                self._save(data)
                raise RuntimeError(data['last_error']) from error
            data['last_error'] = str(error)
            self._save(data)
            raise

    @staticmethod
    def _field_matches_partial(current, original, applied):
        return current == original or (applied is not None and current == applied)

    def _guard_restore(self, data, current):
        original = data['original']
        applied = data.get('applied')
        if data['state'] == 'applied':
            if applied is None or current != applied:
                raise RuntimeError('Assistant profile has foreign drift; refusing restore')
            return
        if data['state'] == 'applying':
            # A process can stop after RoleManager commits but before the next journal
            # replace. Accept only the two states that this transaction can create.
            role_is_original = current['role_holders'] == original['role_holders']
            role_is_rabbit = current['role_holders'] == [PACKAGE]
            assistant = current['settings']['secure:assistant']
            assistant_is_original = assistant == original['settings']['secure:assistant']
            assistant_is_rabbit = assistant == {'exists': True, 'value': COMPONENT}
            if not ((role_is_original and assistant_is_original)
                    or (role_is_rabbit and assistant_is_rabbit)):
                raise RuntimeError('Assistant role changed during apply; refusing to clobber it')
            power = current['settings']['global:' + POWER_KEY]
            allowed_power = [original['settings']['global:' + POWER_KEY],
                             {'exists': True, 'value': POWER_VALUE}]
            if power not in allowed_power:
                raise RuntimeError('Power setting changed during apply; refusing to clobber it')
            if role_is_original:
                for compound in ('secure:voice_interaction_service',
                                 'secure:voice_recognition_service'):
                    if current['settings'][compound] != original['settings'][compound]:
                        raise RuntimeError(compound + ' changed during apply; refusing restore')
            return
        if data['state'] != 'restoring':
            raise RuntimeError('Assistant profile is not applied; refusing restore')
        applied_holders = None if applied is None else applied['role_holders']
        if not self._field_matches_partial(current['role_holders'],
                                           original['role_holders'], applied_holders):
            raise RuntimeError('Assistant role changed during restore; refusing to clobber it')
        for compound, value in current['settings'].items():
            applied_value = None if applied is None else applied['settings'][compound]
            if not self._field_matches_partial(
                    value, original['settings'][compound], applied_value):
                raise RuntimeError(compound + ' changed during restore; refusing to clobber it')

    def restore(self):
        data = self._load()
        current = self._read_state()
        if data['state'] == 'restored' and current == data['original']:
            print('Assistant profile is already restored.')
            return data
        if data['state'] == 'ready' and current == data['original']:
            print('Assistant profile has not changed the device.')
            return data
        self._guard_restore(data, current)
        data['state'] = 'restoring'
        data['progress'] = []
        self._save(data)
        try:
            self._set_holders(data['original']['role_holders'])
            data['progress'] = ['role']
            self._save(data)
            for compound, saved in data['original']['settings'].items():
                if compound.startswith('secure:'):
                    self._put_setting(compound, saved)
            data['progress'].append('secure-settings')
            self._save(data)
            self._put_setting('global:' + POWER_KEY,
                              data['original']['settings']['global:' + POWER_KEY])
            data['progress'].append('power')
            self._save(data)
            if self._read_state() != data['original']:
                raise RuntimeError('Restore readback differs from the saved original state')
            data['state'] = 'restored'
            data['progress'].append('verified')
            data.pop('last_error', None)
            self._save(data)
            print('Restored the original Assistant role and power settings.')
            return data
        except Exception as error:
            data['last_error'] = str(error)
            self._save(data)
            raise

    def status(self):
        data = self._load() if self.snapshot.exists() else None
        current = self._read_state()
        result = {
            'device_serial': self.device.serial,
            'journal_state': None if data is None else data['state'],
            'matches_original': data is not None and current == data['original'],
            'matches_applied': data is not None and data.get('applied') is not None
            and current == data['applied'],
            'role_holders': current['role_holders'],
            'assistant': current['settings']['secure:assistant'],
            'power_button_long_press': current['settings']['global:' + POWER_KEY],
            'policy_long_press_assistant': self._policy_is_assistant(),
        }
        print(json.dumps(result, indent=2, sort_keys=True))
        return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('backup', 'apply', 'status', 'restore'))
    action = parser.parse_args(argv).action
    device = AdbDevice(mutation=action in ('apply', 'restore'))
    return getattr(AssistantProfile(device), action)()


if __name__ == '__main__':
    main()
