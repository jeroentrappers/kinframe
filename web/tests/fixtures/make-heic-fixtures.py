#!/usr/bin/env python3
"""Makes the HEIC test fixtures (synthetic, no photos): needs ImageMagick 7 (`magick`) and libvips (`vips`), both
built with libheif. Run from web/:  python3 tests/fixtures/make-heic-fixtures.py

- heic-rotated.heic: 4000x3000, left half red, right half blue, with an `irot` property of 90 degrees
  anti-clockwise, so it is shown 3000x4000 with the red half at the bottom.
- heic-multi.heic: two images, 600x400 red and 400x600 blue, with the blue one marked as primary.
"""
import os
import struct
import subprocess
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))


def boxes(d, start, end):
    at = start
    while at + 8 <= end:
        size, kind = struct.unpack('>I4s', d[at:at + 8])
        if size == 0:
            size = end - at
        yield kind.decode('latin1'), at, at + size
        at += size


def find(d, start, end, kind, skip=0):
    for k, s, e in boxes(d, start + skip, end):
        if k == kind:
            return s, e
    raise KeyError(kind)


def grow(d, at, n):
    """Adds n to the 32-bit size of the box starting at `at`."""
    (size,) = struct.unpack('>I', d[at:at + 4])
    d[at:at + 4] = struct.pack('>I', size + n)


def shift_iloc(d, iloc_start, iloc_end, delta):
    at = iloc_start + 8
    version = d[at]
    at += 4
    sizes = d[at] << 8 | d[at + 1]
    off_size, len_size, base_size, idx_size = sizes >> 12, (sizes >> 8) & 15, (sizes >> 4) & 15, sizes & 15
    at += 2
    count_size = 2 if version < 2 else 4
    count = int.from_bytes(d[at:at + count_size], 'big')
    at += count_size

    def field(n):
        nonlocal at
        v = int.from_bytes(d[at:at + n], 'big') if n else 0
        at += n
        return v

    for _ in range(count):
        field(2 if version < 2 else 4)  # item_ID
        method = field(2) & 15 if version in (1, 2) else 0
        field(2)  # data_reference_index
        base_at = at
        base = field(base_size)
        extents = field(2)
        for _ in range(extents):
            if version in (1, 2) and idx_size:
                field(idx_size)
            off_at = at
            off = field(off_size)
            field(len_size)
            if method == 0 and base == 0 and off_size:
                d[off_at:off_at + off_size] = (off + delta).to_bytes(off_size, 'big')
        if method == 0 and base and base_size:
            d[base_at:base_at + base_size] = (base + delta).to_bytes(base_size, 'big')


def add_irot(path_in, path_out, angle):
    d = bytearray(open(path_in, 'rb').read())
    meta = find(d, 0, len(d), 'meta')
    pitm = find(d, *meta, 'pitm', skip=12)
    primary = struct.unpack('>H', d[pitm[0] + 12:pitm[0] + 14])[0]
    iprp = find(d, *meta, 'iprp', skip=12)
    ipco = find(d, *iprp, 'ipco', skip=8)
    ipma = find(d, *iprp, 'ipma', skip=8)
    index = sum(1 for _ in boxes(d, ipco[0] + 8, ipco[1])) + 1
    assert d[ipma[0] + 11] & 1 == 0 and index < 128
    # Associate the new property with the primary item (ipma comes after ipco: patch it first).
    at = ipma[0] + 12
    count = struct.unpack('>I', d[at:at + 4])[0]
    at += 4
    for _ in range(count):
        item = struct.unpack('>H', d[at:at + 2])[0]
        n = d[at + 2]
        if item == primary:
            d[at + 2] = n + 1
            d[at + 3 + n:at + 3 + n] = bytes([0x80 | index])  # essential
            break
        at += 3 + n
    else:
        raise KeyError('primary not in ipma')
    irot = struct.pack('>I4sB', 9, b'irot', angle & 3)
    d[ipco[1]:ipco[1]] = irot
    grow(d, ipma[0] + len(irot), 1)
    grow(d, ipco[0], len(irot))
    grow(d, iprp[0], len(irot) + 1)
    grow(d, meta[0], len(irot) + 1)
    iloc = find(d, meta[0], meta[1] + len(irot) + 1, 'iloc', skip=12)
    shift_iloc(d, *iloc, len(irot) + 1)
    open(path_out, 'wb').write(d)


def set_primary(path_in, path_out, item_index):
    d = bytearray(open(path_in, 'rb').read())
    meta = find(d, 0, len(d), 'meta')
    iinf = find(d, *meta, 'iinf', skip=12)
    ids = []
    for k, s, e in boxes(d, iinf[0] + 14, iinf[1]):
        if k == 'infe' and d[s + 8] >= 2:
            item = struct.unpack('>H', d[s + 12:s + 14])[0]
            if d[s + 16:s + 20] == b'hvc1':
                ids.append(item)
    pitm = find(d, *meta, 'pitm', skip=12)
    d[pitm[0] + 12:pitm[0] + 14] = struct.pack('>H', ids[item_index])
    open(path_out, 'wb').write(d)


with tempfile.TemporaryDirectory() as tmp:
    t = lambda name: os.path.join(tmp, name)
    run = lambda *a: subprocess.run(a, check=True)
    run('magick', '-size', '2000x3000', 'xc:#d02020', '-size', '2000x3000', 'xc:#2020d0', '+append', t('halves.png'))
    run('vips', 'heifsave', t('halves.png'), t('halves.heic'), '--Q', '40', '--bitdepth', '8')
    add_irot(t('halves.heic'), os.path.join(HERE, 'heic-rotated.heic'), 1)
    run('magick', '-size', '600x400', 'xc:#d02020', t('a.png'))
    run('magick', '-size', '400x600', 'xc:#2020d0', t('b.png'))
    run('magick', t('a.png'), t('b.png'), '-depth', '8', t('multi.heic'))
    set_primary(t('multi.heic'), os.path.join(HERE, 'heic-multi.heic'), 1)
