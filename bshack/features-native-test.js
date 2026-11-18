
let calls=0;
const apply=new NativeCallback((ctx,config)=>{calls++;ctx.add(0x68).writeByteArray(config.readByteArray(88));},'void',['pointer','pointer']);
const state=Memory.alloc(1024);state.writeByteArray(new Uint8Array(1024));
const bridge=new CModule(SOURCE,{iris_apply:apply,iris_state:state});
const render=new NativeFunction(bridge.render,'void',['pointer','pointer','float','float','int','int','int']);
const submit=new NativeFunction(bridge.submit,'int',['int','int']);
const snapshot=new NativeFunction(bridge.snapshot,'void',['pointer']);
const ctx=Memory.alloc(0x200),out=Memory.alloc(112);
ctx.writeByteArray(new Uint8Array(0x200));ctx.add(0xe0).writePointer(ptr(1));
ctx.add(0x68+3).writeU8(1);ctx.add(0x68+11).writeU8(1);
ctx.add(0x128).writePointer(ptr(0x1000));ctx.add(0x130).writePointer(ptr(0x1020));
function check(v,message){if(!v)throw Error(message);}
rpc.exports.run=function(){
  check(!submit(3,1),'Excluded farm accepted');check(!submit(11,1),'Duplicate goal accepted');
  check(!submit(-1,1)&&!submit(88,1)&&!submit(1,2),'Invalid request accepted');
  // A context API can exist while BRF still installs game hooks. Never
  // call its config callback or let Iris attach game hooks at this point.
  for(const [progress,interactive] of [[0,0],[85,0],[99,1],[100,0]]) {
    render(ctx,ptr(0),1280,720,progress,60,interactive);snapshot(out);
    check(out.readS32()===0,'Loading frame incorrectly reports native initialization complete: '+progress+'/'+interactive);
    check(calls===0,'Configuration applied while native initialization is incomplete');
    check(out.add(104).readS32()===progress&&out.add(108).readS32()===interactive,'Initialization diagnostics disagree with the native signal');
  }
  check(submit(1,1),'Pending setting rejected');
  render(ctx,ptr(0),1280,720,90,60,0);snapshot(out);
  check(calls===0,'Queued configuration applied during initialization');
  render(ctx,ptr(0),1280,720,100,120,1);snapshot(out);
  check(ctx.add(0x69).readU8()===1,'Pending setting lost before initialization completed');
  check(out.readS32()===1&&out.add(8).readS32()===120,'Native readiness/FPS');
  check(out.add(104).readS32()===100&&out.add(108).readS32()===1,'Completed initialization diagnostics');
  check(ctx.add(0x68+3).readU8()===0,'Original farm not disabled');
  check(ctx.add(0x68+11).readU8()===0,'Duplicate BRF goal remained enabled');
  check(ctx.add(0x128).readPointer().equals(ctx.add(0x130).readPointer()),'Native menu touch controls remain');
  const slots=[1,2,4,5,9,14,16,17,18,19,25,26,48,49,50,51,52,53,54,55];
  for(const slot of slots){check(submit(slot,1),'Rejected valid feature');render(ctx,ptr(0),1280,720,100,60,1);snapshot(out);check(out.add(16+slot).readU8()===1,'Feature not applied');check(submit(slot,0),'Rejected OFF');render(ctx,ptr(0),1280,720,100,60,1);snapshot(out);check(out.add(16+slot).readU8()===0,'Feature did not stop');}
  for(const [slot,value] of [[56,100],[60,250],[64,121]]){check(submit(slot,value),'Setting rejected');render(ctx,ptr(0),1280,720,100,60,1);snapshot(out);check(out.add(16+slot).readS32()===value,'Setting not applied');}
  const before=calls;for(let i=0;i<120;i++)render(ctx,ptr(0),1280,720,100,60,1);
  check(calls===before,'Unchanged frames repeatedly write configuration');
  snapshot(out);
  return {boolean_features_and_settings:slots.length,numeric_settings:3,render_frames:out.add(4).readS32(),loading_guard:true,queued_settings_preserved:true,excluded_farm_off:true,duplicate_brf_goal_off:true,engine:'real Frida CModule, fake config callback; Iris goal checked separately by runtime.test.mjs'};
};
