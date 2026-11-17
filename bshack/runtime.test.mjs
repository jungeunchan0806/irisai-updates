import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs';
const bundle=fs.readFileSync(new URL('./licensing_bundle.js',import.meta.url),'utf8');
function core(){const c=vm.createContext({init_node_globals(){},__esm:f=>()=>Object.values(f)[0]()});vm.runInContext(bundle+';init_core();',c);return c;}
test('monotonic leases expire, reject malformed server leases and accept explicit revocation',()=>{
 const c=core();let now=500; c.now=()=>now;
 const lease=vm.runInContext('new Lease(now)',c);
 assert.equal(lease.active(),false);
 lease.accept({active:true,permanent:true,serverTime:1000,leaseSeconds:300});assert.equal(lease.active(),true);
 now+=300000;assert.equal(lease.active(),false);
 assert.throws(()=>lease.accept({active:true,permanent:true,serverTime:1000,leaseSeconds:301}));
 assert.throws(()=>lease.accept({active:true,permanent:false,serverTime:1000,expiresAt:1500,leaseSeconds:1}));
 lease.accept({active:true,permanent:true,serverTime:1000,leaseSeconds:5});lease.accept({active:false,status:'revoked'});assert.equal(lease.active(),false);
});
test('native callback status uses injected monotonic clock without invoking Android Java',()=>{
 const c=core();let javaCalls=0;c.Java=new Proxy({}, {get(){javaCalls++;throw Error('Java entered on native callback');}});c.handlers={disabled(){},notice(){}};c.options={now:()=>100,later(){},delay(){},repeat(){}};
 const license=vm.runInContext('createLicense(Java,handlers,options)',c);
 assert.equal(license.isActive(),false);license.status();license.press();assert.equal(javaCalls,0);
});
function panelFixture({ready=true,started=true,active=false}={}) {
 let command=0,foreground=true,phase='',taps=0;
 const auth={ready,error:null,pending:false,active};
 const c=vm.createContext({console,setInterval(){},_st:{started,disposed:false,requested:null},_ag:{enabled:false,phase:'idle'},_MS:{checked:0},_license:{status:()=>auth,tap(){taps++;},isActive:()=>auth.active},frida_java_bridge_default:{perform:f=>f(),scheduleOnMainThread:f=>f()}});
 vm.runInContext(fs.readFileSync(new URL('./engine_state.js',import.meta.url),'utf8')+'\n'+fs.readFileSync(new URL('./panel_bridge.js',import.meta.url),'utf8'),c);
 c.fake={isResumed:()=>foreground,poll:()=>{const n=command;command=0;return n;},update:(enabled,p)=>{phase=p;}};
 vm.runInContext('_panelHost=fake;',c);
 return {c,auth,poll(n=0){command=n;vm.runInContext('_panelPoll()',c);},activate(){vm.runInContext('_panelActivate()',c);},background(){foreground=false;vm.runInContext('_panelAttempts=40;',c);},get taps(){return taps;},get phase(){return phase;}};
}
test('queued ON reaches the real engine state without requiring a render callback',()=>{
 const f=panelFixture();f.poll(1);assert.equal(f.taps,1);assert.equal(f.phase,'인증 확인 중');assert.equal(f.c._ag.enabled,false);
 f.auth.active=true;f.activate();f.poll();assert.equal(f.c._ag.enabled,true);assert.equal(f.c._ag.phase,'findBall');assert.equal(f.c._st.requested,null);
 f.poll(1);assert.equal(f.c._ag.enabled,false);assert.equal(f.c._ag.stopPending,true);
});
test('a loading-time tap waits for both game and authorization readiness and runs once',()=>{
 const f=panelFixture({ready:false,started:false});f.poll(1);assert.equal(f.taps,0);
 f.c._st.started=true;f.poll();assert.equal(f.taps,0);
 f.auth.ready=true;f.poll();assert.equal(f.taps,1);f.poll();assert.equal(f.taps,1);
 f.auth.active=true;f.activate();f.poll();assert.equal(f.c._ag.enabled,true);
});
test('cancelled initialization and delayed authorization cannot re-enable auto goal',()=>{
 const f=panelFixture({ready:false,started:false});f.poll(1);f.poll(1);f.c._st.started=true;f.auth.ready=true;f.poll();assert.equal(f.taps,0);
 f.poll(1);assert.equal(f.taps,1);f.poll(1);f.auth.active=true;f.activate();f.poll();assert.equal(f.c._ag.enabled,false);
});
test('backgrounding and lease expiry stop input even with the render loop idle',()=>{
 const f=panelFixture({active:true});f.poll(1);f.activate();f.poll();assert.equal(f.c._ag.enabled,true);
 f.auth.active=false;f.poll();assert.equal(f.c._ag.enabled,false);assert.equal(f.c._ag.stopPending,true);
 f.auth.active=true;f.poll(1);f.activate();f.poll();assert.equal(f.c._ag.enabled,true);f.background();f.poll();assert.equal(f.c._ag.enabled,false);
});
