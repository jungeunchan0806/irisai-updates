import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {JSDOM,VirtualConsole} from 'jsdom';

const html=fs.readFileSync(new URL('../../seller-client/panel.html',import.meta.url),'utf8');
async function until(fn){for(let n=0;n<100;n++){if(fn())return;await new Promise(r=>setTimeout(r,10));}assert.fail('UI did not settle');}
async function fixture(){
 const original={id:'buyer-fixture',key_suffix:'ABCD',hwid:'BS-'+'A'.repeat(64),note:'fixture',status:'active',duration_ms:86400000,permanent:false,expires_at:1900000000000,revoked:0};
 let rows=[{...original}],failEdit=false;const calls=[],errors=[];
 const virtualConsole=new VirtualConsole();virtualConsole.on('jsdomError',e=>errors.push(e.message));
 const dom=new JSDOM(html,{url:'https://fixture.invalid/',runScripts:'dangerously',virtualConsole,beforeParse(window){
  window.fetch=()=>{throw Error('No external network in UI fixture');};
  window.confirm=()=>{throw Error('Native WebView must not depend on window.confirm');};
  window.BshackDevice={copy(){},request(id,method,path,body){
   const payload=JSON.parse(body);calls.push({method,path,payload});let status=200,data;
   if(method==='INFO')data={platform:'apk',deviceSuffix:'fixture'};
   else if(path==='/api/seller/session')data={seller:{note:'fixture seller',permanent:true}};
   else if(method==='GET')data={licenses:rows.map(r=>({...r}))};
   else if(payload.action==='update_device'){
    if(failEdit){status=409;data={error:'이미 다른 구독에 연결된 HWID입니다.'};}
    else{rows=rows.map(r=>r.id===payload.id?{...r,hwid:payload.hwid||null,note:payload.note}:r);data={ok:true};}
   }else if(payload.action==='delete'){rows=rows.filter(r=>r.id!==payload.id);data={ok:true};}
   else if(['revoke','restore'].includes(payload.action)){rows=rows.map(r=>r.id===payload.id?{...r,status:payload.action==='revoke'?'revoked':'active'}:r);data={ok:true};}
   else throw Error('Unexpected fixture request');
   window.setTimeout(()=>window.BSHACK_RESULT(id,Buffer.from(JSON.stringify({status,data}),'utf8').toString('base64')),0);
  }};
 }});
 const w=dom.window,$=id=>w.document.getElementById(id),button=label=>[...$('licenseRows').querySelectorAll('button')].find(b=>b.textContent===label);
 await until(()=>$('licenseRows').children.length===1);
 return {w,$,button,calls,errors,original,get rows(){return rows;},failEdit(){failEdit=true;},close(){dom.window.close();}};
}
test('seller edits wrong and missing HWIDs through the native bridge without changing the term',async()=>{
 const f=await fixture();try{
  f.button('HWID 수정').click();assert.equal(f.$('editHwid').value,f.original.hwid);
  f.$('editHwid').value='BS-'+'B'.repeat(64);f.$('editNote').value='corrected';
  f.$('editForm').dispatchEvent(new f.w.Event('submit',{bubbles:true,cancelable:true}));
  await until(()=>f.$('editModal').classList.contains('hidden'));
  assert.equal(f.rows[0].hwid,'BS-'+'B'.repeat(64));assert.equal(f.rows[0].expires_at,f.original.expires_at);
  assert.deepEqual(f.calls.find(c=>c.payload.action==='update_device'),{method:'POST',path:'/api/seller/licenses',payload:{action:'update_device',id:f.original.id,hwid:'BS-'+'B'.repeat(64),note:'corrected'}});
  await until(()=>!f.$('saveEdit').disabled);f.button('HWID 수정').click();f.$('editHwid').value='';
  f.$('editForm').dispatchEvent(new f.w.Event('submit',{cancelable:true}));await until(()=>f.$('editModal').classList.contains('hidden'));
  assert.equal(f.rows[0].hwid,null);assert.equal(f.rows[0].expires_at,f.original.expires_at);assert.deepEqual(f.errors,[]);
 }finally{f.close();}
});
test('duplicate HWID feedback preserves the edit form and permits correction',async()=>{
 const f=await fixture();try{
  f.failEdit();f.button('HWID 수정').click();f.$('editHwid').value='BS-'+'C'.repeat(64);
  f.$('editForm').dispatchEvent(new f.w.Event('submit',{cancelable:true}));await until(()=>!f.$('saveEdit').disabled);
  assert.equal(f.$('editModal').classList.contains('hidden'),false);assert.match(f.$('editError').textContent,/이미 다른/);
  assert.equal(f.rows[0].hwid,f.original.hwid);assert.deepEqual(f.errors,[]);
 }finally{f.close();}
});
test('seller suspension and permanent deletion use the in-page controls and remove the row',async()=>{
 const f=await fixture();try{
  f.button('정지').click();await until(()=>f.button('정지 해제'));
  assert.equal(f.rows[0].status,'revoked');f.button('정지 해제').click();await until(()=>f.button('정지'));
  f.button('완전 삭제').click();assert.equal(f.$('deleteModal').classList.contains('hidden'),false);
  assert.equal(f.calls.some(c=>c.payload.action==='delete'),false);
  f.$('cancelDelete').click();assert.equal(f.rows.length,1);
  f.button('완전 삭제').click();f.$('confirmDelete').click();await until(()=>f.$('licenseRows').children.length===0);
  assert.equal(f.rows.length,0);assert.equal(f.$('deleteModal').classList.contains('hidden'),true);assert.deepEqual(f.errors,[]);
 }finally{f.close();}
});
