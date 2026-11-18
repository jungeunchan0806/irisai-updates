"""Exercise the real Frida CModule bridge against a fake menu/config boundary."""
import json
import subprocess
from pathlib import Path
import frida

source = (Path(__file__).parent / 'features_native.c').read_text()
test = (Path(__file__).parent / 'features-native-test.js').read_text().replace('SOURCE',json.dumps(source))
process=subprocess.Popen(['cat'],stdin=subprocess.PIPE,stdout=subprocess.DEVNULL)
session=None
try:
    session=frida.attach(process.pid)
    script=session.create_script(test)
    script.on('message',lambda message,data:print(json.dumps(message)))
    script.load()
    print(json.dumps(script.exports_sync.run(),indent=2))
finally:
    if session:session.detach()
    process.terminate();process.wait(timeout=5)
