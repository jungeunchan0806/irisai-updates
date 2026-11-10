#!/usr/bin/env python3
"""Read-only device/version inventory. Does not root, unlock, patch, or install anything."""
import argparse, hashlib, json, re, shutil, subprocess, sys
from pathlib import Path

PACKAGE = 'com.supercell.brawlstars'

def run(argv, timeout=30):
    p=subprocess.run(argv,capture_output=True,text=True,timeout=timeout)
    if p.returncode: raise RuntimeError(p.stderr.strip() or p.stdout.strip() or f'exit {p.returncode}')
    return p.stdout.strip()

def parse_package(text):
    code=re.search(r'\bversionCode=(\d+)',text)
    name=re.search(r'\bversionName=([^\r\n]+)',text)
    return {'version_code':int(code.group(1)) if code else None,'version_name':name.group(1).strip() if name else None,
            'debuggable':'DEBUGGABLE' in text}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb',default=shutil.which('adb') or 'adb');p.add_argument('--serial')
    p.add_argument('--check-root',action='store_true',help='Run su -c id; an installed root manager may show its permission dialog')
    p.add_argument('--out',type=Path,default=Path('android-device-report.json'))
    a=p.parse_args(); report={'status':'waiting_for_device','friendly_battle_tested':False,'game_package':PACKAGE}
    try:
        lines=run([a.adb,'devices']).splitlines()[1:]
        ready=[line.split()[0] for line in lines if len(line.split())>=2 and line.split()[1]=='device']
        if a.serial:
            if a.serial not in ready: raise RuntimeError('Selected device is not connected and authorized')
            serial=a.serial
        elif len(ready)==1: serial=ready[0]
        elif not ready: raise RuntimeError('No authorized Android device is connected')
        else: raise RuntimeError('Multiple devices: select one with --serial')
        base=[a.adb,'-s',serial,'shell']
        report.update(status='device_found',device_id_hash=hashlib.sha256(serial.encode()).hexdigest()[:12])
        for key,prop in [('model','ro.product.model'),('android','ro.build.version.release'),('sdk','ro.build.version.sdk'),('abis','ro.product.cpu.abilist')]:
            report[key]=run(base+['getprop',prop])
        installed=run(base+['pm','path',PACKAGE])
        report['game_installed']=bool(installed.startswith('package:'))
        if report['game_installed']: report['game']=parse_package(run(base+['dumpsys','package',PACKAGE]))
        report['root_authorized']=None
        if a.check_root:
            try: report['root_authorized']='uid=0(' in run(base+['su','-c','id'],timeout=45)
            except (RuntimeError,subprocess.TimeoutExpired): report['root_authorized']=False
        report['status']='ready_for_manual_compatibility_check' if report['game_installed'] else 'game_missing'
    except (OSError,RuntimeError,subprocess.TimeoutExpired) as e: report['blocker']=str(e)
    a.out.parent.mkdir(parents=True,exist_ok=True);a.out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))
    return 0 if report['status']=='ready_for_manual_compatibility_check' else 3

if __name__=='__main__':sys.exit(main())
