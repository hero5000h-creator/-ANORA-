#!/usr/bin/env bash
# CI: the own Quran recogniser inside the app on an emulator. It must decode in its own process, and ending that
# process (as a native failure would) must leave the app running.
set -u
APK=$1 PKG=app.anora.quran
OUT=${GITHUB_WORKSPACE:-.}/logcat.txt
adb install -r -g "$APK" || exit 1
adb shell pm grant $PKG android.permission.RECORD_AUDIO || true
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity --ez qasrSelfTest true
ok=""
for i in $(seq 1 120); do
  sleep 1
  if adb logcat -d -s qasr:* | grep -q "selftest ok"; then ok=1; break; fi
  if adb logcat -d -s qasr:* | grep -q "selftest failed\|model failed"; then break; fi
  adb shell pidof $PKG >/dev/null || break
done
adb logcat -d > "$OUT"
adb logcat -d -b crash >> "$OUT"
grep -a "qasr" "$OUT" | tail -20
main=$(adb shell pidof $PKG | tr -d '\r')
asr=$(adb shell pidof $PKG:qasr | tr -d '\r')
echo "main=$main qasr=$asr"
[ -n "$ok" ] || { echo "::error::the recogniser self-test did not pass"; exit 1; }
[ -n "$main" ] || { echo "::error::the app is not running after the self-test"; exit 1; }
if [ -n "$asr" ]; then
  adb shell run-as $PKG kill -9 "$asr" || adb shell kill -9 "$asr" || true
  sleep 3
  main2=$(adb shell pidof $PKG | tr -d '\r')
  echo "after ending :qasr main=$main2"
  [ "$main2" = "$main" ] || { echo "::error::ending the recogniser's process took the app down"; exit 1; }
fi
echo "QASR EMULATOR OK"
