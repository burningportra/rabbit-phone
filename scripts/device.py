#!/usr/bin/env python3
"""Select the intended Rabbit R1 without embedding a private device serial."""
import os
import subprocess


def _connected_devices():
    result = subprocess.run(['adb', 'devices'], capture_output=True, text=True,
                            timeout=10, check=True)
    devices = {}
    for line in result.stdout.splitlines()[1:]:
        fields = line.split()
        if len(fields) >= 2:
            devices[fields[0]] = fields[1]
    return devices


def _property(serial, name):
    return subprocess.check_output(
        ['adb', '-s', serial, 'shell', 'getprop', name],
        text=True, stderr=subprocess.DEVNULL, timeout=10).strip()


def select_r1(mutation=False):
    """Return one connected R1 serial; mutations additionally require Android 16."""
    devices = _connected_devices()
    requested = os.environ.get('RABBIT_ADB_SERIAL', '').strip()
    if requested:
        if devices.get(requested) != 'device':
            raise RuntimeError('RABBIT_ADB_SERIAL is not connected and authorized: ' + requested)
        candidates = [requested]
    else:
        candidates = []
        for serial, state in devices.items():
            if state == 'device' and _property(serial, 'ro.product.device') == 'r1':
                candidates.append(serial)
        if len(candidates) != 1:
            raise RuntimeError(
                'Expected exactly one connected Rabbit R1; found ' + str(len(candidates))
                + '. Set RABBIT_ADB_SERIAL to choose explicitly.')

    serial = candidates[0]
    product = _property(serial, 'ro.product.device')
    if product != 'r1':
        raise RuntimeError('Selected device is ' + repr(product) + ', not Rabbit R1')
    if mutation:
        release = _property(serial, 'ro.build.version.release')
        if release != '16':
            raise RuntimeError('Refusing mutation: selected R1 runs Android ' + repr(release)
                               + ', expected Android 16')
    return serial


def require_evidence_serial(data, serial, source):
    recorded = data.get('device_serial')
    if not recorded:
        raise RuntimeError(str(source) + ' has no device_serial; refusing device-specific restore')
    if recorded != serial:
        raise RuntimeError(str(source) + ' belongs to a different device; refusing restore')
