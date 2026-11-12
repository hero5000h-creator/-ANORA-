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

## Windows

`desktop/` wraps the same `www/` in Electron (window size kept between runs, keyboard shortcuts on F1, Windows
notifications for prayer and adhkar reminders while the app runs, surah downloads in the user's AppData folder).
The workflow **Windows** builds `anora-windows-setup.exe` and `anora-windows-portable.exe` and adds them to the
`test` release. They are unsigned, so SmartScreen asks once (More info → Run anyway).
Not on Windows yet: reciting into the mic for memorisation, the media notification and widgets (Android only).

```sh
python3 tools/build.py
cd desktop && npm ci && npm start
```

## Licences

Code: GPL-3.0-or-later. Content and fonts keep their own licences; see `NOTICE.md`.
