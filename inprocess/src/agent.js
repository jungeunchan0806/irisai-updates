import Java from 'frida-java-bridge';
import { Gate } from './gate.js';

// Research input backend. No game native offsets, patches, protection bypasses,
// OS-wide InputManager injection, filesystem harvesting, or network listeners.
const supported=new Set(['com.supercell.brawlstars','com.irisai.inputprobe']);
const gate=new Gate(()=>Date.now());
let initialized=false, initialization=null, closed=false, MotionEvent, Clock, appPackage, current=null, active=null, watchdog=null;
const timers=new Set();

function onMain(fn) {
  return new Promise((resolve,reject)=>Java.perform(()=>Java.scheduleOnMainThread(()=>{
    try { resolve(fn()); } catch(e) { reject(new Error(String(e))); }
  })));
}
async function initialize() {
  if(closed)throw new Error('Input session has been shut down');
  if(initialized)return;
  if(initialization!==null)return initialization;
  initialization=(async()=>{
    await onMain(()=>{
      const app=Java.use('android.app.ActivityThread').currentApplication();
      if(app===null)throw new Error('Application is not ready');
      appPackage=String(app.getPackageName());
      if(!supported.has(appPackage))throw new Error('Unsupported package: '+appPackage);
      MotionEvent=Java.use('android.view.MotionEvent');Clock=Java.use('android.os.SystemClock');
      initialized=true;
    });
    watchdog=setInterval(()=>{
      const stroke=active;
      if(stroke && !gate.valid(stroke.epoch))onMain(()=>{if(active===stroke)cancel();}).catch(()=>{});
    },100);
  })();
  try{await initialization;}finally{initialization=null;}
}
function releaseActivity(){if(current!==null){current.$dispose();current=null;}}
async function locateActivity() {
  await initialize();
  const found=await new Promise((resolve,reject)=>Java.perform(()=>{
    let result=null;
    try{Java.choose('android.app.Activity',{
      onMatch(a){try{if(String(a.getPackageName())===appPackage&&!a.isFinishing()&&!a.isDestroyed()&&a.hasWindowFocus()){result=Java.retain(a);return 'stop';}}catch(_){}},
      onComplete(){resolve(result);}
    });}catch(e){reject(e);}
  }));
  await onMain(()=>{if(active)cancel();releaseActivity();current=found;});
  return status();
}
function foreground(){
  if(current===null)return false;
  try{return !current.isFinishing()&&!current.isDestroyed()&&current.hasWindowFocus()&&current.getWindow().getDecorView().isShown();}catch(_){return false;}
}
function emit(action,x,y,downAt){
  const now=Clock.uptimeMillis();
  const event=MotionEvent.obtain.overload('long','long','int','float','float','int').call(MotionEvent,downAt,now,action,x,y,0);
  try{event.setSource(0x1002);return Boolean(current.dispatchTouchEvent(event));}finally{event.recycle();}
}
function cancel(){
  for(const id of timers)clearTimeout(id);timers.clear();
  if(active!==null){const a=active;active=null;try{emit(3,a.x,a.y,a.downAt);}catch(_){} }
}
function delayed(stroke,fn,ms){
  const id=setTimeout(()=>{
    timers.delete(id);
    onMain(()=>{if(active===stroke)fn();}).catch(()=>onMain(()=>{if(active===stroke)cancel();}).catch(()=>{}));
  },ms);timers.add(id);
}
async function status(){
  await initialize();
  return onMain(()=>({package:appPackage,foreground:foreground(),enabled:gate.enabled,epoch:gate.epoch,busy:active!==null,activity:current===null?null:String(current.getClass().getName()),backend:'in-process Activity.dispatchTouchEvent',brawlFriendlyBattleVerified:false}));
}
async function arm(enabled){await initialize();return onMain(()=>{if(closed)throw new Error('Input session has been shut down');cancel();return gate.arm(enabled);});}
async function gesture(command){
  await initialize();
  return onMain(()=>{
    if(closed)return {accepted:false,reason:'session_closed'};
    if(!gate.accept(command))return {accepted:false,reason:'invalid_or_stale_command'};
    if(!foreground())return {accepted:false,reason:'game_not_foreground'};
    if(active!==null)return {accepted:false,reason:'gesture_busy'};
    const view=current.getWindow().getDecorView();const w=view.getWidth(),h=view.getHeight();
    if(w<=h||h<1)return {accepted:false,reason:'landscape_required'};
    const x1=Math.min(w-1,command.x1*w),y1=Math.min(h-1,command.y1*h),x2=Math.min(w-1,command.x2*w),y2=Math.min(h-1,command.y2*h);
    const downAt=Clock.uptimeMillis();
    const stroke={epoch:command.epoch,downAt,x:x1,y:y1};active=stroke;
    try{
      if(!emit(0,x1,y1,downAt)){cancel();return {accepted:false,reason:'view_rejected_down'};}
      for(const fraction of [.15,.5])delayed(stroke,()=>{
        if(!active)return;
        if(!foreground()||!gate.valid(command.epoch)){cancel();return;}
        active.x=x1+(x2-x1)*fraction;active.y=y1+(y2-y1)*fraction;emit(2,active.x,active.y,downAt);
      },Math.round(command.duration*fraction));
      delayed(stroke,()=>{
        if(!active)return;
        if(!foreground()||!gate.valid(command.epoch)){cancel();return;}
        try{emit(2,x2,y2,downAt);emit(1,x2,y2,downAt);active=null;}catch(e){cancel();throw e;}
      },command.duration);
      return {accepted:true};
    }catch(e){cancel();throw e;}
  });
}
async function shutdown(){
  closed=true;gate.arm(false);
  if(initialization!==null){try{await initialization;}catch(_){}}
  if(watchdog!==null){clearInterval(watchdog);watchdog=null;}
  if(initialized)await onMain(()=>{gate.arm(false);cancel();releaseActivity();});
  return true;
}
rpc.exports={initialize,locateActivity,status,arm,gesture,heartbeat:async epoch=>{await initialize();return onMain(()=>gate.ping(epoch));},shutdown};
