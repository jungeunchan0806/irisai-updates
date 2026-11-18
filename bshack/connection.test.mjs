import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs';
const source=fs.readFileSync(new URL('./connection_guard.js',import.meta.url),'utf8');
function fixture(){
 let time=10000;const c=vm.createContext({_panelTime:()=>time,NULL2:null,_bs:{snapshot:{}},_MS:{},_O:{j:76},_ag:{enabled:true,ball:{},carriedKey:'old',route:[1],ballDataNames:new Map([['old',true]])}});
 vm.runInContext(source,c);const gate=vm.runInContext('_connection',c);
 const f={c,gate,advance(ms=100){time+=ms;},sync(network='online',focused=true,generation=1){gate.sync({network,focused,generation});},
  warm(key='battle',start=1){for(let n=0;n<9;n++){f.advance();gate.observe(key,start+n);}},get state(){return gate.status();}};
 f.sync();return f;
}
test('input waits for multiple fresh ticks and a stable recovery window',()=>{
 const f=fixture();f.gate.observe('battle',1);assert.equal(f.state.ready,false);
 f.advance(700);f.gate.observe('battle',2);assert.equal(f.state.ready,false);
 f.gate.observe('battle',3);assert.equal(f.state.ready,true);
});
test('offline and Wi-Fi/mobile handoffs discard old state before resuming',()=>{
 const f=fixture();f.warm();assert.equal(f.state.ready,true);const epoch=f.state.epoch;
 f.sync('offline',true,2);assert.equal(f.state.reason,'offline');assert.equal(f.state.farmBlocked,true);
 f.warm('battle',20);assert.equal(f.state.ready,false);
 f.sync('online',true,3);assert.equal(f.state.ready,false);
 f.warm('battle',40);assert.equal(f.state.ready,true);assert.ok(f.state.epoch>epoch);
 f.sync('online',true,4);assert.equal(f.state.ready,false,'new default network needs fresh evidence even if both are online');
});
test('a frozen tick is rejected even when the game keeps rendering',()=>{
 const f=fixture();f.warm();for(let n=0;n<25;n++){f.advance();f.gate.observe('battle',9);}
 assert.equal(f.state.reason,'stalled');assert.equal(f.state.ready,false);
 f.gate.observe('battle',10);assert.equal(f.state.ready,false);f.warm('battle',11);assert.equal(f.state.ready,true);
});
test('missing callbacks, battle replacement and a backwards tick require fresh state',()=>{
 const f=fixture();f.warm();f.advance(1900);assert.equal(f.state.reason,'stalled');
 f.warm('battle',20);assert.equal(f.state.ready,true);
 f.gate.observe('different',1);assert.equal(f.state.ready,false);f.warm('different',2);assert.equal(f.state.ready,true);
 f.gate.observe('different',0);assert.equal(f.state.ready,false);
});
test('focus loss holds inputs but an idle lobby remains available to the farm',()=>{
 const f=fixture();assert.equal(f.state.reason,'waiting');assert.equal(f.state.farmBlocked,false);
 f.warm();f.sync('online',false);assert.equal(f.state.reason,'screen');assert.equal(f.state.farmBlocked,true);
 f.sync();assert.equal(f.state.ready,false);f.warm('battle',30);assert.equal(f.state.ready,true);
 f.gate.clear();assert.equal(f.state.farmBlocked,false);
});
test('recovery removes cached targets without re-enabling a user-stopped engine',()=>{
 const f=fixture();f.warm();f.c._ag.enabled=false;f.sync('offline',true,2);
 vm.runInContext('_connectionPrune()',f.c);
 assert.equal(f.c._ag.ball,null);assert.equal(f.c._ag.route,null);assert.equal(f.c._bs.snapshot,null);
 assert.equal(f.c._ag.ballDataNames.size,0);f.sync('online',true,3);f.warm('battle',20);
 assert.equal(f.state.ready,true);assert.equal(f.c._ag.enabled,false);
});
test('game hook rejects an old or unreadable battle pointer without dereferencing it',()=>{
 const f=fixture();let reads=0,tick=1;
 const pointer={isNull:()=>false,toString:()=> 'current',equals:p=>p===pointer,add(){reads++;return {readS32:()=>tick};}};
 f.c._gmb=()=>pointer;f.c._cfm=()=>true;
 f.c.old={isNull:()=>false,toString:()=> 'old',add(){throw Error('stale memory was dereferenced');}};
 assert.equal(vm.runInContext('_connectionBeforeTick(old)',f.c),false);assert.equal(reads,0);
 f.c.pointer=pointer;f.c._cfm=()=>false;assert.equal(vm.runInContext('_connectionBeforeTick(pointer)',f.c),false);assert.equal(reads,0);
 f.c._cfm=()=>true;for(let i=0;i<9;i++){f.advance();tick++;vm.runInContext('_connectionBeforeTick(pointer)',f.c);}
 assert.equal(f.state.ready,true);assert.ok(reads>0);
});
