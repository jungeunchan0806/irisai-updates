#!/usr/bin/env python3
"""Install/start in a disposable CI emulator. This is NOT a friendly-match test."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET
import zipfile
import shutil
from build_ingame import ORIGINALS, sha

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--apk',type=Path,required=True)
    p.add_argument('--out',type=Path,required=True)
    p.add_argument('--backup',type=Path)
    a=p.parse_args();a.out.mkdir(parents=True,exist_ok=True)
    report={'installed':False,'game_process_alive':False,'button_off_visible':False,
            'button_on_visible':False,'button_off_again':False,'friendly_matches':0}
    serial=None
    def adb(*args,timeout=60,check=True):
        cmd=['adb']+(['-s',serial] if serial else [])+list(args)
        r=subprocess.run(cmd,capture_output=True,timeout=timeout)
        if check and r.returncode:
            raise RuntimeError('ADB '+args[0]+' failed: '+(r.stdout+r.stderr).decode('utf-8','replace')[-1800:])
        return r
    def screenshot(name):
        r=adb('exec-out','screencap','-p',timeout=30)
        if r.stdout.startswith(b'\x89PNG'):(a.out/name).write_bytes(r.stdout)
    def button():
        adb('shell','uiautomator','dump','/sdcard/iris-window.xml',timeout=45)
        raw=adb('shell','cat','/sdcard/iris-window.xml').stdout
        for node in ET.fromstring(raw).iter('node'):
            if node.attrib.get('text','').startswith('오토골 '):
                return node.attrib['text'],list(map(int,re.findall(r'\d+',node.attrib['bounds'])))
        return None,None
    def diagnostics():
        result={}
        try:
            raw=adb('logcat','-b','crash','-d','-v','brief',timeout=15,check=False).stdout.decode('utf-8','replace')
            result['crash_summary']=[s for s in raw.splitlines() if re.search(r'FATAL EXCEPTION|java\.lang\.|signal \d|Abort message|#\d+ pc|backtrace',s)][-60:]
            raw=adb('shell','dumpsys','activity','exit-info','com.supercell.brawlstars',timeout=20,check=False).stdout.decode('utf-8','replace')
            result['exit_info']=[s.strip() for s in raw.splitlines() if re.search(r'reason=|status=|description=|processName=|importance=',s)][-20:]
        except Exception as e:result['diagnostic_error']=type(e).__name__
        return result
    try:
        devices=adb('devices').stdout.decode().splitlines()
        ready=[line.split()[0] for line in devices if line.endswith('\tdevice')]
        if len(ready)!=1 or not ready[0].startswith('emulator-'):
            raise RuntimeError('Requires exactly one disposable emulator; physical devices are refused.')
        serial=ready[0]
        if adb('shell','pm','path','com.supercell.brawlstars',check=False).stdout.strip():
            raise RuntimeError('Refusing to alter a pre-existing game installation')
        report['abi']=adb('shell','getprop','ro.product.cpu.abilist').stdout.decode().strip()
        report['android_api']=adb('shell','getprop','ro.build.version.sdk').stdout.decode().strip()
        if 'arm64-v8a' not in report['abi']:raise RuntimeError('Emulator lacks ARM64 translation')
        adb('install','--streaming','-t',str(a.apk),timeout=600)
        report['installed']=True
        adb('logcat','-c')
        # The only ADB input below clicks OUR toggle for an integration test.
        # The shipped APK itself uses only its in-process JNI bridge.
        start=adb('shell','am','start','-W','-n','com.supercell.brawlstars/com.supercell.brawlstars.GameApp',timeout=90,check=False)
        report['start_exit']=start.returncode
        time.sleep(25)
        report['game_process_alive']=bool(adb('shell','pidof','com.supercell.brawlstars',check=False).stdout.strip())
        screenshot('01-launched.png')
        text,bounds=button();report['initial_button']=text
        report['button_off_visible']=bool(text and text.startswith('오토골 OFF'))
        if bounds:
            x=str((bounds[0]+bounds[2])//2);y=str((bounds[1]+bounds[3])//2)
            adb('shell','input','tap',x,y);time.sleep(20)
            text,_=button();report['enabled_button']=text
            report['button_on_visible']=bool(text and text.startswith('오토골 ON'))
            screenshot('02-enabled.png')
            if report['button_on_visible']:
                adb('shell','input','tap',x,y);time.sleep(1)
                text,_=button();report['button_off_again']=bool(text and text.startswith('오토골 OFF'))
                screenshot('03-disabled.png')
        if not all(report[k] for k in ['installed','game_process_alive','button_off_visible','button_on_visible','button_off_again']):
            raise RuntimeError('Game launch/toggle check incomplete; inspect report and screenshots')
    except Exception as e:
        report['error']=str(e)
    finally:
        report['candidate_diagnostics']=diagnostics()
        try:
            # Do not publish raw game logs, guest account identifiers or credentials.
            raw=adb('logcat','-d','-v','brief','IrisAutoGoal:E','AndroidRuntime:E','*:S',timeout=15,check=False).stdout.decode('utf-8','replace')
            lines=[s for s in raw.splitlines() if ('com.irisai.' in s or 'IrisAutoGoal' in s or 'java.lang.' in s or 'dlopen failed' in s)]
            report['module_diagnostics']=lines[-40:]
        except Exception:pass
        # Only the installation created above is removed, only in a clean disposable emulator.
        if 'error' in report and report['installed'] and a.backup:
            baseline={}
            try:
                directory=a.apk.parent/'baseline-apks';directory.mkdir(exist_ok=True)
                with zipfile.ZipFile(a.backup) as archive:
                    for name,expected in ORIGINALS.items():
                        entries=[n for n in archive.namelist() if Path(n).name==name]
                        if len(entries)!=1:raise RuntimeError('Ambiguous original APK')
                        path=directory/name
                        with archive.open(entries[0]) as src,path.open('wb') as dst:shutil.copyfileobj(src,dst,1024*1024)
                        if sha(path)!=expected:raise RuntimeError('Original checksum mismatch')
                adb('uninstall','com.supercell.brawlstars')
                adb('install-multiple','-t',*[str(directory/n) for n in ORIGINALS],timeout=600)
                baseline['installed']=True
                adb('logcat','-c')
                start=adb('shell','am','start','-W','-n','com.supercell.brawlstars/com.supercell.brawlstars.GameApp',timeout=90,check=False)
                baseline['start_exit']=start.returncode
                time.sleep(30)
                baseline['game_process_alive']=bool(adb('shell','pidof','com.supercell.brawlstars',check=False).stdout.strip())
                screenshot('04-original-backup.png')
                baseline.update(diagnostics())
            except Exception as e:baseline['error']=str(e)
            report['unmodified_backup_comparison']=baseline
        (a.out/'smoke.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        print(json.dumps(report,ensure_ascii=False,indent=2))
    return 1 if 'error' in report else 0

if __name__=='__main__':raise SystemExit(main())
