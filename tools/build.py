"""Builds www/ (the web app Capacitor ships) from src/ and data/."""
import os, shutil
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
S = lambda *p: os.path.join(root, 'src', *p)
app = open(S('app.html'), encoding='utf-8').read()
import base64, json as _json, re as _re
# Startup speed: the page used to carry 5.8 MB inline (fonts as base64, the mushaf layout and text as JS
# literals), all parsed on the main thread before the first page could show. Now:
#  - each font is its own .woff2/.ttf file next to the page (no base64 to decode, read when the font is used);
#  - the data scripts are separate files (the web view parses them off the main thread and caches the code),
#    and plain-data constants are written as JSON.parse('...'), which V8 reads several times faster than the
#    same array written as a JavaScript literal.
FONTS = {}
def unfont(css, name):
    def rep(m):
        i = len([k for k in FONTS if k.startswith('fonts/' + name)])
        fn = f'fonts/{name}{i}.{ {"woff2":"woff2","woff":"woff","ttf":"ttf","truetype":"ttf","otf":"otf","opentype":"otf"}.get(m.group(1), m.group(1)) }'
        FONTS[fn] = base64.b64decode(m.group(2))
        return f'url({fn})'
    return _re.sub(r'url\(["\']?data:(?:font|application)/(?:x-font-)?([\w-]+);base64,([A-Za-z0-9+/=]+)["\']?\)', rep, css)
def asjson(src):
    # 'const X=<json>;' blocks become 'const X=JSON.parse("...")' when the value is plain JSON
    out = []
    for m in _re.finditer(r'(?:^|\n)const (\w+)=(.*?);?\s*(?=\nconst |$)', src, _re.S):
        try:
            v = _json.loads(m.group(2).rstrip().rstrip(';'))
            j = _json.dumps(v, ensure_ascii=False, separators=(",", ":"))
            j = j.replace('\\', '\\\\').replace("'", "\\'").replace('\u2028', '\\u2028').replace('\u2029', '\\u2029')
            out.append(f"const {m.group(1)}=JSON.parse('{j}');")
        except ValueError:
            out.append(f'const {m.group(1)}={m.group(2).rstrip().rstrip(";")};')
    return '\n'.join(out) + '\n'
fontcss = unfont(open(S('font.css'), encoding='utf-8').read(), 'mushaf') + unfont(open(S('amiri.css'), encoding='utf-8').read(), 'amiri') + unfont(open(S('suranames.css'), encoding='utf-8').read(), 'suranames') + unfont(open(S('alexandria.css'), encoding='utf-8').read(), 'alexandria')
SCRIPTS = {'adhan.js': open(S('adhan.js'), encoding='utf-8').read(), 'data.js': asjson(open(S('data.js'), encoding='utf-8').read()), 'k.js': asjson(open(S('k.js'), encoding='utf-8').read())}
app = app.replace('/*SKIN*/', open(S('skin-clean.css'), encoding='utf-8').read())
app = app.replace('/*TFONT*/', unfont(open(S('textfonts.css'), encoding='utf-8').read(), 'text'))
app = app.replace('/*FONT*/', fontcss).replace('/*QP*/', '')
assert '<script>/*ADHAN*/</script>' in app and '<script>\n/*DATA*/\n/*K*/\n</script>' in app
app = (app.replace('<script>/*ADHAN*/</script>', '<script src="adhan.js"></script>')
          .replace('<script>\n/*DATA*/\n/*K*/\n</script>', '<script src="data.js"></script>\n<script src="k.js"></script>'))
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
for fn, b in FONTS.items():
    os.makedirs(os.path.join(www, os.path.dirname(fn)), exist_ok=True)
    open(os.path.join(www, fn), 'wb').write(b)
for fn, t in SCRIPTS.items():
    open(os.path.join(www, fn), 'w', encoding='utf-8').write(t)
head = ('<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">'
        '<meta name="theme-color" content="#12291f"><title>أنورا</title>'
        '<style>body{margin:0}[hidden]{display:none!important}</style></head><body>')
open(os.path.join(www, 'index.html'), 'w', encoding='utf-8').write(head + app + '</body></html>')
print('www/index.html', os.path.getsize(os.path.join(www, 'index.html')))
