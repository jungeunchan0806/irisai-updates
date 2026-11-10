import {test} from 'node:test';
import assert from 'node:assert/strict';
import {Gate} from '../src/gate.js';
function setup(){let now=1000;const g=new Gate(()=>now);return {g,advance:n=>now+=n};}
function command(epoch,sequence=0){return {epoch,sequence,x1:.2,y1:.8,x2:.3,y2:.7,duration:160};}
test('starts off',()=>{const {g}=setup();assert.equal(g.accept(command(0)),false);});
test('accepts one fresh finite bounded gesture',()=>{const {g}=setup();assert.equal(g.accept(command(g.arm(true))),true);});
test('OFF/ON invalidates queued commands',()=>{const {g}=setup();const old=g.arm(true);g.arm(false);g.arm(true);assert.equal(g.accept(command(old)),false);});
test('sequence replay rejected',()=>{const {g}=setup();const epoch=g.arm(true);assert.equal(g.accept(command(epoch)),true);assert.equal(g.accept(command(epoch)),false);});
test('missed heartbeat disables acceptance',()=>{const {g,advance}=setup();const epoch=g.arm(true);advance(1001);assert.equal(g.accept(command(epoch)),false);});
test('heartbeat must match current session',()=>{const {g,advance}=setup();const epoch=g.arm(true);advance(900);assert.equal(g.ping(epoch-1),false);assert.equal(g.ping(epoch),true);advance(900);assert.equal(g.accept(command(epoch)),true);});
test('malformed or unbounded gesture rejected',()=>{for(const [key,value] of [['x1',NaN],['x2',2],['y1',-1],['duration',Infinity],['duration',181],['duration',0],['sequence',.2]]){const {g}=setup();const c=command(g.arm(true));c[key]=value;assert.equal(g.accept(c),false,key);}});

test('clock rollback rejects a queued gesture',()=>{const {g,advance}=setup();const epoch=g.arm(true);advance(-1);assert.equal(g.accept(command(epoch)),false);});
