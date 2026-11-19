set -eu
result=preview-results
mkdir -p "$result"
app=com.irisai.autogoal.preview
adb install -r preview-source/uipreview/build/outputs/apk/debug/uipreview-debug.apk
adb install -r preview-source/uipreview/build/outputs/apk/androidTest/debug/uipreview-debug-androidTest.apk
adb shell am instrument -w "$app.test/$app.PreviewChecks" | tee "$result/instrumentation.txt"
grep -E '^PASS |stream=PASS ' "$result/instrumentation.txt"
adb pull "/sdcard/Android/data/$app/files/ui-checks/result.json" "$result/result.json"
python3 - <<'PY'
import json
from pathlib import Path
report = json.loads(Path('preview-results/result.json').read_text())
assert report['passed']
assert 'manual OFF from the menu survives connection recovery' in report['checks']
print(len(report['checks']), 'passed UI checks')
PY
