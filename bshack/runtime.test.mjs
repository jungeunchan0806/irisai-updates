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
test('panel queues authentication without losing intent; second tap cancels and leaving game stops',()=>{
 let command=1,foreground=true,phase='',tap=0;
 const c=vm.createContext({console,setInterval(){},_st:{started:true,disposed:false,requested:null},_ag:{enabled:false,phase:'idle'},_license:{status:()=>({ready:true,error:null,pending:false}),tap(){tap++;},isActive:()=>false},frida_java_bridge_default:{perform:f=>f(),scheduleOnMainThread:f=>f()}});
 vm.runInContext(fs.readFileSync(new URL('./panel_bridge.js',import.meta.url),'utf8'),c);
 c.fake={isResumed:()=>foreground,poll:()=>{const n=command;command=0;return n;},update:(enabled,p)=>{phase=p;}};vm.runInContext('_panelHost=fake;_panelPoll()',c);
 assert.equal(tap,1);assert.equal(phase,'인증 확인 중');assert.equal(c._st.requested,null);
 command=1;vm.runInContext('_panelPoll()',c);assert.equal(c._st.requested,false);assert.equal(tap,1);
 command=1;vm.runInContext('_panelPoll()',c);assert.equal(tap,2);
 foreground=false;vm.runInContext('_panelAttempts=40;_panelPoll()',c);assert.equal(c._st.requested,false);
});
