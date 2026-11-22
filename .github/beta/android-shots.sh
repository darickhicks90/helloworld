#!/bin/bash
# Beta check on an Android emulator: installs the three apps, opens each screen and saves a
# screenshot of it, plus any crashes. Run by .github/workflows/beta-check.yml inside the emulator job.
set -u
OUT=${1:-shots}; mkdir -p "$OUT"
adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
# the one-time "Viewing full screen" hint would cover every screenshot
adb shell settings put secure immersive_mode_confirmations confirmed
for apk in 5IY.apk 5IY-Pocket.apk Little-Yeti.apk; do adb install -r -g "$apk" || echo "INSTALL FAILED: $apk" >> "$OUT/problems.txt"; done
adb logcat -c

shot() { sleep "${2:-4}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
dens=$(adb shell wm density | tail -1 | grep -o '[0-9]*$'); scale=$(echo "$dens / 160" | bc -l)
dp() { printf '%.0f' "$(echo "$1 * $scale" | bc -l)"; }
# the long side is the width in landscape
size=$(adb shell wm size | tail -1 | grep -o '[0-9]*x[0-9]*'); a=${size%x*}; b=${size#*x}
W=$(( a > b ? a : b )); H=$(( a > b ? b : a ))
echo "screen ${W}x${H} @ ${dens}dpi" | tee "$OUT/device.txt"
# the radio key: top right corner, the left one of the two keys (TopKeys: 100 x 46 dp, 4 dp in)
RX=$(( W - $(dp 4) - $(dp 100) + $(dp 24) )); RY=$(( $(dp 4) + $(dp 23) ))
NX=$(( W - $(dp 4) - $(dp 24) ))

A=app.foxbox/.MainActivity
start() { adb shell am force-stop app.foxbox; adb shell am start -W -n $A "$@" > /dev/null; }

# 5 Eye Yeti
start; shot 01-title 6
start --es screen studio --es page gusts; shot 02-studio-gusts 6
for pg in reflections leaves minds bumble pickup flowers; do start --es screen studio --es page $pg; shot 03-studio-$pg 5; done
start --es screen clibbins; shot 04-clibbins 6
start --es screen notebook; shot 05-notebook 6
start --es screen studio --es page gusts; sleep 5; adb shell input tap $RX $RY; shot 06-radio 4
adb shell input keyevent KEYCODE_BACK; sleep 1
# the menu (top bar, just left of the radio/notebook keys)
adb shell input tap $(( W - $(dp 104) - $(dp 50) )) $(dp 24); shot 07-menu 3
adb shell input keyevent KEYCODE_BACK

# (Wildwood Hall is still in development: not part of the beta check)

# 5IY Pocket
adb shell am start -W -n app.fiveiy.pocket/app.foxbox.pocket.PocketActivity > /dev/null; shot 20-pocket 6
size=$(adb shell wm size | tail -1 | grep -o '[0-9]*x[0-9]*')
PW=$(adb exec-out screencap -p | python3 -c "import sys,struct; d=sys.stdin.buffer.read(); print(struct.unpack('>I', d[16:20])[0])")
adb shell input tap $(( PW - $(dp 4) - $(dp 100) + $(dp 24) )) $RY; shot 21-pocket-radio 4

# Little Yeti: each page
for pg in 0 1 2; do
  adb shell am force-stop app.fiveiy.kids
  adb shell am start -W -n app.fiveiy.kids/.KidsActivity --ei page $pg > /dev/null; shot 3$pg-kids-page$pg 4
done

adb logcat -d -b crash > "$OUT/crashes.txt"
adb logcat -d '*:E' | grep -E "app.foxbox|fiveiy|AndroidRuntime|FATAL" | tail -200 > "$OUT/errors.txt" || true
echo "crash lines: $(wc -l < "$OUT/crashes.txt")"
