"""Build the admin apps. Signing material is supplied from outside this repository."""
import argparse, os, shutil, subprocess, zipfile
from pathlib import Path

p=argparse.ArgumentParser()
for name in ['sdk-tools','android-jar','ecj','signing-key','signing-cert','out-dir']:
 p.add_argument('--'+name,required=True,type=Path)
p.add_argument('--go-tool',default='go')
args=p.parse_args()
root=Path(__file__).resolve().parent
build=root/'build'
build.mkdir(exist_ok=True)
(build/'classes').mkdir(exist_ok=True)
args.out_dir.mkdir(parents=True,exist_ok=True)
def run(command,**kwargs):subprocess.run([str(x) for x in command],check=True,**kwargs)
run(['java','-jar',args.ecj,'-source','8','-target','8','-encoding','UTF-8','-bootclasspath',str(args.android_jar)+os.pathsep+str(args.sdk_tools/'core-lambda-stubs.jar'),'-d',build/'classes',root/'android/MainActivity.java'])
run(['java','-cp',args.sdk_tools/'lib/d8.jar','com.android.tools.r8.D8','--lib',args.android_jar,'--min-api','23','--output',build,*build.glob('classes/**/*.class')])
run([args.sdk_tools/'aapt2','compile','--dir',root/'android/res','-o',build/'resources.zip'])
run([args.sdk_tools/'aapt2','link','-I',args.android_jar,'--manifest',root/'android/AndroidManifest.xml','-o',build/'base.apk',build/'resources.zip'])
shutil.copy2(build/'base.apk',build/'unsigned.apk')
with zipfile.ZipFile(build/'unsigned.apk','a',compression=zipfile.ZIP_DEFLATED) as z:z.write(build/'classes.dex','classes.dex')
run([args.sdk_tools/'zipalign','-f','4',build/'unsigned.apk',build/'aligned.apk'])
apk=args.out_dir/'BSHACK-Admin-Android-1.2.0.apk'
run(['java','-jar',args.sdk_tools/'lib/apksigner.jar','sign','--v4-signing-enabled','false','--key',args.signing_key,'--cert',args.signing_cert,'--out',apk,build/'aligned.apk'])
run(['java','-jar',args.sdk_tools/'lib/apksigner.jar','verify','--verbose',apk])
run([args.sdk_tools/'zipalign','-c','4',apk])
run([args.go_tool,'build','-buildvcs=false','-trimpath','-ldflags=-H windowsgui -s -w','-o',args.out_dir/'BSHACK-Admin-Windows-1.2.0.exe','.'],cwd=root/'windows',env={**os.environ,'GOOS':'windows','GOARCH':'amd64','CGO_ENABLED':'0'})
