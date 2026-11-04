"""Builds www/ (the web app Capacitor ships) from src/ and data/."""
import os, shutil
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
S = lambda *p: os.path.join(root, 'src', *p)
app = open(S('app.html'), encoding='utf-8').read()
app = (app.replace('/*FONT*/', open(S('font.css'), encoding='utf-8').read() + open(S('amiri.css'), encoding='utf-8').read() + open(S('suranames.css'), encoding='utf-8').read())
          .replace('/*DATA*/', open(S('data.js'), encoding='utf-8').read())
          .replace('/*QP*/', '')
          .replace('/*K*/', open(S('k.js'), encoding='utf-8').read())
          .replace('/*ADHAN*/', open(S('adhan.js'), encoding='utf-8').read()))
# ---- Quran text lock: the mushaf text and the page words must match the reviewed release ----
import hashlib, json, re, sys
def jsval(path, name):
    src = open(S(path), encoding='utf-8').read()
    m = re.search(r'const ' + name + r'=(.*?);?\s*(?:\nconst |$)', src, re.S)
    return json.loads(m.group(1).rstrip().rstrip(';'))
def canon_q(Q): return '\n\n'.join('\n'.join(s) for s in Q)
def canon_k(K): return '\f'.join('\n'.join(' '.join(w if isinstance(w, str) else w[0] for w in l[-1]) for l in p) for p in K)
hq = hashlib.sha256(canon_q(jsval('data.js', 'Q')).encode('utf-8')).hexdigest()
hk = hashlib.sha256(canon_k(jsval('k.js', 'K')).encode('utf-8')).hexdigest()
lockf = os.path.join(root, 'tools', 'quran.lock')
if os.path.exists(lockf):
    want = json.load(open(lockf))
    if (want['text'], want['pages']) != (hq, hk) and os.environ.get('ALLOW_TEXT_CHANGE') != '1':
        sys.exit(f'Quran text changed!\n text  {hq}\n want  {want["text"]}\n pages {hk}\n want  {want["pages"]}\nIf this change was reviewed, rebuild with ALLOW_TEXT_CHANGE=1 and commit tools/quran.lock.')
json.dump({'text': hq, 'pages': hk}, open(lockf, 'w'), indent=1)
app = app.replace('/*QLOCK*/null', json.dumps({'text': hq, 'pages': hk}))
print('quran lock ok', hq[:12], hk[:12])
www = os.path.join(root, 'www')
shutil.rmtree(www, ignore_errors=True)
shutil.copytree(os.path.join(root, 'data'), www)
head = ('<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">'
        '<meta name="theme-color" content="#12291f"><title>أنورا</title>'
        '<style>body{margin:0}[hidden]{display:none!important}</style></head><body>')
open(os.path.join(www, 'index.html'), 'w', encoding='utf-8').write(head + app + '</body></html>')
print('www/index.html', os.path.getsize(os.path.join(www, 'index.html')))
