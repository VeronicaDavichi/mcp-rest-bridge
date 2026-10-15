# -*- coding: utf-8 -*-
"""Download all 17 Lanhu designs at native resolution."""
import json, urllib.request, os, io
from PIL import Image

path = r'C:\Users\EDY\AppData\Local\Temp\lanhu-images.network-response'
data = json.loads(open(path, 'rb').read().decode('utf-8', 'replace'))
images = data['data']['images']

OUT = r'E:\IdeaProjects\mcp-rest-bridge\_work\lanhu'
os.makedirs(OUT, exist_ok=True)

# keep the order the API returns, but name files with an index for stable reading
manifest = []
for idx, item in enumerate(images, 1):
    name = item['name']
    url = item['url']
    safe = ''.join(c if c not in '\\/:*?"<>|' else '_' for c in name)
    fname = '%02d-%s.png' % (idx, safe)
    dst = os.path.join(OUT, fname)
    if not os.path.exists(dst):
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0',
                                                   'Referer': 'https://lanhuapp.com/web/'})
        raw = urllib.request.urlopen(req, timeout=60).read()
        im = Image.open(io.BytesIO(raw)).convert('RGB')
        im.save(dst)
    else:
        im = Image.open(dst)
    manifest.append({'idx': idx, 'name': name, 'file': fname, 'w': im.width, 'h': im.height,
                     'image_id': item['id'], 'url': url})
    print('%02d  %-32s %-34s %dx%d' % (idx, name, fname, im.width, im.height))

with open(os.path.join(OUT, 'manifest.json'), 'w', encoding='utf-8') as f:
    json.dump(manifest, f, ensure_ascii=False, indent=2)
print('\ntotal=%d  ->  %s' % (len(manifest), OUT))
