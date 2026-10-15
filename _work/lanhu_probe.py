# -*- coding: utf-8 -*-
"""Probe whether the Lanhu cover object can be fetched at full resolution."""
import json, urllib.request, io, os
from PIL import Image

path = r'C:\Users\EDY\AppData\Local\Temp\lanhu-images.network-response'
data = json.loads(open(path, 'rb').read().decode('utf-8', 'replace'))
images = data['data']['images']
print('designs:', len(images))

sample = [i for i in images if i['name'] == '首页'][0]
base = sample['url']
print('cover url:', base)

variants = [
    ('raw(no oss-process)', base),
    ('w_1920 webp', base + '?x-oss-process=image/quality,q_100/resize,w_1920/format,webp'),
    ('w_1920 png', base + '?x-oss-process=image/quality,q_100/resize,w_1920'),
    ('cover->PSUXP', base.replace('PSUXPCover', 'PSUXP')),
]
os.makedirs(r'E:\IdeaProjects\mcp-rest-bridge\_work\lanhu', exist_ok=True)
for label, url in variants:
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0', 'Referer': 'https://lanhuapp.com/web/'})
        raw = urllib.request.urlopen(req, timeout=30).read()
        im = Image.open(io.BytesIO(raw))
        print('%-20s bytes=%-9d size=%s format=%s' % (label, len(raw), im.size, im.format))
        if label == 'raw(no oss-process)':
            im.convert('RGB').save(r'E:\IdeaProjects\mcp-rest-bridge\_work\lanhu\probe-home-raw.png')
    except Exception as e:
        print('%-20s FAILED: %s' % (label, e))
