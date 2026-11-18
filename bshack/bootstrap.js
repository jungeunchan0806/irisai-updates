// The bootstrap and verifier are installed with the APK, never downloaded.
// Only authenticated engine/UI files are selected. The running engine is never hot-swapped.
init_frida_java_bridge();
const _bootJava=frida_java_bridge_default;
Object.defineProperty(globalThis,'__irisJava',{value:_bootJava,writable:false,configurable:false});
let _bootStarted=false;
function _bootRead(input) {
 const output=_bootJava.use('java.io.ByteArrayOutputStream').$new();
 const buffer=_bootJava.array('byte',new Array(8192).fill(0));
 try{let n;while((n=input.read(buffer))>0)output.write(buffer,0,n);return output.toByteArray();}
 finally{input.close();output.close();}
}
function _bootStart(){
 if(_bootStarted)return;
 _bootJava.perform(()=>{
  const app=_bootJava.use('android.app.ActivityThread').currentApplication();
  if(!app){setTimeout(_bootStart,200);return;}
  _bootStarted=true;
  let selected={version:23000,name:'2.3.0',engine:'',ui:'',recovery:''};
  try{
   const bytes=_bootRead(app.getAssets().open('iris-bootstrap.dex'));
   const loader=_bootJava.use('dalvik.system.InMemoryDexClassLoader').$new(_bootJava.use('java.nio.ByteBuffer').wrap(bytes),app.getClassLoader());
   const verifier=_bootJava.ClassFactory.get(loader).use('com.irisai.bshack.update.Bootstrap');
   selected=JSON.parse(String(verifier.select(_bootJava.cast(app,_bootJava.use('android.app.Application')))));
  }catch(e){selected.recovery='업데이트 검사 실패 · 기본 버전 실행: '+e;console.error('[IRIS BOOT] '+e);}
  Object.defineProperty(globalThis,'__irisBoot',{value:Object.freeze(selected),writable:false,configurable:false});
  try{
   const input=selected.engine?_bootJava.use('java.io.FileInputStream').$new(selected.engine):app.getAssets().open('iris-engine.mjs');
   const source=String(_bootJava.use('java.lang.String').$new(_bootRead(input),'UTF-8'));
   Script.load('/iris-engine.mjs',source).catch(e=>console.error('[IRIS BOOT] Engine load: '+e));
  }catch(e){console.error('[IRIS BOOT] Engine read: '+e);}
 });
}
setImmediate(_bootStart);
