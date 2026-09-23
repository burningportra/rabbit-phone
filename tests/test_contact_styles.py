"""Host-only tests for the bounded Contacts resources.arsc style patch."""

import struct
import sys
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from contact_styles import patch_contact_styles  # noqa: E402


def complex_entry(value_type, value_data):
    entry = struct.pack('<HHIII', 16, 1, 0, 0, 1)
    value = struct.pack('<IHBBI', 0x01010098, 8, 0, value_type, value_data)
    return entry + value


def resource_table(package_id=0x7F, style_type=0x12, text_type=0x1D,
                   text_data=0xFF000000):
    entries = {
        0x002: complex_entry(0x01, 0x7F0603CB),
        0x154: complex_entry(text_type, text_data),
    }
    entry_count = 0x155
    index = [0xFFFF] * entry_count
    entry_data = bytearray()
    value_offsets = {}
    for entry_id, entry in entries.items():
        index[entry_id] = len(entry_data) // 4
        value_offsets[entry_id] = len(entry_data) + 20
        entry_data.extend(entry)
    index_data = struct.pack('<' + 'H' * entry_count, *index)
    type_header_size = 24
    entries_start = (type_header_size + len(index_data) + 3) & ~3
    type_size = entries_start + len(entry_data)
    type_chunk = bytearray(struct.pack(
        '<HHIBBHIII', 0x0201, type_header_size, type_size, style_type, 0x02,
        0, entry_count, entries_start, 4
    ))
    type_chunk.extend(index_data)
    type_chunk.extend(b'\0' * (entries_start - len(type_chunk)))
    type_chunk.extend(entry_data)

    package_header_size = 288
    package_size = package_header_size + len(type_chunk)
    package = bytearray(package_header_size)
    struct.pack_into('<HHII', package, 0, 0x0200, package_header_size,
                     package_size, package_id)
    package.extend(type_chunk)

    table_size = 12 + len(package)
    table = bytearray(struct.pack('<HHII', 0x0002, 12, table_size, 1))
    table.extend(package)
    absolute_entries = 12 + package_header_size + entries_start
    absolute_values = {
        entry_id: absolute_entries + offset
        for entry_id, offset in value_offsets.items()
    }
    return bytes(table), absolute_values


class ContactStylePatchTests(unittest.TestCase):
    def test_only_expected_typed_value_bytes_change(self):
        original, values = resource_table()
        patched = patch_contact_styles(original)
        self.assertEqual(len(patched), len(original))
        self.assertEqual(
            (patched[values[0x002] + 3],
             struct.unpack_from('<I', patched, values[0x002] + 4)[0]),
            (0x1C, 0xFFFF5A1F),
        )
        self.assertEqual(
            (patched[values[0x154] + 3],
             struct.unpack_from('<I', patched, values[0x154] + 4)[0]),
            (0x1C, 0xFFF5EFE1),
        )
        allowed = {
            *(values[0x002] + offset for offset in range(3, 8)),
            *(values[0x154] + offset for offset in range(3, 8)),
        }
        changed = {
            offset for offset, (before, after) in enumerate(zip(original, patched))
            if before != after
        }
        self.assertEqual(changed, {
            offset for offset in allowed if original[offset] != patched[offset]
        })
        self.assertEqual(len(changed), 9)

    def test_rejects_wrong_package(self):
        original, _ = resource_table(package_id=0x80)
        with self.assertRaisesRegex(ValueError, 'style 0x7f120002, found 0'):
            patch_contact_styles(original)

    def test_rejects_wrong_style_type(self):
        original, _ = resource_table(style_type=0x11)
        with self.assertRaisesRegex(ValueError, 'style 0x7f120002, found 0'):
            patch_contact_styles(original)

    def test_rejects_unexpected_typed_value(self):
        original, _ = resource_table(text_type=0x1C)
        with self.assertRaisesRegex(
            ValueError, 'style 0x7f120154: type=0x1c data=0xff000000'
        ):
            patch_contact_styles(original)

    def test_rejects_unexpected_value_data(self):
        original, _ = resource_table(text_data=0xFF010101)
        with self.assertRaisesRegex(
            ValueError, 'style 0x7f120154: type=0x1d data=0xff010101'
        ):
            patch_contact_styles(original)

    def test_rejects_truncated_tables(self):
        original, _ = resource_table()
        for truncated in (b'', original[:7], original[:11], original[:-1]):
            with self.subTest(length=len(truncated)):
                with self.assertRaisesRegex(ValueError, 'truncated|length'):
                    patch_contact_styles(truncated)


if __name__ == '__main__':
    unittest.main()
