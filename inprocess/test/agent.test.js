import {test} from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFileSync} from 'node:fs';
import {Gate} from '../src/gate.js';

// Execute the actual agent against a deterministic Java/main-loop test double.
// These tests cover scheduling contracts, not Android or Brawl compatibility.
const source=readFileSync(new URL('../src/agent.js',import.meta.url),'utf8').replace(/^import .*;\n/gm,'');
function harness(){
  const main=[],timers=new Map(),events=[];let next=0,now=1000,focused=true,failUp=false;
  const activity={getPackageName:()=> 'com.irisai.inputprobe',isFinishing:()=>false,isDestroyed:()=>false,hasWindowFocus:()=>focused,
    getWindow:()=>({getDecorView:()=>({isShown:()=>true,getWidth:()=>960,getHeight:()=>540})}),getClass:()=>({getName:()=> 'ProbeActivity'}),$dispose(){},
    dispatchTouchEvent(e){if(failUp&&e.action===1)throw new Error('UP rejected');events.push({action:e.action,x:e.x,y:e.y});return true;}};
  const java={perform:fn=>fn(),scheduleOnMainThread:fn=>main.push(fn),retain:a=>a,
    choose(name,{onMatch,onComplete}){onMatch(activity);onComplete();},
    use(name){
      if(name==='android.app.ActivityThread')return {currentApplication:()=>activity};
      if(name==='android.os.SystemClock')return {uptimeMillis:()=>now};
      if(name==='android.view.MotionEvent')return {obtain:{overload:()=>({call:(self,downAt,at,action,x,y)=>({action,x,y,setSource(){},recycle(){}})})}};
      throw new Error(name);
    }};
  const context={Java:java,Gate,rpc:{},Date:{now:()=>now},setTimeout:fn=>{const id=++next;timers.set(id,fn);return id;},clearTimeout:id=>timers.delete(id),setInterval:()=>-1,clearInterval(){}};
  vm.runInNewContext(source,context);
  async function settle(p){let done=false,result,error;p.then(v=>{done=true;result=v;},e=>{done=true;error=e;});for(let i=0;i<100&&!done;i++){await Promise.resolve();if(main.length)main.shift()();}if(!done)throw new Error('Main loop did not settle');if(error)throw error;return result;}
  async function enqueue(p){p.catch(()=>{});for(let i=0;i<4;i++)await Promise.resolve();return {p};}
  return {api:context.rpc.exports,main,timers,events,settle,enqueue,focus:value=>focused=value,failUp:()=>failUp=true};
}
const command=epoch=>({epoch,sequence:0,x1:.2,y1:.8,x2:.3,y2:.7,duration:160});
async function ready(){const h=harness();await h.settle(h.api.locateActivity());const epoch=await h.settle(h.api.arm(true));return {...h,epoch};}
test('old scheduled callback cannot cancel a newly enabled gesture',async()=>{
  const h=await ready();await h.settle(h.api.gesture(command(h.epoch)));const oldTimer=h.timers.values().next().value;
  const off=await h.enqueue(h.api.arm(false));const on=await h.enqueue(h.api.arm(true));const newer=await h.enqueue(h.api.gesture(command(h.epoch+2)));
  oldTimer();await h.settle(off.p);await h.settle(on.p);await h.settle(newer.p);await h.settle(h.api.status());
  assert.deepEqual(h.events.map(e=>e.action),[0,3,0]);assert.equal((await h.settle(h.api.status())).busy,true);
});
test('failed UP attempts CANCEL instead of abandoning a held pointer',async()=>{
  const h=await ready();await h.settle(h.api.gesture(command(h.epoch)));h.failUp();const finish=[...h.timers.values()].at(-1);finish();await h.settle(h.api.status());
  assert.equal(h.events.at(-1).action,3);assert.equal((await h.settle(h.api.status())).busy,false);
});
test('shutdown wins over a pending re-enable',async()=>{
  const h=await ready();const arm=await h.enqueue(h.api.arm(true));const shutdown=h.api.shutdown();await assert.rejects(h.settle(arm.p),/shut down/);await h.settle(shutdown);await assert.rejects(h.settle(h.api.gesture(command(h.epoch+1))),/shut down/);assert.equal(h.events.length,0);
});
test('other foreground activity gets no injected events',async()=>{
  const h=await ready();h.focus(false);const r=await h.settle(h.api.gesture(command(h.epoch)));assert.equal(r.accepted,false);assert.equal(r.reason,'game_not_foreground');assert.equal(h.events.length,0);
});
test('normal completion has DOWN MOVE UP ordering and releases state',async()=>{
  const h=await ready();await h.settle(h.api.gesture(command(h.epoch)));for(const callback of [...h.timers.values()])callback();await h.settle(h.api.status());assert.equal(h.events[0].action,0);assert.equal(h.events.at(-1).action,1);assert.ok(h.events.some(e=>e.action===2));assert.equal((await h.settle(h.api.status())).busy,false);
});
