"""Builds data/hph.json: how each ayah must sound, for the vowel check while following a recitation.

For every ayah: the Quran phonetic script (obadx/quran-transcript, Hafs) of the whole ayah, as it is read on
(wasl inside the ayah, waqf at its end), and how many of its symbols belong to each word of the app's mushaf text.
Word boundaries come from lining the ayah's script up with each word's own script. Ayat whose word count differs
from the app's text are left out (the check then skips them).

  pip install quran-transcript levenshtein   (fuzzysearch, which it imports for search only, may be stubbed)
  python tools/harakat_ref.py
"""
import json, os, re
from quran_transcript import Aya, quran_phonetizer, MoshafAttributes

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MA = MoshafAttributes(rewaya='hafs', madd_monfasel_len=4, madd_mottasel_len=4, madd_mottasel_waqf=4, madd_aared_len=2)
src = open(os.path.join(ROOT, 'src', 'data.js'), encoding='utf-8').read()
m = re.search(r"const Q=(?:JSON\.parse\(')?(\[\[.*?\]\])", src, re.S)
Q = json.loads(m.group(1))


MARKS = set('\u064e\u064f\u0650\u06ba\u06e5\u06e6\u0627\u06ea\u0640\u0672\u06dc\u0619')


def words(t):
    return [w for w in t.split() if w not in ('۞', '۩')]


out, skipped = {}, 0
for s, sura in enumerate(Q, 1):
    for a, t in enumerate(sura, 1):
        u = Aya(s, a).get().uthmani.split(' ')
        if len(u) != len(words(t)):
            skipped += 1
            continue
        spaced = quran_phonetizer(' '.join(u), MA).phonemes
        full = spaced.replace(' ', '')
        gaps, n = set(), 0                           # where the script itself leaves a space between words
        for ch in spaced:
            if ch == ' ':
                gaps.add(n)
            else:
                n += 1
        # where each word ends: how far the script of the words up to it agrees with the whole ayah's (the last
        # word of a prefix is read as a stop, which only changes its own end)
        ends = []
        for k in range(1, len(u)):
            p = quran_phonetizer(' '.join(u[:k]), MA).phonemes.replace(' ', '')
            n = 0
            while n < min(len(p), len(full)) and p[n] == full[n]:
                n += 1
            ends.append(max(n, ends[-1] if ends else 0))
        ends.append(len(full))
        # a stop changes the end of a word (ة→ه, a dropped vowel): when the script has a space between this word's
        # approximate end and the next word's, that space is the true end
        for k in range(len(ends) - 1):
            g = [x for x in gaps if ends[k] <= x < ends[k + 1]]
            if g:
                ends[k] = min(g)
        # ta marbuta read on (ت) belongs to its word too; at a stop it becomes ه, so the prefix stops before it
        for k in range(len(ends) - 1):
            if 'ة' in u[k] and ends[k] < ends[k + 1] and full[ends[k]] == 'ت':
                ends[k] += 1
        # a vowel, a long vowel or a ghunnah never starts a word: it belongs to the word before
        for k in range(len(ends) - 1):
            while ends[k] < ends[k + 1] and full[ends[k]] in MARKS:
                ends[k] += 1
        lens = [e - (ends[i - 1] if i else 0) for i, e in enumerate(ends)]
        out[f'{s}:{a}'] = full + '|' + ','.join(map(str, lens))
json.dump(out, open(os.path.join(ROOT, 'data', 'hph.json'), 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
print(len(out), 'ayat,', skipped, 'skipped')
