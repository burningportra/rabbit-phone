#!/usr/bin/env python3
"""Fetch the official Android/Temurin tools pinned by this repository."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import tarfile
import urllib.request
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
ROOT = Path(os.environ.get('RABBIT_TOOLCHAIN_DIR', PROJECT / '.toolchain')).expanduser().resolve()
MANIFEST = json.loads((PROJECT / 'toolchain-manifest.json').read_text())


def prepare(item):
    name, spec = item
    dest = ROOT / name.replace(';', '-')
    archive = ROOT / (name.replace(';', '-') + ('.tar.gz' if name == 'jdk' else '.zip'))
    algorithm = 'sha256' if len(spec['checksum']) == 64 else 'sha1'
    def digest():
        h = hashlib.new(algorithm)
        with archive.open('rb') as stream:
            for block in iter(lambda: stream.read(8 * 1024 * 1024), b''):
                h.update(block)
        return h.hexdigest()
    if not archive.exists() or archive.stat().st_size != spec['size'] or digest() != spec['checksum']:
        partial = archive.with_suffix(archive.suffix + '.part')
        request = urllib.request.Request(spec['url'], headers={'User-Agent': 'Rabbit-Phone-build'})
        with urllib.request.urlopen(request, timeout=60) as response, partial.open('wb') as output:
            shutil.copyfileobj(response, output, 1024 * 1024)
        partial.replace(archive)
    assert archive.stat().st_size == spec['size'], name + ': size mismatch'
    assert digest() == spec['checksum'], name + ': checksum mismatch'
    if not (dest / '.verified').exists():
        dest.mkdir(parents=True, exist_ok=True)
        if name == 'jdk':
            with tarfile.open(archive) as source:
                source.extractall(dest, filter='data')
        else:
            with zipfile.ZipFile(archive) as source:
                for entry in source.infolist():
                    target = (dest / entry.filename).resolve()
                    if not target.is_relative_to(dest.resolve()):
                        raise ValueError('Unsafe archive path')
                    file_mode = entry.external_attr >> 16
                    if stat.S_ISLNK(file_mode):
                        link = source.read(entry).decode('utf-8')
                        if not (target.parent / link).resolve().is_relative_to(dest.resolve()):
                            raise ValueError('Unsafe archive symlink')
                        target.parent.mkdir(parents=True, exist_ok=True)
                        os.symlink(link, target)
                        continue
                    source.extract(entry, dest)
                    mode = (entry.external_attr >> 16) & 0o777
                    if mode and target.is_file():
                        target.chmod(mode)
        (dest / '.verified').write_text(spec['checksum'] + '\n')
    print(name + ': downloaded, checksum verified, extracted', flush=True)


with ThreadPoolExecutor(max_workers=3) as pool:
    list(pool.map(prepare, MANIFEST.items()))

paths = {
    'java_home': str(next((ROOT / 'jdk').glob('*/Contents/Home'))),
    'android_jar': str(next((ROOT / 'platforms-android-36').rglob('android.jar'))),
    'build_tools': str(next((ROOT / 'build-tools-36.0.0').rglob('aapt2')).parent),
}
(ROOT / 'paths.json').write_text(json.dumps(paths, indent=2) + '\n')
print(json.dumps(paths, indent=2))
