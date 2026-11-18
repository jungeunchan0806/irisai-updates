function onUi(fn){return new Promise((resolve,reject)=>_Java.perform(()=>_Java.scheduleOnMainThread(()=>{try{resolve(fn());}catch(e){reject(String(e));}})));}
rpc.exports={...rpc.exports,
 state(){return {enabled:_ag.enabled,error:_panelError,phase:_panelPhase(),foreground:_panelForeground,auth:_license.status()};},
 connection(){return {..._connection.status(),enabled:_ag.enabled,inputs:_fixtureInputs};},
 link(mode){return _fixtureLink(mode);},
 grant(){return onUi(()=>{
  // Fixture-only server outcome injection. This code is never included in the game APK.
  const hf=_panelHost.class.getDeclaredField('host');hf.setAccessible(true);const host=hf.get(null);
  const lf=_panelHost.class.getDeclaredField('license');lf.setAccessible(true);const license=lf.get(host);
  const type=license.getClass();
  for(const [key,value] of [['until',Number(_Java.use('android.os.SystemClock').elapsedRealtime())+60000],['checked',true],['status','active']]){
   const f=type.getDeclaredField(key);f.setAccessible(true);
   if(key==='until')f.setLong(license,value);else if(key==='checked')f.setBoolean(license,value);else f.set(license,_Java.use('java.lang.String').$new(value));
  }
  return true;
 });},
 click(){return onUi(()=>{
  const records=_Java.use('android.app.ActivityThread').currentActivityThread().mActivities.value;
  const it=records.values().iterator();
  function find(v){
   const d=v.getContentDescription();if(d&&['오토골 켜기 또는 끄기','Toggle Auto Goal'].includes(String(d))){v.performClick();return true;}
   if(_Java.use('android.view.ViewGroup').class.isInstance(v)){
    const g=_Java.cast(v,_Java.use('android.view.ViewGroup'));
    for(let i=0;i<g.getChildCount();i++)if(find(g.getChildAt(i)))return true;
   }return false;
  }
  while(it.hasNext()){
   const r=_Java.cast(it.next(),_Java.use('android.app.ActivityThread$ActivityClientRecord'));
   if(!r.paused.value&&find(r.activity.value.getWindow().getDecorView()))return true;
  }return false;
 });},
 failonce(){const host=_panelHost;let fail=true;_panelHost=new Proxy(host,{get(target,key){if(key==='poll'&&fail){fail=false;throw Error('fixture recoverable UI failure');}return target[key];}});return true;}
};
