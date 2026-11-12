#!/usr/bin/env python3
"""Prove a diagnostic update reads a prior crash and preserves data in our CI app."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
    a.out.mkdir(parents=True,exist_ok=True)
    directory=Path('diagnostic-distribution');m=json.loads((directory/'manifest.json').read_text())
    for entry in m['files']:
        with (directory/entry['name']).open('rb') as src:actual=hashlib.file_digest(src,'sha256').hexdigest()
        assert actual==entry['sha256'],'Test APK checksum mismatch'
    serial=None;report={'test':'Own CI fixture, not Brawl Stars','data_preserved':False,'same_uid':False,'prior_crash_readable':False,'ui_alive':False}
    def adb(*args,check=True,timeout=45):
        r=subprocess.run(['adb']+(['-s',serial] if serial else [])+list(args),capture_output=True,timeout=timeout)
        if check and r.returncode:raise RuntimeError((r.stdout+r.stderr).decode('utf-8','replace')[-1500:])
        return r.stdout
    def uid():
        text=adb('shell','cmd','package','list','packages','-U','com.supercell.brawlstars').decode()
        return re.search(r'uid:(\d+)',text).group(1)
    try:
        ready=[line.split()[0] for line in adb('devices').decode().splitlines() if line.endswith('\tdevice')]
        assert len(ready)==1 and ready[0].startswith('emulator-'),'Requires one disposable emulator'
        serial=ready[0]
        assert not adb('shell','pm','path','com.supercell.brawlstars',check=False).strip(),'Refuses pre-existing app'
        report['api']=adb('shell','getprop','ro.build.version.sdk').decode().strip()
        adb('root');adb('wait-for-device')
        adb('install',str(directory/'CI_ONLY_crash_fixture.apk'));before=uid()
        adb('logcat','-c')
        adb('shell','am','start','-W','-n','com.supercell.brawlstars/com.irisai.fixture.CrashActivity')
        time.sleep(3)
        adb('install','-r',str(directory/'IrisAutoGoal-Startup-Diagnostic1.apk'))
        report['same_uid']=before==uid()
        marker=adb('shell','cat','/data/user/0/com.supercell.brawlstars/files/iris-ci-preserved-data.txt').decode()
        report['data_preserved']=marker=='keep-this-data'
        adb('shell','am','start','-W','-n','com.supercell.brawlstars/com.irisai.diagnostic.DiagnosticActivity')
        time.sleep(6)
        report['ui_alive']=bool(adb('shell','pidof','com.supercell.brawlstars',check=False).strip())
        adb('shell','uiautomator','dump','/sdcard/diagnostic-window.xml')
        xml=adb('shell','cat','/sdcard/diagnostic-window.xml')
        root=ET.fromstring(xml)
        ui='\n'.join(n.attrib.get('text','') for n in root.iter('node'))
        report['prior_crash_readable']='IRIS_DIAGNOSTIC_FIXTURE' in ui
        report['exit_history_present']='Java 예외 (4)' in ui
        report['report_visible']='IRIS STARTUP DIAGNOSTIC 1' in ui
        # Only synthetic fixture text is saved; no original game or account is installed.
        (a.out/'ui.txt').write_text(ui)
        png=adb('exec-out','screencap','-p')
        if png.startswith(b'\x89PNG'):(a.out/'diagnostic.png').write_bytes(png)
        assert all(report[k] for k in ['same_uid','data_preserved','ui_alive','prior_crash_readable','exit_history_present','report_visible']),report
    except Exception as e:report['error']=str(e)
    (a.out/'test-result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps(report,ensure_ascii=False,indent=2))
    return int('error' in report)

if __name__=='__main__':raise SystemExit(main())
