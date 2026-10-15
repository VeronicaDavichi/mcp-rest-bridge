# -*- coding: utf-8 -*-
"""Dump the full field set of one design entry from the Lanhu images API."""
import json

path = r'C:\Users\EDY\AppData\Local\Temp\lanhu-images.network-response'
data = json.loads(open(path, 'rb').read().decode('utf-8', 'replace'))

doc = data['data']
print('data keys:', list(doc.keys()) if isinstance(doc, dict) else type(doc))

# locate the list of images
def find_lists(node, path='data'):
    if isinstance(node, dict):
        for k, v in node.items():
            yield from find_lists(v, path + '.' + k)
    elif isinstance(node, list):
        yield path, node
        for i, v in enumerate(node[:1]):
            yield from find_lists(v, path + '[0]')

for p, lst in find_lists(doc):
    print('list at %s  len=%d' % (p, len(lst)))

def all_entries(node, out=None):
    if out is None:
        out = []
    if isinstance(node, dict):
        if 'name' in node and ('id' in node or 'image_id' in node):
            out.append(node)
        for v in node.values():
            all_entries(v, out)
    elif isinstance(node, list):
        for v in node:
            all_entries(v, out)
    return out

entries = all_entries(doc)
print('entries=%d' % len(entries))
home = [e for e in entries if e.get('name') == '首页'][0]
print('=' * 90)
print('FULL ENTRY for 首页:')
print(json.dumps(home, ensure_ascii=False, indent=2)[:4000])
