#!/usr/bin/env python3
"""Résumé d'Android Lint, limité au code du launcher (le moteur de carte Mapsforge, code externe, est mis à part)."""
import collections
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
mine, ext = [], 0
for i in root.iter('issue'):
    locs = i.findall('location')
    f = locs[0].get('file', '') if locs else ''
    if '/mapsforge/' in f.replace('\\', '/'):
        ext += 1
        continue
    short = f.replace('\\', '/').split('/app/src/main/')[-1] if '/app/src/main/' in f else f.replace('\\', '/').split('/')[-1]
    mine.append((i.get('severity'), i.get('id'), short, locs[0].get('line', '') if locs else '', i.get('message', '')))

order = {'Fatal': 0, 'Error': 1, 'Warning': 2, 'Information': 3}
mine.sort(key=lambda t: (order.get(t[0], 9), t[1], t[2], t[3]))
print('Android Lint : %d remarques sur le launcher, %d sur le moteur de carte externe (ignorées)' % (len(mine), ext))
print('Par gravité :', dict(collections.Counter(t[0] for t in mine)))
print('Par type :', dict(collections.Counter(t[1] for t in mine).most_common()))
print()
for sev, iid, f, line, msg in mine:
    print('[%s] %s  %s:%s  %s' % (sev, iid, f, line, msg))
