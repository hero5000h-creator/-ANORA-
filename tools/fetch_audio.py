"""Fetches MP3Quran.net reciter info and ayah timings (run on CI: the dev sandbox cannot reach mp3quran.net)."""
import json, os, urllib.request, time
out = 'audio-raw'; os.makedirs(out, exist_ok=True)
def get(url):
    for i in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'ANORA/0.1'}), timeout=60) as r:
                return json.loads(r.read().decode('utf-8'))
        except Exception as e:
            print('retry', url, e); time.sleep(2 * (i + 1))
    return None
API = 'https://mp3quran.net/api/v3/'
rec = get(API + 'reciters?language=ar'); json.dump(rec, open(out + '/reciters.json', 'w'), ensure_ascii=False)
reads = get(API + 'ayat_timing/reads'); json.dump(reads, open(out + '/reads.json', 'w'), ensure_ascii=False)
print('reads:', json.dumps(reads, ensure_ascii=False)[:3000])
# reciters the app offers: key -> name fragments (any spelling); a murattal read is preferred over mujawwad/teaching
def rname(r): return json.dumps(r, ensure_ascii=False)
WANT = {
    'husary': ['الحصري'], 'alafasy': ['العفاسي'],
    'minshawi': ['المنشاوي'], 'abdulbasit': ['عبدالباسط', 'عبد الباسط'],
    'shatri': ['الشاطري'], 'abkar': ['أبكر', 'ابكر'], 'amer': ['أحمد عامر', 'احمد عامر', 'محمد عامر'],
    'khalil': ['خليل إسماعيل', 'خليل اسماعيل', 'خليل إسماعيل'],
}
# the Iraqi reciter al-Hafiz Khalil Ismail: list every MP3Quran entry that names him, with or without ayah timings
for r in (rec or {}).get('reciters', []):
    if 'خليل' in r.get('name', '') and 'الحصري' not in r.get('name', ''):
        print('reciter', r.get('id'), r.get('name'), [(m.get('id'), m.get('name'), m.get('server')) for m in r.get('moshaf', [])])
print('timing reads with خليل:', [(r.get('id'), r.get('name')) for r in (reads or []) if 'خليل' in rname(r)])
FIXED = {118: 'husary', 123: 'alafasy'}   # the reads the app already shipped with
def score(r):
    n = rname(r)
    return (('مرتل' in n) * 2 - ('مجود' in n) - ('معلم' in n) * 2 - ('ورش' in n or 'قالون' in n or 'الدوري' in n) * 3)
PICK = {}
for key, frags in WANT.items():
    fixed = [rid for rid, k in FIXED.items() if k == key]
    if fixed: PICK[fixed[0]] = key; continue
    cands = [r for r in (reads or []) if any(f in rname(r) for f in frags)]
    cands.sort(key=score, reverse=True)
    print('candidates', key, [(r.get('id'), r.get('name')) for r in cands])
    if cands: PICK[cands[0].get('id') or cands[0].get('read_id')] = key
print('PICK', PICK)
for rid in PICK:
    T = {}
    for s in range(1, 115):
        T[s] = get(API + f'ayat_timing?surah={s}&read={rid}')
    r = next((x for x in reads if (x.get('id') or x.get('read_id')) == rid), {})
    json.dump({'read': r, 'timing': T}, open(f'{out}/timing-{rid}.json', 'w'), ensure_ascii=False)
    print('saved', rid, sum(len(v or []) for v in T.values()))

# ---- compact per-reciter files for the app: {surah: [[ayah, start_ms, end_ms], ...]} plus the mp3 folder ----
os.makedirs('data/audio', exist_ok=True)
for rid, key in PICK.items():
    p = f'{out}/timing-{rid}.json'
    if not os.path.exists(p): continue
    d = json.load(open(p))
    if sum(len(v or []) for v in d['timing'].values()) < 6000: print('skip, timings incomplete', key); continue
    print('sample', key, json.dumps(d['timing']['1'][:3]), json.dumps(d['timing']['2'][:2]))
    S = {}
    for s, rows in d['timing'].items():
        rr = []
        for x in rows or []:
            a = x.get('ayah', x.get('verse', x.get('aya')))
            st = x.get('start_time', x.get('start'))
            en = x.get('end_time', x.get('end'))
            if a is None or st is None: continue
            rr.append([int(a), int(st), int(en) if en is not None else None])
        S[s] = rr
    json.dump({'reader': d['read']['name'], 'folder': d['read']['folder_url'], 'surahs': S},
              open(f'data/audio/{key}.json', 'w'), ensure_ascii=False, separators=(',', ':'))
    print('wrote', key, sum(len(v) for v in S.values()))

# ---- file sizes, so the app can say how big a download is before it starts ----
def size(url):
    for i in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, method='HEAD', headers={'User-Agent': 'ANORA/0.1'}), timeout=60) as r:
                return int(r.headers.get('Content-Length') or 0)
        except Exception as e:
            print('retry', url, e); time.sleep(2 * (i + 1))
    return 0
for key in PICK.values():
    p = f'data/audio/{key}.json'
    if not os.path.exists(p): continue
    d = json.load(open(p))
    d['sizes'] = {str(s): size(f"{d['folder']}{s:03d}.mp3") for s in range(1, 115)}
    json.dump(d, open(p, 'w'), ensure_ascii=False, separators=(',', ':'))
    print('sizes', key, round(sum(d['sizes'].values()) / 1048576), 'MB')
