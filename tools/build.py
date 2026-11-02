"""Builds www/ (the web app Capacitor ships) from src/ and data/."""
import os, shutil
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
S = lambda *p: os.path.join(root, 'src', *p)
app = open(S('app.html'), encoding='utf-8').read()
app = (app.replace('/*FONT*/', open(S('font.css'), encoding='utf-8').read() + open(S('amiri.css'), encoding='utf-8').read())
          .replace('/*DATA*/', open(S('data.js'), encoding='utf-8').read())
          .replace('/*QP*/', '')
          .replace('/*K*/', open(S('k.js'), encoding='utf-8').read()))
www = os.path.join(root, 'www')
shutil.rmtree(www, ignore_errors=True)
shutil.copytree(os.path.join(root, 'data'), www)
head = ('<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">'
        '<meta name="theme-color" content="#12291f"><title>أنورا</title>'
        '<style>body{margin:0}[hidden]{display:none!important}</style></head><body>')
open(os.path.join(www, 'index.html'), 'w', encoding='utf-8').write(head + app + '</body></html>')
print('www/index.html', os.path.getsize(os.path.join(www, 'index.html')))
