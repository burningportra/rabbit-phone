#!/usr/bin/env python3
"""Apply/restore Rabbit colors through data-backed Android resource overlays."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

from device import require_evidence_serial, select_r1
from build_clock_overlay import build as build_clock

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / 'evidence/theme'
NAME = 'RabbitPhonePalette'
OVERLAY = 'com.android.shell:' + NAME
KEYS = [('secure', 'theme_customization_overlay_packages'),
        ('secure', 'lock_screen_custom_clock_face'), ('secure', 'ui_night_mode'),
        ('secure', 'lockscreen_use_double_line_clock')]


def palette():
    colors = {}
    shades = [0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000]
    ramps = {
        'neutral1': ['FFFFFF', 'FFF9EF', 'F5EFE1', 'E8E2D5', 'CDC7BA', 'B2AC9F',
                     '979185', '7D776C', '635E54', '48453D', '302E28', '1B1B18', '0A0A09'],
        'neutral2': ['FFFFFF', 'FFF9EF', 'F5EFE1', 'E9DFD0', 'CFC3B2', 'B4A897',
                     '998E7D', '7F7364', '655B4C', '4B4336', '342D24', '201B15', '0A0A09'],
        'accent1': ['FFFFFF', 'FFF8F5', 'FFEDE5', 'FFDCCB', 'FFB998', 'FF8B5D',
                    'FF6D37', 'FF5A1F', 'CC4515', '9A330E', '662209', '3B170B', '0A0A09'],
        'accent2': ['FFFFFF', 'FFF9EF', 'F5EFE1', 'EBDCCC', 'D3BEA7', 'BCA289',
                    'A3886E', '896F55', '70573E', '563F2B', '3E2A1A', '29180B', '0A0A09'],
        'accent3': ['FFFFFF', 'FFF8F5', 'FFEDE5', 'FFDCCB', 'FFB998', 'FF8B5D',
                    'FF6D37', 'FF5A1F', 'CC4515', '9A330E', '662209', '3B170B', '0A0A09'],
    }
    for family, values in ramps.items():
        for shade, color in zip(shades, values):
            colors[f'system_{family}_{shade}'] = color
    dark = {
        'primary': 'FF5A1F', 'on_primary': '0A0A09', 'primary_container': '512211',
        'on_primary_container': 'FFDCCB', 'secondary': 'D3BEA7', 'on_secondary': '0A0A09',
        'secondary_container': '3E2A1A', 'on_secondary_container': 'EBDCCC',
        'tertiary': 'FF8B5D', 'on_tertiary': '0A0A09', 'tertiary_container': '512211',
        'on_tertiary_container': 'FFDCCB', 'background': '0A0A09', 'on_background': 'F5EFE1',
        'surface': '0A0A09', 'on_surface': 'F5EFE1', 'surface_container_low': '141412',
        'surface_container_lowest': '0A0A09', 'surface_container': '1B1B18',
        'surface_container_high': '242420', 'surface_container_highest': '302E28',
        'surface_bright': '302E28', 'surface_dim': '0A0A09', 'surface_variant': '302E28',
        'on_surface_variant': 'CDC7BA', 'outline': '979185', 'outline_variant': '48453D',
        'inverse_surface': 'F5EFE1', 'inverse_on_surface': '1B1B18',
        'inverse_primary': '9A330E', 'control_activated': 'FF5A1F',
        'control_normal': 'CDC7BA', 'control_highlight': '40403B',
    }
    for name, color in dark.items():
        colors['system_' + name + '_dark'] = color
    return colors


class Profile:
    def __init__(self):
        self.serial = select_r1(mutation=True)

    def adb(self, *args):
        return subprocess.check_output(['adb', '-s', self.serial, *args],
                                       text=True, timeout=30).strip()

    def shell(self, *args):
        return self.adb('shell', shlex.join(args))

    def backup(self):
        EVIDENCE.mkdir(parents=True, exist_ok=True)
        path = EVIDENCE / 'before.json'
        if path.exists():
            data = json.loads(path.read_text())
            require_evidence_serial(data, self.serial, path)
            return data
        if self.shell('id', '-u') != '0':
            raise RuntimeError('The theme profile needs the tested root ADB installation')
        data = {'device_serial': self.serial, 'created': datetime.datetime.now().isoformat(),
                'settings': {}, 'overlays': self.shell('cmd', 'overlay', 'list'),
                'wallpaper': self.shell('dumpsys', 'wallpaper'), 'wallpaper_files': []}
        if OVERLAY in data['overlays']:
            raise RuntimeError('Rabbit palette already exists without its original backup')
        for namespace, key in KEYS:
            data['settings'][namespace + ':' + key] = self.shell('settings', 'get', namespace, key)
        files = self.shell('find', '/data/system/users/0', '-maxdepth', '1', '-name',
                           'wallpaper*', '-type', 'f').splitlines()
        for remote in files:
            self.adb('pull', remote, str(EVIDENCE / Path(remote).name))
        data['wallpaper_files'] = files
        path.write_text(json.dumps(data, indent=2) + '\n')
        path.chmod(0o600)
        return data

    def apply(self):
        self.backup()
        theme = {'android.theme.customization.system_palette': 'FF5A1F',
                 'android.theme.customization.accent_color': 'FF5A1F',
                 'android.theme.customization.color_source': 'preset',
                 'android.theme.customization.theme_style': 'TONAL_SPOT'}
        self.shell('settings', 'put', 'secure', 'theme_customization_overlay_packages',
                   json.dumps(theme, separators=(',', ':')))
        self.shell('settings', 'put', 'secure', 'lock_screen_custom_clock_face',
                   json.dumps({'clockId': 'DEFAULT', 'seedColor': 0xffff5a1f - 2**32}))
        self.shell('cmd', 'uimode', 'night', 'yes')
        self.shell('settings', 'put', 'secure', 'lockscreen_use_double_line_clock', '0')
        self.apply_clock(reapply_palette=False)
        self.shell('cmd', 'overlay', 'fabricate', '--target', 'android', '--name',
                   'RabbitPhoneLegacyBar', '--config', 'hdpi',
                   'android:drawable/ab_solid_light_holo', 'color', '0xff0a0a09')
        self.shell('cmd', 'overlay', 'enable', '--user', '0', 'com.android.shell:RabbitPhoneLegacyBar')
        included, skipped = [], []
        for name, value in palette().items():
            try:
                self.shell('cmd', 'overlay', 'lookup', 'android', 'android:color/' + name)
            except subprocess.CalledProcessError:
                skipped.append(name)
                continue
            included.append(name)
        self.overlay('android', NAME, {name: 'FF' + palette()[name] for name in included})
        receipt = {'status': 'applied', 'colors_verified': len(included), 'unsupported_colors_skipped': skipped,
                   'overlay': OVERLAY, 'selinux': self.shell('getenforce')}
        (EVIDENCE / 'applied.json').write_text(json.dumps(receipt, indent=2) + '\n')
        print(json.dumps(receipt, indent=2))
        self.apply_apps()

    def restart_system_ui(self):
        pid = self.shell('pidof', 'com.android.systemui')
        if not pid.isdigit():
            raise RuntimeError('Expected one SystemUI process for clock recreation')
        self.shell('kill', '-TERM', pid)
        time.sleep(3)

    def apply_clock(self, reapply_palette=True):
        self.backup()
        old_spacing = self.shell('cmd', 'overlay', 'lookup', 'com.android.systemui',
                                 'com.android.systemui:dimen/keyguard_clock_line_spacing_scale')
        old_size = self.shell('cmd', 'overlay', 'lookup', 'com.android.systemui',
                              'com.android.systemui:dimen/small_clock_text_size')
        jar = build_clock()
        remote = '/data/local/rabbit-phone/clock-overlay.jar'
        self.shell('mkdir', '-p', '/data/local/rabbit-phone')
        self.shell('chmod', '700', '/data/local/rabbit-phone')
        self.adb('push', str(jar), remote)
        self.shell('chmod', '600', remote)
        if self.shell('sha256sum', remote).split()[0] != hashlib.sha256(jar.read_bytes()).hexdigest():
            raise RuntimeError('Clock utility copy did not match')
        print(self.shell('env', 'CLASSPATH=' + remote, 'app_process', '/system/bin', 'ClockOverlay'))
        value = self.shell('cmd', 'overlay', 'lookup', 'com.android.systemui',
                           'com.android.systemui:dimen/keyguard_clock_line_spacing_scale')
        if float(value) != 1.0:
            raise RuntimeError('Clock spacing resource did not apply')
        if old_spacing != '1.0' or old_size != '68.0dip':
            self.restart_system_ui()
            receipt = EVIDENCE / 'applied.json'
            if reapply_palette and receipt.exists() and json.loads(receipt.read_text()).get('status', 'applied') == 'applied':
                self.overlay('android', NAME, {name: 'FF' + value for name, value in palette().items()})

    def overlay(self, package, name, colors, drawables=None):
        root = ET.Element('overlay')
        for resource, value in colors.items():
            # An existing night-qualified value otherwise outranks the default.
            for config in ['', 'night']:
                attributes = {'target': 'color/' + resource, 'value': '0x' + value}
                if config:
                    attributes['config'] = config
                ET.SubElement(root, 'item', **attributes)
        xml = EVIDENCE / (name + '.xml')
        ET.ElementTree(root).write(xml, encoding='utf-8', xml_declaration=True)
        # The parser opens XML in system_server, which cannot read shell_data_file.
        # Use a private, normally labelled system-data directory; never relax SELinux.
        directory = '/data/system/rabbit-phone-theme'
        self.shell('mkdir', '-p', directory)
        self.shell('chown', 'system:system', directory)
        self.shell('chmod', '700', directory)
        self.shell('restorecon', directory)
        for resource, relative in (drawables or {}).items():
            source = (ROOT / 'theme' / relative).resolve()
            if not source.is_relative_to((ROOT / 'theme/assets').resolve()):
                raise RuntimeError('Drawable must be a reviewed asset under theme/assets')
            # idmap2d consumes binary assets as transferred file descriptors and
            # may only read the normal resource-cache label, not system_data_file.
            target = '/data/resource-cache/rabbit-phone-' + source.name
            self.adb('push', str(source), target)
            self.shell('chown', 'system:system', target)
            self.shell('chmod', '600', target)
            self.shell('restorecon', target)
            for config in ('hdpi', 'night-hdpi'):
                ET.SubElement(root, 'item', target='drawable/' + resource,
                              value=target, config=config)
        ET.ElementTree(root).write(xml, encoding='utf-8', xml_declaration=True)
        remote = directory + '/' + name + '.xml'
        self.adb('push', str(xml), remote)
        self.shell('chown', 'system:system', remote)
        self.shell('chmod', '600', remote)
        self.shell('restorecon', remote)
        self.shell('cmd', 'overlay', 'fabricate', '--target', package, '--name', name,
                   '--file', remote)
        self.shell('cmd', 'overlay', 'enable', '--user', '0', 'com.android.shell:' + name)
        # Keep the reviewed data XML for the finite boot-time reapplication.
        for resource, expected in colors.items():
            actual = self.shell('cmd', 'overlay', 'lookup', package, package + ':color/' + resource)
            if actual.lower() != '#' + expected.lower():
                raise RuntimeError(f'{package}:{resource}: unexpected applied color {actual}')

    def apply_apps(self):
        self.backup()
        apps = json.loads((ROOT / 'theme/apps.json').read_text())
        receipts = {}
        for package, config in apps.items():
            for resource in config['colors']:
                self.shell('cmd', 'overlay', 'lookup', package, package + ':color/' + resource)
            self.overlay(package, config['overlay_name'], config['colors'], config.get('drawables'))
            receipts[package] = len(config['colors'])
        (EVIDENCE / 'apps-applied.json').write_text(json.dumps(receipts, indent=2) + '\n')
        print(json.dumps(receipts, indent=2))
        self.shell('touch', '/data/system/rabbit-phone-theme/enabled')

    def restore(self):
        path = EVIDENCE / 'before.json'
        data = json.loads(path.read_text())
        require_evidence_serial(data, self.serial, path)
        self.shell('rm', '-f', '/data/system/rabbit-phone-theme/enabled')
        self.shell('setprop', 'ctl.stop', 'rabbit-phone-theme')
        for _ in range(40):
            state = self.shell('getprop', 'init.svc.rabbit-phone-theme')
            if state in ('', 'stopped'):
                break
            time.sleep(.1)
        else:
            raise RuntimeError('Theme service did not stop; refusing a racing restore')
        overlays = self.shell('cmd', 'overlay', 'list')
        apps = json.loads((ROOT / 'theme/apps.json').read_text())
        names = [OVERLAY, 'com.android.shell:RabbitPhoneLegacyBar', 'com.android.shell:RabbitPhoneClock'] + [
            'com.android.shell:' + app['overlay_name'] for app in apps.values()]
        for name in names:
            if name in overlays:
                self.shell('cmd', 'overlay', 'disable', '--user', '0', name)
        for compound, value in data['settings'].items():
            namespace, key = compound.split(':', 1)
            if value == 'null':
                self.shell('settings', 'delete', namespace, key)
            else:
                self.shell('settings', 'put', namespace, key, value)
        night = data['settings'].get('secure:ui_night_mode')
        if night in {'0', '1', '2'}:
            self.shell('cmd', 'uimode', 'night', {'0': 'auto', '1': 'no', '2': 'yes'}[night])
        if 'com.android.shell:RabbitPhoneClock' in overlays:
            self.restart_system_ui()
        receipt = EVIDENCE / 'applied.json'
        if receipt.exists():
            restored = json.loads(receipt.read_text())
            restored['status'] = 'restored'
            receipt.write_text(json.dumps(restored, indent=2) + '\n')
        print('Restored palette and clock settings. Wallpaper backup is retained in evidence/theme.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['backup', 'apply', 'apply-apps', 'apply-clock', 'restore'])
    getattr(Profile(), parser.parse_args().action.replace('-', '_'))()
