# -*- coding: utf-8 -*-
"""Crop artboards out of the rendered PSD composites for close reading."""
import os
from PIL import Image

SRC = r'E:\IdeaProjects\mcp-rest-bridge\_work\preview'
OUT = r'E:\IdeaProjects\mcp-rest-bridge\_work\boards'
os.makedirs(OUT, exist_ok=True)

jobs = {
    '首页与服务列表': [
        ('p1-home', (0, 0, 1920, 1080)),
        ('p2-list', (0, 1224, 1920, 2304)),
        ('p3-list-hover', (2020, 1224, 3940, 2304)),
        ('p4-toolbar-zoom', (2210, 1254, 3749, 2273)),
    ],
    '服务的创建与导入': [
        ('s1-new-service', (0, 0, 1920, 1080)),
        ('s2-import-upload', (2020, 0, 3940, 1080)),
        ('s3-import-check', (4040, 0, 5960, 1080)),
        ('s4-import-result', (6060, 0, 7980, 1080)),
    ],
    '工具': [
        ('t1-tool-list', (0, 0, 1920, 1080)),
        ('t2-tool-detail', (2020, 0, 3940, 1080)),
        ('t3-param-frames-left', (700, 1300, 4000, 1951)),
        ('t4-param-frames-right', (4000, 1300, 8027, 1951)),
    ],
}

for name, crops in jobs.items():
    im = Image.open(os.path.join(SRC, name + '.png')).convert('RGB')
    for label, box in crops:
        box = (max(0, box[0]), max(0, box[1]), min(im.width, box[2]), min(im.height, box[3]))
        piece = im.crop(box)
        path = os.path.join(OUT, label + '.png')
        piece.save(path)
        print('%-22s %-24s %s  size=%s' % (name, label, box, piece.size))
