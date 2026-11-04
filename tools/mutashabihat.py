"""Builds data/mutashabihat.json from Waqar144/Quran_Mutashabihat_Data (CC0 1.0).

The source lists, for each ayah a hafiz tends to confuse, the ayat it resembles (curated after
Qari Idrees al-Asim). Its ayah numbers count from 0; ours count from 1.
Output: [[[src ids], [[mut ids], ...]], ...]. Which words the ayat share is worked out in the app
from the mushaf text itself, so this file holds no Quran text.

Usage: python3 tools/mutashabihat.py [path/to/mutashabiha_data.json]
(without a path it downloads the file from GitHub)."""
import json, os, sys, urllib.request
URL = 'https://raw.githubusercontent.com/Waqar144/Quran_Mutashabihat_Data/master/mutashabiha_data.json'
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
src = json.load(open(sys.argv[1], encoding='utf-8')) if len(sys.argv) > 1 else json.load(urllib.request.urlopen(URL))
ids = lambda a: [x + 1 for x in (a if isinstance(a, list) else [a])]
out, seen = [], set()
for juz in sorted(src, key=int):
    for e in src[juz]:
        s = ids(e['src']['ayah'])
        ms = [ids(m['ayah']) for m in e['muts']]
        assert all(1 <= x <= 6236 for x in s + sum(ms, [])), e
        key = json.dumps([s, ms])
        if key in seen: continue
        seen.add(key); out.append([s, ms])
json.dump(out, open(os.path.join(root, 'data', 'mutashabihat.json'), 'w'), separators=(',', ':'))
print(len(out), 'entries')
