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
want = ('الحصري', 'العفاسي', 'Husary', 'Alafasy', 'Afasy')
picked = [r for r in (reads or []) if any(w in json.dumps(r, ensure_ascii=False) for w in want)]
print('picked', json.dumps(picked, ensure_ascii=False))
for r in picked:
    rid = r.get('id') or r.get('read_id')
    T = {}
    for s in range(1, 115):
        T[s] = get(API + f'ayat_timing?surah={s}&read={rid}')
    json.dump({'read': r, 'timing': T}, open(f'{out}/timing-{rid}.json', 'w'), ensure_ascii=False)
    print('saved', rid, sum(len(v or []) for v in T.values()))

# ---- compact per-reciter files for the app: {surah: [[ayah, start_ms, end_ms], ...]} plus the mp3 folder ----
PICK = {118: 'husary', 123: 'alafasy'}
os.makedirs('data/audio', exist_ok=True)
for rid, key in PICK.items():
    p = f'{out}/timing-{rid}.json'
    if not os.path.exists(p): continue
    d = json.load(open(p))
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
