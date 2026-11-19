"""Exercise the patcher's normal document pickers on an isolated Android emulator."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = 'com.irisai.theseven.linkremoval'
SOURCE = 'TheSeven-Transparent-UI-1.0.apk'
OUTPUT = 'TheSeven-Transparent-UI-no-Discord.apk'
EXPECTED = '454f0c209e5bd42260e0c4e3f5f8db76f7588866bafb1dcb04774323eae9ea29'
evidence = Path('qa-results')
evidence.mkdir(exist_ok=True)

def adb(*args):
    return subprocess.check_output(['adb', *args], timeout=260).decode(errors='replace').strip()

def nodes():
    adb('shell','uiautomator','dump','/sdcard/ui-check.xml')
    snapshot = adb('exec-out','cat','/sdcard/ui-check.xml')
    (evidence/'last-ui.xml').write_text(snapshot)
    return [node.attrib for node in ET.fromstring(snapshot).iter('node')]

def lookup(items, labels):
    for item in items:
        if any(item.get(k,'').casefold() in labels for k in ('text','content-desc')):
            return item
    return None

def tap(item):
    x1,y1,x2,y2 = map(int,re.findall(r'\d+',item['bounds']))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
    time.sleep(.45)

def wait_item(*labels, timeout=30):
    labels={s.casefold() for s in labels}
    until=time.monotonic()+timeout
    while time.monotonic()<until:
        item=lookup(nodes(),labels)
        if item is not None: return item
        time.sleep(.4)
    raise RuntimeError('Missing UI element: '+str(labels))

def downloads():
    current=nodes()
    drawer=lookup(current,{'show roots','open navigation drawer','show navigation drawer','navigate up','show locations'})
    if drawer is None:
        drawer=next((x for x in current if x.get('class')=='android.widget.ImageButton' and x.get('clickable')=='true'),None)
    if drawer is None: raise RuntimeError('Cannot open the document picker locations')
    tap(drawer)
    tap(wait_item('Downloads'))

def run():
    adb('install','dist/TheSeven-Link-Remover.apk')
    adb('shell','mkdir','-p','/sdcard/Download')
    adb('push',os.path.join(os.environ['RUNNER_TEMP'],SOURCE),'/sdcard/Download/'+SOURCE)
    adb('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d','file:///sdcard/Download/'+SOURCE)
    adb('shell','svc','wifi','disable')
    adb('shell','svc','data','disable')
    adb('shell','am','start','-W','-n',PACKAGE+'/.MainActivity')
    tap(wait_item('기존 APK 선택'))
    downloads()
    tap(wait_item(SOURCE))
    downloads()
    tap(wait_item('Save'))
    success='검증 및 저장 완료. 선택한 폴더에서 저장한 APK를 열 수 있습니다.'
    end=time.monotonic()+260
    while time.monotonic()<end:
        items=nodes()
        if lookup(items,{success.casefold()}): break
        errors=[i['text'] for i in items if i.get('text','').startswith('저장 실패')]
        if errors: raise RuntimeError(errors[0])
        time.sleep(1)
    else: raise RuntimeError('No completion message')
    output=adb('shell','sha256sum','/sdcard/Download/'+OUTPUT).split()[0]
    assert output==EXPECTED,output
    info={'api':adb('shell','getprop','ro.build.version.sdk'),'picker_flow':True,'offline':True,'output_sha256':output,'game_launched':False}
    (evidence/'result.json').write_text(json.dumps(info,indent=2)+'\n')
    print(info,flush=True)

try:
    run()
except BaseException:
    (evidence/'screen.png').write_bytes(subprocess.check_output(['adb','exec-out','screencap','-p']))
    (evidence/'crash.txt').write_text(adb('logcat','-d','-b','crash','-t','80'))
    raise
