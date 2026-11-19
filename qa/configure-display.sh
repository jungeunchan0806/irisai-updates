#!/usr/bin/env bash
set -euo pipefail
config="$HOME/.android/avd/test.avd/config.ini"
sed -i -E '/^hw\.lcd\.(vsync|width|height|density)[[:space:]]*=/d' "$config"
cat >> "$config" <<'DISPLAY'
hw.lcd.vsync=120
hw.lcd.width=720
hw.lcd.height=1280
hw.lcd.density=260
DISPLAY
