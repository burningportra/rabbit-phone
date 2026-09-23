"""Temporarily change animator scale while preserving Android's live default."""
from dataclasses import dataclass
import math
import re
import time


SETTING = 'animator_duration_scale'
PACKAGE = 'com.kevtrinh.rabbitphone'


def parse_scale(value):
    try:
        scale = float(value)
    except (TypeError, ValueError) as error:
        raise RuntimeError('Animator duration scale is not numeric') from error
    if not math.isfinite(scale) or scale < 0:
        raise RuntimeError('Animator duration scale must be finite and nonnegative')
    return scale


@dataclass(frozen=True)
class RuntimeScale:
    scale: float
    enabled: bool


def runtime_scale(device, package=PACKAGE):
    output = device.shell('dumpsys', 'activity', package + '/.HomeActivity', 'rabbit-navigation')
    scales = re.findall(r'^\s*rabbit_animator_duration_scale=(\S+)\s*$', output, re.MULTILINE)
    enabled = re.findall(r'^\s*rabbit_animators_enabled=(true|false)\s*$', output, re.MULTILINE)
    if len(scales) != 1 or len(enabled) != 1:
        raise RuntimeError('A live Rabbit Home animation diagnostic is required')
    return RuntimeScale(parse_scale(scales[0]), enabled[0] == 'true')


class AnimationScaleGuard:
    def __init__(self, device, package=PACKAGE, timeout=5):
        self.device = device
        self.package = package
        self.timeout = timeout
        self.last_set = None
        self.original = self.persisted()
        if self.original != 'null':
            parse_scale(self.original)
        self.original_runtime = runtime_scale(device, package)
        if self.persisted() != self.original:
            raise RuntimeError('Animator duration scale changed during capture')

    def persisted(self):
        return self.device.shell('settings', 'get', 'global', SETTING).strip()

    def wait_runtime(self, expected):
        deadline = time.monotonic() + self.timeout
        while True:
            actual = runtime_scale(self.device, self.package)
            if actual.enabled == expected.enabled and math.isclose(
                    actual.scale, expected.scale, rel_tol=1e-6, abs_tol=0):
                return
            if time.monotonic() >= deadline:
                raise RuntimeError('Rabbit Home did not receive the expected animator duration scale')
            time.sleep(.1)

    def set(self, value):
        value = str(value)
        scale = parse_scale(value)
        expected_current = self.original if self.last_set is None else self.last_set
        if self.persisted() != expected_current:
            raise RuntimeError('Animator duration scale changed outside this test')
        # Record intent first so a command that writes and then fails is still
        # recoverable, but only if its value actually reached SettingsProvider.
        self.last_set = value
        self.device.shell('settings', 'put', 'global', SETTING, value)
        self.wait_runtime(RuntimeScale(scale, scale > 0))

    def restore(self):
        current = self.persisted()
        if current == self.original:
            actual = runtime_scale(self.device, self.package)
            if actual == self.original_runtime and self.persisted() == self.original:
                return True
        if self.last_set is None or current != self.last_set:
            return False

        # Android 16 WMS uses its cached scale as the default when this setting
        # is absent. Deleting a test value alone leaves that value live. First
        # propagate the captured live scale, then restore the absent DB entry.
        target = (str(self.original_runtime.scale) if self.original == 'null'
                  else self.original)
        if self.persisted() != self.last_set:
            return False
        self.device.shell('settings', 'put', 'global', SETTING, target)
        self.wait_runtime(self.original_runtime)
        if self.persisted() != target:
            return False
        if self.original == 'null':
            self.device.shell('settings', 'delete', 'global', SETTING)
            self.wait_runtime(self.original_runtime)
        return self.persisted() == self.original
