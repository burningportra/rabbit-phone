#!/usr/bin/env python3
"""Build verified Contacts empty-state layouts as standalone binary XML files."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[1]
LAYOUTS = (
    'res/layout/empty_account_view.xml',
    'res/layout/empty_group_view.xml',
    'res/layout-land/empty_group_view.xml',
    'res/layout/empty_home_view.xml',
)
RESOURCE_IDS = {
    '0x7f06008b': 'color/empty_state_background',
    '0x7f070104': 'dimen/empty_account_view_text_padding_top',
    '0x7f070105': 'dimen/empty_group_view_image_padding_top',
    '0x7f070106': 'dimen/empty_group_view_text_padding_top',
    '0x7f070108': 'dimen/empty_home_view_text_padding_top',
    '0x7f070109': 'dimen/empty_view_image_height',
    '0x7f080077': 'drawable/accounts_empty',
    '0x7f08008f': 'drawable/home_empty',
    '0x7f0800b3': 'drawable/label_empty',
    '0x7f09005b': 'id/add_contact_button',
    '0x7f09005d': 'id/add_member_button',
    '0x7f090119': 'id/empty_account',
    '0x7f09011a': 'id/empty_account_image',
    '0x7f09011b': 'id/empty_account_view_text',
    '0x7f09011d': 'id/empty_group',
    '0x7f09011e': 'id/empty_group_image',
    '0x7f09011f': 'id/empty_group_view_text',
    '0x7f090120': 'id/empty_home',
    '0x7f090121': 'id/empty_home_image',
    '0x7f090122': 'id/empty_home_view_text',
    '0x7f0c005e': 'layout/empty_account_view',
    '0x7f0c005f': 'layout/empty_group_view',
    '0x7f0c0060': 'layout/empty_home_view',
    '0x7f110124': 'string/emptyAccount',
    '0x7f110125': 'string/emptyGroup',
    '0x7f110126': 'string/emptyMainList',
    '0x7f1101bd': 'string/menu_addContactsToGroup',
    '0x7f1101bf': 'string/menu_addToGroup',
    '0x7f120002': 'style/AddContactsButtonStyle',
    '0x7f120154': 'style/EmptyStateTextStyle',
}


def run(args, *, capture=False):
    printable = ' '.join(str(arg) for arg in args)
    print(f'+ {printable}', flush=True)
    result = subprocess.run(
        [str(arg) for arg in args],
        check=True,
        cwd=ROOT,
        env=os.environ,
        text=capture,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None,
    )
    return result.stdout if capture else None


def normalized_tree(tree):
    """Drop source line numbers and the two intentional color overrides."""
    lines = []
    for line in tree.splitlines():
        line = re.sub(r' \(line=\d+\)', '', line.rstrip())
        if ':textColor(' in line:
            continue
        lines.append(line)
    return lines


def validate_contacts_apk(aapt2, contacts_apk):
    resources = run([aapt2, 'dump', 'resources', contacts_apk], capture=True)
    missing = [
        f'{resource_id} {name}'
        for resource_id, name in RESOURCE_IDS.items()
        if f'resource {resource_id} {name}' not in resources
    ]
    if missing:
        raise RuntimeError(
            'Contacts APK resource contract does not match this layout build:\n  '
            + '\n  '.join(missing)
        )


def dump_tree(aapt2, apk, layout):
    return run([aapt2, 'dump', 'xmltree', apk, '--file', layout], capture=True)


def verify_compiled_layouts(aapt2, contacts_apk, staging_apk):
    for layout in LAYOUTS:
        original = dump_tree(aapt2, contacts_apk, layout)
        compiled = dump_tree(aapt2, staging_apk, layout)
        if normalized_tree(original) != normalized_tree(compiled):
            raise RuntimeError(f'Compiled layout contract differs from Contacts APK: {layout}')
        if compiled.count(':textColor(0x01010098)=#fff5efe1') != 1:
            raise RuntimeError(f'Missing warm-white explanatory text in {layout}')
        if compiled.count(':textColor(0x01010098)=#ffff5a1f') != 1:
            raise RuntimeError(f'Missing orange action text in {layout}')


def build(contacts_apk, output):
    toolchain = Path(
        os.environ.get('RABBIT_TOOLCHAIN_DIR', ROOT / '.toolchain')
    ).expanduser().resolve()
    paths = json.loads((toolchain / 'paths.json').read_text())
    aapt2 = Path(paths['build_tools']) / 'aapt2'
    android_jar = Path(paths['android_jar'])
    layouts = ROOT / 'theme/layouts'

    if not contacts_apk.is_file():
        raise FileNotFoundError(f'Contacts APK not found: {contacts_apk}')
    validate_contacts_apk(aapt2, contacts_apk)

    output = output.resolve()
    build_root = (ROOT / 'build').resolve()
    if output == build_root or not output.is_relative_to(build_root):
        raise RuntimeError('Layout output must be a subdirectory of this repository build directory')
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)

    with tempfile.TemporaryDirectory(prefix='rabbit-theme-layouts-') as temp_name:
        temp = Path(temp_name)
        manifest = temp / 'AndroidManifest.xml'
        manifest.write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    package="com.android.contacts">\n'
            '    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35" />\n'
            '</manifest>\n'
        )
        compiled_zip = temp / 'compiled.zip'
        staging_apk = temp / 'contacts-layouts.apk'
        run([aapt2, 'compile', '--dir', layouts, '-o', compiled_zip])
        run([
            aapt2,
            'link',
            '-o',
            staging_apk,
            '-I',
            android_jar,
            '-I',
            contacts_apk,
            '--manifest',
            manifest,
            '--min-sdk-version',
            '26',
            '--target-sdk-version',
            '35',
            compiled_zip,
        ])
        verify_compiled_layouts(aapt2, contacts_apk, staging_apk)

        with zipfile.ZipFile(staging_apk) as archive:
            for layout in LAYOUTS:
                destination = output / layout
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(archive.read(layout))

    for layout in LAYOUTS:
        path = output / layout
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        print(f'{path}: {path.stat().st_size} bytes sha256={digest}')
    receipt = {'schema': 1,
               'source_apk_sha256': hashlib.sha256(contacts_apk.read_bytes()).hexdigest(),
               'layouts': {layout: hashlib.sha256((output / layout).read_bytes()).hexdigest()
                           for layout in LAYOUTS},
               'source_layouts': {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                                  for path in sorted(layouts.rglob('*.xml'))}}
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        '--contacts-apk',
        type=Path,
        default=ROOT / 'evidence/theme/apks/com.android.contacts.apk',
        help='Contacts APK whose resource IDs and original trees must match',
    )
    parser.add_argument(
        '--output',
        type=Path,
        default=ROOT / 'build/theme-layouts',
        help='directory for extracted binary XML files',
    )
    args = parser.parse_args()
    print(build(args.contacts_apk.resolve(), args.output))


if __name__ == '__main__':
    main()
