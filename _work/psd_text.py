# -*- coding: utf-8 -*-
"""Minimal PSD layer-name + text extractor (no external deps beyond stdlib)."""
import struct, sys, os, re, json

def read_psd(path):
    data = open(path, 'rb').read()
    assert data[:4] == b'8BPS', 'not a psd'
    pos = 26
    # color mode data
    n = struct.unpack('>I', data[pos:pos+4])[0]; pos += 4 + n
    # image resources
    n = struct.unpack('>I', data[pos:pos+4])[0]; pos += 4 + n
    # layer and mask info
    lm_len = struct.unpack('>I', data[pos:pos+4])[0]; lm_start = pos + 4
    p = lm_start
    layer_info_len = struct.unpack('>I', data[p:p+4])[0]; p += 4
    layer_count = struct.unpack('>h', data[p:p+2])[0]; p += 2
    layer_count = abs(layer_count)
    layers = []
    for i in range(layer_count):
        top, left, bottom, right = struct.unpack('>4i', data[p:p+16]); p += 16
        nch = struct.unpack('>H', data[p:p+2])[0]; p += 2
        p += nch * 6
        sig = data[p:p+4]; p += 4
        blend = data[p:p+4]; p += 4
        opacity = data[p]; p += 1
        clipping = data[p]; p += 1
        flags = data[p]; p += 1
        p += 1  # filler
        extra_len = struct.unpack('>I', data[p:p+4])[0]; p += 4
        extra_end = p + extra_len
        # layer mask data
        m = struct.unpack('>I', data[p:p+4])[0]; p += 4 + m
        # blending ranges
        b = struct.unpack('>I', data[p:p+4])[0]; p += 4 + b
        # pascal name (padded to 4)
        name_len = data[p]; p += 1
        name = data[p:p+name_len].decode('latin-1', 'replace'); p += name_len
        pad = (4 - ((1 + name_len) % 4)) % 4
        p += pad
        unicode_name = None
        tysh = None
        while p + 12 <= extra_end:
            sig2 = data[p:p+4]; p += 4
            if sig2 not in (b'8BIM', b'8B64'):
                break
            key = data[p:p+4]; p += 4
            ln = struct.unpack('>I', data[p:p+4])[0]; p += 4
            body = data[p:p+ln]
            if key == b'luni':
                slen = struct.unpack('>I', body[:4])[0]
                unicode_name = body[4:4+slen*2].decode('utf-16-be', 'replace')
            elif key == b'TySh':
                tysh = body
            p += ln + (ln % 2)
        p = extra_end
        layers.append(dict(index=i, bbox=[top, left, bottom, right], pascal=name,
                           name=unicode_name or name, tysh=tysh))
    return layers

def utf16_runs(blob, minlen=2):
    out = []
    for enc in ('utf-16-be',):
        i = 0; cur = []
        while i + 1 < len(blob):
            ch = blob[i:i+2]
            try:
                s = ch.decode(enc)
            except Exception:
                s = None
            if s and (s.isprintable() or s in '\r\n\t') and s != '\x00':
                cur.append(s); i += 2
            else:
                if len(cur) >= minlen:
                    out.append(''.join(cur))
                cur = []; i += 2
        if len(cur) >= minlen:
            out.append(''.join(cur))
    return out

def text_from_tysh(tysh):
    if not tysh:
        return None
    # EngineData text usually appears as UTF-16 in the descriptor "Txt " value
    runs = utf16_runs(tysh, 1)
    cand = [r for r in runs if re.search(r'[\u4e00-\u9fff]', r) or re.search(r'[A-Za-z0-9]', r)]
    # the text run is usually the longest CJK-ish/ascii run; join unique
    return cand

for path in sys.argv[1:]:
    print('=' * 100)
    print(os.path.basename(path))
    try:
        layers = read_psd(path)
    except Exception as e:
        print('  PARSE FAIL:', e); continue
    for L in layers:
        t, l, b, r = L['bbox']
        if b - t <= 0 or r - l <= 0:
            continue
        txts = text_from_tysh(L['tysh']) if L['tysh'] else None
        joined = ' | '.join(dict.fromkeys(txts)) if txts else ''
        joined = joined[:400]
        print(f"  [{L['index']:>3}] {l:>5},{t:>5} {r-l:>5}x{b-t:<5} name={L['name']!r}" + (f"  TEXT={joined!r}" if joined else ''))
