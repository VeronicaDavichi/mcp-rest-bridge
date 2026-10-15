# -*- coding: utf-8 -*-
import sys, glob, os
sys.path.insert(0, r'E:\IdeaProjects\mcp-rest-bridge\_work')
from psd_text import read_psd

for p in sorted(glob.glob(r'E:\IdeaProjects\mcp-rest-bridge\ui_design\*.psd')):
    print('=' * 60)
    print(os.path.basename(p))
    for L in read_psd(p):
        t, l, b, r = L['bbox']
        w, h = r - l, b - t
        if w >= 900 and h >= 600:
            print('  artboard? idx=%3d x=%d y=%d w=%d h=%d name=%r' % (L['index'], l, t, w, h, L['name']))
