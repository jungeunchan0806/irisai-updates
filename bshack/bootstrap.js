// Load only the engine and UI bundled in this APK.
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
  const selected={version:22500,name:'2.2.5-static',engine:'',ui:'',recovery:''};
  Object.defineProperty(globalThis,'__irisBoot',{value:Object.freeze(selected),writable:false,configurable:false});
  try{
   const input=app.getAssets().open('iris-engine.mjs');
   const source=String(_bootJava.use('java.lang.String').$new(_bootRead(input),'UTF-8'));
   Script.load('/iris-engine.mjs',source).catch(e=>console.error('[IRIS BOOT] Engine load: '+e));
  }catch(e){console.error('[IRIS BOOT] Engine read: '+e);}
 });
}
setImmediate(_bootStart);
