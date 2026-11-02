# أنورا · ANORA

**The Holy Quran Experience** · القرآن الكريم • التلاوة • التفسير

A Madinah 1405H mushaf for Android, built for the Galaxy Z Fold first. It has swipe-only page turns, a decorated book look, six tafsirs, a word explorer, memorisation with spaced review and self-tests, ayah-by-ayah recitation with range repeat, a khatma planner, adhkar and coloured tajweed. It works offline and has no ads, ever.

Developed by مصطفى نصير عبد العكيدي.

## Layout

- `src/` — the app (one HTML/JS/CSS page) and its inlined data (layout, font, metadata).
- `data/` — tafsir, word-meaning and morphology files loaded on demand.
- `tools/build.py` — assembles `www/` from `src/` and `data/`.
- `android/` — the Capacitor Android project.

## Build

```sh
npm ci
npm run sync          # builds www/ and copies it into android/
cd android && ./gradlew assembleDebug
```

Every push to `main` builds a debug APK on GitHub Actions. To get it, open **Actions**, pick the latest run and download **anora-debug-apk**.

## Licences

Code: GPL-3.0-or-later. Content and fonts keep their own licences; see `NOTICE.md`.
