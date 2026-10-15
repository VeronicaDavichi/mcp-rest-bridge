# -*- coding: utf-8 -*-
"""Parse the Lanhu /api/project/images response and list design name + image urls."""
import json, sys

path = r'C:\Users\EDY\AppData\Local\Temp\lanhu-images.network-response'
raw = open(path, 'rb').read()
txt = raw.decode('utf-8', 'replace')
data = json.loads(txt)


def walk(node, depth=0, out=None):
    """Collect every dict that looks like a design/image entry."""
    if out is None:
        out = []
    if isinstance(node, dict):
        keys = set(node.keys())
        if {'name'} & keys and ({'url'} & keys or {'image_url'} & keys or {'origin_url'} & keys or {'id'} & keys):
            out.append(node)
        for v in node.values():
            walk(v, depth + 1, out)
    elif isinstance(node, list):
        for v in node:
            walk(v, depth + 1, out)
    return out


print('top-level keys:', list(data.keys()) if isinstance(data, dict) else type(data))
entries = walk(data)
print('candidate entries:', len(entries))
seen = set()
for e in entries:
    name = e.get('name')
    if name in seen:
        continue
    seen.add(name)
    urlish = {k: v for k, v in e.items() if isinstance(v, str) and ('http' in v or v.endswith('.png') or v.endswith('.jpg'))}
    print('-' * 90)
    print('name=%r  id=%r  sector=%r  width=%r height=%r' % (
        name, e.get('id') or e.get('image_id'), e.get('sector_id') or e.get('sector_name'),
        e.get('width'), e.get('height')))
    for k, v in urlish.items():
        print('   %-16s %s' % (k, v))
