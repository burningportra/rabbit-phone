#!/usr/bin/env python3
"""Patch the two Contacts empty-state style colors in a resources.arsc blob."""

import struct


RES_TABLE_TYPE = 0x0002
RES_TABLE_PACKAGE_TYPE = 0x0200
RES_TABLE_TYPE_TYPE = 0x0201
NO_ENTRY = 0xFFFFFFFF
FLAG_SPARSE = 0x01
FLAG_OFFSET16 = 0x02
FLAG_COMPLEX = 0x0001

CONTACTS_PACKAGE_ID = 0x7F
STYLE_TYPE_ID = 0x12
TEXT_COLOR_ATTR = 0x01010098
TYPE_REFERENCE = 0x01
TYPE_INT_COLOR_ARGB8 = 0x1C
TYPE_INT_COLOR_RGB8 = 0x1D

STYLE_PATCHES = {
    0x002: (TYPE_REFERENCE, 0x7F0603CB, TYPE_INT_COLOR_ARGB8, 0xFFFF5A1F),
    # The captured Contacts APK encodes opaque black as RGB8, although the
    # replacement includes an explicit alpha channel and is therefore ARGB8.
    0x154: (TYPE_INT_COLOR_RGB8, 0xFF000000, TYPE_INT_COLOR_ARGB8, 0xFFF5EFE1),
}


def _u16(data, offset, limit, field):
    if offset < 0 or offset + 2 > limit:
        raise ValueError(f'truncated resources.arsc while reading {field}')
    return struct.unpack_from('<H', data, offset)[0]


def _u32(data, offset, limit, field):
    if offset < 0 or offset + 4 > limit:
        raise ValueError(f'truncated resources.arsc while reading {field}')
    return struct.unpack_from('<I', data, offset)[0]


def _chunk(data, offset, limit, field):
    if offset < 0 or offset + 8 > limit:
        raise ValueError(f'truncated resources.arsc at {field} chunk header')
    chunk_type, header_size, chunk_size = struct.unpack_from('<HHI', data, offset)
    if header_size < 8 or chunk_size < header_size:
        raise ValueError(f'invalid {field} chunk sizes')
    if chunk_size > limit - offset:
        raise ValueError(f'truncated resources.arsc at {field} chunk body')
    return chunk_type, header_size, chunk_size


def _chunks(data, start, end, field):
    offset = start
    while offset < end:
        chunk_type, header_size, chunk_size = _chunk(data, offset, end, field)
        yield offset, chunk_type, header_size, chunk_size
        offset += chunk_size
    if offset != end:
        raise ValueError(f'invalid {field} child chunk boundary')


def _entry_offset(data, chunk_offset, header_size, chunk_size, entry_id):
    limit = chunk_offset + chunk_size
    flags = data[chunk_offset + 9]
    if flags & ~(FLAG_SPARSE | FLAG_OFFSET16):
        raise ValueError(f'unsupported style type index flags 0x{flags:02x}')
    if flags == (FLAG_SPARSE | FLAG_OFFSET16):
        raise ValueError('invalid style type index flags: sparse and offset16')

    entry_count = _u32(data, chunk_offset + 12, limit, 'type entry count')
    entries_start = _u32(data, chunk_offset + 16, limit, 'type entries start')
    if entries_start < header_size or entries_start > chunk_size:
        raise ValueError('invalid style type entries start')
    index_start = chunk_offset + header_size

    if flags & FLAG_SPARSE:
        index_size = entry_count * 4
        if index_size > entries_start - header_size:
            raise ValueError('sparse style index overlaps entry data')
        previous_id = -1
        encoded_offset = None
        for index in range(entry_count):
            item = index_start + index * 4
            item_id = _u16(data, item, limit, 'sparse entry id')
            item_offset = _u16(data, item + 2, limit, 'sparse entry offset')
            if item_id <= previous_id:
                raise ValueError('sparse style entry ids are not strictly increasing')
            previous_id = item_id
            if item_id == entry_id:
                encoded_offset = item_offset
        relative = None if encoded_offset is None else encoded_offset * 4
    elif flags & FLAG_OFFSET16:
        index_size = entry_count * 2
        if index_size > entries_start - header_size:
            raise ValueError('offset16 style index overlaps entry data')
        if entry_id >= entry_count:
            return None
        encoded_offset = _u16(
            data, index_start + entry_id * 2, limit, 'offset16 entry offset'
        )
        relative = None if encoded_offset == 0xFFFF else encoded_offset * 4
    else:
        index_size = entry_count * 4
        if index_size > entries_start - header_size:
            raise ValueError('dense style index overlaps entry data')
        if entry_id >= entry_count:
            return None
        encoded_offset = _u32(
            data, index_start + entry_id * 4, limit, 'dense entry offset'
        )
        relative = None if encoded_offset == NO_ENTRY else encoded_offset

    if relative is None:
        return None
    if relative & 0x3:
        raise ValueError('style entry offset is not four-byte aligned')
    absolute = chunk_offset + entries_start + relative
    if absolute < chunk_offset + entries_start or absolute + 8 > limit:
        raise ValueError('style entry offset is outside its type chunk')
    return absolute


def _find_text_color(data, type_offset, header_size, chunk_size, entry_id):
    entry = _entry_offset(data, type_offset, header_size, chunk_size, entry_id)
    if entry is None:
        return []
    limit = type_offset + chunk_size
    entry_size = _u16(data, entry, limit, 'style entry size')
    entry_flags = _u16(data, entry + 2, limit, 'style entry flags')
    if entry_size < 16 or entry + entry_size > limit:
        raise ValueError('invalid complex style entry size')
    if not entry_flags & FLAG_COMPLEX:
        raise ValueError(f'style 0x7f12{entry_id:04x} is not a complex entry')
    map_count = _u32(data, entry + 12, limit, 'style map count')
    position = entry + entry_size
    if map_count > (limit - position) // 12:
        raise ValueError('style map count exceeds its type chunk')
    matches = []
    for _ in range(map_count):
        name = _u32(data, position, limit, 'style map attribute')
        value_size = _u16(data, position + 4, limit, 'style map value size')
        if value_size < 8 or position + 4 + value_size > limit:
            raise ValueError('invalid style map value size')
        if data[position + 6] != 0:
            raise ValueError('style map Res_value has nonzero reserved byte')
        if name == TEXT_COLOR_ATTR:
            matches.append((position + 7, position + 8))
        position += 4 + value_size
    return matches


def patch_contact_styles(resources_arsc):
    """Return an ARSC blob with only the two verified Contacts style values changed."""
    if not isinstance(resources_arsc, bytes):
        raise TypeError('resources_arsc must be bytes')
    root_type, root_header, root_size = _chunk(
        resources_arsc, 0, len(resources_arsc), 'resource table'
    )
    if root_type != RES_TABLE_TYPE or root_header < 12:
        raise ValueError('resources.arsc does not start with a resource table')
    if root_size != len(resources_arsc):
        raise ValueError('resource table length does not match input length')
    expected_packages = _u32(resources_arsc, 8, root_header, 'package count')

    found_packages = 0
    matches = {entry_id: [] for entry_id in STYLE_PATCHES}
    for package_offset, chunk_type, header_size, chunk_size in _chunks(
        resources_arsc, root_header, root_size, 'table'
    ):
        if chunk_type != RES_TABLE_PACKAGE_TYPE:
            continue
        found_packages += 1
        package_limit = package_offset + chunk_size
        if header_size < 12:
            raise ValueError('resource package header is too small')
        package_id = _u32(
            resources_arsc, package_offset + 8, package_limit, 'package id'
        )
        if package_id != CONTACTS_PACKAGE_ID:
            continue
        for type_offset, child_type, type_header, type_size in _chunks(
            resources_arsc,
            package_offset + header_size,
            package_limit,
            'package',
        ):
            if child_type != RES_TABLE_TYPE_TYPE:
                continue
            if type_header < 24:
                raise ValueError('resource type header is too small')
            type_limit = type_offset + type_size
            config_size = _u32(
                resources_arsc, type_offset + 20, type_limit, 'type config size'
            )
            if config_size < 4 or 20 + config_size > type_header:
                raise ValueError('invalid resource type configuration size')
            if resources_arsc[type_offset + 8] != STYLE_TYPE_ID:
                continue
            for entry_id in STYLE_PATCHES:
                matches[entry_id].extend(
                    _find_text_color(
                        resources_arsc, type_offset, type_header, type_size, entry_id
                    )
                )

    if found_packages != expected_packages:
        raise ValueError(
            f'resource table package count mismatch: expected {expected_packages}, '
            f'found {found_packages}'
        )

    patched = bytearray(resources_arsc)
    for entry_id, (old_type, old_data, new_type, new_data) in STYLE_PATCHES.items():
        locations = matches[entry_id]
        if len(locations) != 1:
            raise ValueError(
                f'expected exactly one textColor map for style 0x7f12{entry_id:04x}, '
                f'found {len(locations)}'
            )
        type_offset, data_offset = locations[0]
        actual_type = resources_arsc[type_offset]
        actual_data = _u32(
            resources_arsc, data_offset, len(resources_arsc), 'style value data'
        )
        if (actual_type, actual_data) != (old_type, old_data):
            raise ValueError(
                f'unexpected textColor value for style 0x7f12{entry_id:04x}: '
                f'type=0x{actual_type:02x} data=0x{actual_data:08x}'
            )
        patched[type_offset] = new_type
        struct.pack_into('<I', patched, data_offset, new_data)

    if len(patched) != len(resources_arsc):
        raise AssertionError('resources.arsc patch changed its length')
    return bytes(patched)
