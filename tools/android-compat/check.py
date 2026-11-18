"""Run only in a fresh CI emulator. Store results as checks complete, including failures."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import uuid

HERE=Path(__file__).resolve().parent
PACKAGES=json.loads((HERE/'inputs.json').read_text())
APP='com.irisai.releasechecks'
GAME='com.supercell.brawlstars'
OUT=Path('compat-evidence')
OUT.mkdir(exist_ok=True)
def cmd(*args,timeout=120):
    p=subprocess.run(list(args),stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=timeout)
    return p.returncode,p.stdout.strip()
def adb(*args,timeout=120):
    return cmd('adb',*args,timeout=timeout)
def shell(*args):
    code,out=adb('shell',*args,timeout=35)
    if code:raise RuntimeError('shell '+str(args[:2])+': '+out[-280:])
    return out
def digest(path):
    with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def note(row):
    with (OUT/'results.jsonl').open('a') as f:f.write(json.dumps(row)+'\n')
    print(json.dumps(row),flush=True)
def wait_package_service():
    deadline=time.monotonic()+110
    while time.monotonic()<deadline:
        try:
            user=shell('am','get-current-user')
            if user.isdigit() and 'package:android' in shell('pm','list','packages','--user',user,'android').splitlines():
                return user
        except (RuntimeError,subprocess.TimeoutExpired):pass
        time.sleep(3)
    raise RuntimeError('package manager/current Android user unavailable')
def install(path,package,user):
    last=''
    for attempt in range(4):
        code,out=adb('install','--no-incremental','--user',user,'-r',str(path),timeout=240)
        if not code and 'Success' in out:
            if shell('pm','path','--user',user,package).startswith('package:'):return 'installed',''
            last='installer returned success but package unavailable for current user'
        else:
            found=re.search(r'INSTALL_(?:FAILED|PARSE_FAILED)_[A-Z0-9_]+',out)
            if found:return found.group(0),out[-350:]
            last=out[-350:]
        wait_package_service()
        time.sleep(3)
    return 'installer unavailable',last
def real_install(name,user,api,abis):
    spec=PACKAGES[name]
    path=Path('/tmp/iris-compat-game.apk')
    try:
        subprocess.run(['curl','-fsSL','--retry','3','--max-time','330','-o',str(path),spec['apkUrl']],check=True)
        assert digest(path)==spec['apkSha256'],'full APK hash'
        result,reason=install(path,GAME,user)
        note({'type':'released-apk','variant':name,'api':api,'abi':abis,'sha256':spec['apkSha256'],'install':result,'reason':reason})
        if result=='installed':
            assert api>=32
            info=shell('dumpsys','package',GAME)
            assert 'versionCode=70648' in info and 'versionName=70.648' in info
            code,msg=adb('uninstall',GAME);assert code==0 and 'Success' in msg,msg
        elif result=='INSTALL_FAILED_NO_MATCHING_ABIS' and 'arm64-v8a' not in abis:
            # Some Android 9/10 installers reject the CPU before checking minSdk.
            # The ABI-independent APK below exercises the SDK check on those devices.
            pass
        elif api<32:assert result=='INSTALL_FAILED_OLDER_SDK',reason
        else:raise RuntimeError(name+' install: '+result+' '+reason)
    finally:path.unlink(missing_ok=True)
def launch(user,variant,action):
    token=uuid.uuid4().hex[:16]
    adb('shell','am','force-stop','--user',user,APP)
    args=['am','start','-W','--user',user,'-n',APP+'/com.supercell.brawlstars.GameApp',
          '--es','variant',variant,'--es','action',action,'--es','token',token]
    start=shell(*args)
    if 'Error' in start:raise RuntimeError(start)
    deadline=time.monotonic()+30
    while time.monotonic()<deadline:
        _,output=adb('logcat','-d','-s','IrisReleaseQA:I')
        for line in output.splitlines():
            if token+' FAIL ' in line:raise RuntimeError(line[-420:])
            if token+' PASS ' in line:return line.split(token+' PASS ',1)[1]
        time.sleep(.3)
    raise RuntimeError('no result before deadline: '+variant+' '+action+' '+start[-150:])
def series(user,variant,restarts,iteration):
    assert 'Success' in shell('pm','clear','--user',user,APP)
    actions=['initial','bad-download'] + ['resume']*restarts+['interrupt','rollback']
    results=[]
    for action in actions:results.append(launch(user,variant,action))
    note({'type':'panel-update-cycle','variant':variant,'cycle':iteration,'checks':results})
def main():
    args=argparse.ArgumentParser()
    args.add_argument('--soak-until',type=float,default=0)
    args.add_argument('--skip-apks',action='store_true')
    p=args.parse_args()
    user=wait_package_service()
    api=int(shell('getprop','ro.build.version.sdk'));abis=shell('getprop','ro.product.cpu.abilist').split(',')
    assert api==int(os.environ['EXPECTED_API'])
    note({'type':'environment','api':api,'abis':abis,'user':user})
    errors=[]
    if not p.skip_apks:
        for k in PACKAGES:
            try:real_install(k,user,api,abis)
            except Exception as e:
                note({'type':'apk-test-error','variant':k,'error':str(e)[:480]})
                errors.append(k+': '+str(e))
                adb('uninstall',GAME)
    result,detail=install(Path('/tmp/iris-platform-qa.apk'),APP,user)
    note({'type':'abi-independent-installer','api':api,'install':result,'reason':detail})
    if api<32:
        if result!='INSTALL_FAILED_OLDER_SDK':raise RuntimeError('minSdk probe: '+result+' '+detail)
    else:
        if result!='installed':raise RuntimeError('test app: '+result+' '+detail)
        i=0
        densities=[None,'300','400','360','440']
        try:
            while True:
                if p.soak_until:
                    setting=densities[i%len(densities)] or 'reset'
                    shell('wm','density',setting)
                    shell('settings','put','system','font_scale',str([1.0,1.15,1.3][i%3]))
                    note({'type':'display-configuration','cycle':i,'density':setting,'fontScale':[1.0,1.15,1.3][i%3]})
                for variant in PACKAGES:series(user,variant,3 if not p.soak_until else 2+i%4,i)
                i+=1
                if time.time()>=p.soak_until: break
        finally:
            if p.soak_until:
                adb('shell','wm','density','reset')
                adb('shell','settings','put','system','font_scale','1.0')
    if errors:raise RuntimeError('; '.join(errors))
if __name__=='__main__':
    try:main()
    except Exception as e:
        note({'type':'failure','message':str(e)[:600]})
        # The emulator has only QA data. Preserve system failure clues for diagnosis.
        for key,args in [('space',['shell','df','-h','/data']),('users',['shell','pm','list','users']),('crash',['logcat','-d','-b','crash','-t','200'])]:
            try:(OUT/(key+'.txt')).write_text(adb(*args,timeout=12)[1][-24000:])
            except Exception:pass
        raise
