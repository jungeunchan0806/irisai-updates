"""Fresh-emulator smoke checks only. Test-signed APKs are NEVER release artifacts."""
from pathlib import Path
import os,subprocess,time,json,xml.etree.ElementTree as E

out=Path('panel-app-checks');out.mkdir(exist_ok=True)
prepared=Path(os.environ['RUNNER_TEMP'])/'prepared-panels'
results=[]
def adb(*args):return subprocess.check_output(['adb',*args],text=True,stderr=subprocess.STDOUT)
for role,package,expected in [('Seller','com.bshack.sellerpanel','판매자 패널 활성화'),('Admin','com.bshack.licensepanel','로그인')]:
 apk=prepared/(role+'-fixture-only.apk')
 adb('install','-r',str(apk));adb('logcat','-c')
 adb('shell','am','start','-W','-n',package+'/.MainActivity')
 deadline=time.monotonic()+45;xml='';labels=[]
 while time.monotonic()<deadline:
  time.sleep(2)
  try:
   adb('shell','uiautomator','dump','/sdcard/panel-check.xml')
   xml=adb('shell','cat','/sdcard/panel-check.xml')
   labels=[v.attrib.get('text','')+' '+v.attrib.get('content-desc','') for v in E.fromstring(xml).iter('node')]
   if any(expected in v for v in labels):break
  except (subprocess.CalledProcessError,E.ParseError):pass
 (out/(role+'-ui.xml')).write_text(xml)
 (out/(role+'-logcat.txt')).write_text(adb('logcat','-d'))
 with (out/(role+'.png')).open('wb') as f:subprocess.run(['adb','exec-out','screencap','-p'],stdout=f,check=True)
 ok=any(expected in v for v in labels)
 results.append({'app':role,'login_ui_visible':ok,'test_signer_only':True,'production_key_used':False,'account_login_or_license_mutation':False})
 print(json.dumps(results[-1],ensure_ascii=False))
 adb('shell','am','force-stop',package)
 if not ok:
  (out/'result.json').write_text(json.dumps(results,indent=2));raise AssertionError(role+' login/setup UI not visible')
(out/'result.json').write_text(json.dumps(results,indent=2)+'\n')
print('PASS seller/admin APK fresh-install launch (temporary fixture signing, original release key still required)')
