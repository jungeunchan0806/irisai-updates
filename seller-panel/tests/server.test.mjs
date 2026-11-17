import test from 'node:test';import assert from 'node:assert/strict';import {DatabaseSync} from 'node:sqlite';import {build} from 'esbuild';import {readFile,writeFile,readdir} from 'node:fs/promises';
const sql=new DatabaseSync(':memory:');for(const name of (await readdir('drizzle')).filter(n=>n.endsWith('.sql')).sort())sql.exec(await readFile('drizzle/'+name,'utf8'));
class Statement{constructor(query,args=[]){this.query=query;this.args=args;}bind(...args){return new Statement(this.query,args);}async first(){return sql.prepare(this.query).get(...this.args)||null;}async all(){return {results:sql.prepare(this.query).all(...this.args)};}async run(){return sql.prepare(this.query).run(...this.args);}}
globalThis.TEST_ENV={DB:{prepare:q=>new Statement(q),async batch(stmts){sql.exec('BEGIN');try{const result=[];for(const s of stmts)result.push(sql.prepare(s.query).run(...s.args));sql.exec('COMMIT');return result;}catch(e){sql.exec('ROLLBACK');throw e;}}}};
const compiled=await build({stdin:{contents:"export * from './lib/license-server';export * from './lib/seller-server';export * from './lib/admin-auth';",resolveDir:process.cwd(),loader:'ts'},bundle:true,platform:'node',format:'esm',write:false,plugins:[{name:'env',setup(b){b.onResolve({filter:/^cloudflare:workers$/},()=>({path:'test-env',namespace:'env'}));b.onLoad({filter:/.*/,namespace:'env'},()=>({contents:'export const env=globalThis.TEST_ENV;'}));}}]});
await writeFile('.sites-runtime/server-test.mjs',compiled.outputFiles[0].contents);
const {admin,client,panelKeys,sellerSession,sellerLicenses,adminAuth}=await import('../.sites-runtime/server-test.mjs');
globalThis.TEST_ENV.SELLER_KEY_ENCRYPTION_KEY=btoa('t'.repeat(32));
const ORIGIN='https://test.example';let now=Date.parse('2026-11-14T06:00:00Z');const realNow=Date.now;Date.now=()=>now;
const hwid='BS-'+'A'.repeat(64),hwid2='BS-'+'B'.repeat(64);
let adminCookie='';
const req=(data,owner=false,origin=ORIGIN)=>new Request(ORIGIN+'/api',{method:'POST',headers:{'content-type':'application/json',origin,...(owner?{cookie:adminCookie}:{})},body:JSON.stringify(data)});
const A=async(data)=>{const r=await admin(req(data,true));return {status:r.status,data:await r.json()};};
const C=async(data,redeem=false)=>{const r=await client(req(data),redeem);return {status:r.status,data:await r.json()};};
const TEST_CODE='test-only-setup-code-never-used-in-production';
globalThis.TEST_ENV.ADMIN_SETUP_HASH=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(TEST_CODE))),b=>b.toString(16).padStart(2,'0')).join('');
const AUTH=async(p,c='')=>{const r=req(p);if(c)r.headers.set('cookie',c);const response=await adminAuth(r);return{status:response.status,data:await response.json(),cookie:response.headers.get('set-cookie')?.split(';')[0]};};
await test('admin requires secret setup code once; password is hashed; ChatGPT headers do not authorize',async()=>{
 assert.equal((await AUTH({action:'setup',setupCode:'wrong',username:'owner',password:'Test8pwd'})).status,403);
 for(const password of ['seven77','x'.repeat(129)])assert.equal((await AUTH({action:'setup',setupCode:TEST_CODE,username:'owner',password})).status,400);
 const setup=await AUTH({action:'setup',setupCode:TEST_CODE,username:'owner',password:'Test8pwd'});assert.equal(setup.status,200);adminCookie=setup.cookie;
 assert.equal((await AUTH({action:'setup',setupCode:TEST_CODE,username:'hijack',password:'Test8pwd'})).status,409);
 const stored=sql.prepare('SELECT password_hash FROM admin_account').get();assert.match(stored.password_hash,/^pbkdf2-sha256\$600000\$/);assert.ok(!stored.password_hash.includes('Test8pwd'));
 const forged=req({});forged.headers.set('oai-authenticated-user-id','site-owner');forged.headers.set('oai-authenticated-user-email','jungeunchan0806@gmail.com');assert.equal((await admin(forged)).status,403);
});
await test('administrator authorization and origin checks',async()=>{assert.equal((await admin(req({}))).status,403);assert.equal((await admin(req({},true,'https://evil.example'))).status,403);});
let id,key;
await test('custom 2.5 hour subscription, HWID validation and duplicate protection',async()=>{const a=await A({action:'create',hwid,note:'test',term:{kind:'duration',unit:'hours',value:2.5}});assert.equal(a.status,201);({id,key}=a.data);const c=await C({hwid});assert.equal(c.data.active,true);assert.equal(c.data.expiresAt,now+9000000);assert.equal(c.data.leaseSeconds,300);assert.ok(!('note' in c.data));assert.equal((await A({action:'create',hwid,term:{kind:'permanent'}})).status,409);assert.equal((await C({hwid:'bad'})).status,400);});
await test('preset and arbitrary time extension plus expiry change',async()=>{await A({action:'extend',id,term:{kind:'duration',unit:'days',value:3}});assert.equal((await C({hwid})).data.expiresAt,now+9000000+259200000);const expiry=new Date(now+123456000).toISOString();await A({action:'set_term',id,term:{kind:'until',value:expiry}});assert.equal((await C({hwid})).data.expiresAt,Date.parse(expiry));});
await test('permanent activation, revocation and restoration',async()=>{await A({action:'set_term',id,term:{kind:'permanent'}});let c=await C({hwid});assert.equal(c.data.permanent,true);assert.equal(c.data.expiresAt,null);assert.equal((await A({action:'extend',id,term:{kind:'duration',unit:'days',value:1}})).status,400);await A({action:'revoke',id});assert.equal((await C({hwid})).data.active,false);await A({action:'restore',id});assert.equal((await C({hwid})).data.active,true);});
await test('unbound key redeem is device-bound and idempotent; expiry denies',async()=>{const a=await A({action:'create',term:{kind:'duration',unit:'minutes',value:37}});const k=a.data.key;now+=100000;const c=await C({hwid:hwid2,key:k},true);assert.equal(c.data.active,true);assert.equal(c.data.expiresAt,now+2220000);const exp=c.data.expiresAt;now+=10000;assert.equal((await C({hwid:hwid2,key:k},true)).data.expiresAt,exp);assert.equal((await C({hwid,key:k},true)).status,403);now=exp;assert.equal((await C({hwid:hwid2})).data.active,false);assert.equal((await C({hwid:hwid2,key:k},true)).status,403);});
await test('invalid terms fail, existing HWID cannot take another key',async()=>{assert.equal((await A({action:'create',term:{kind:'duration',unit:'days',value:-1}})).status,400);assert.equal((await A({action:'create',term:{kind:'until',value:'2000-01-01'}})).status,400);const a=await A({action:'create',term:{kind:'permanent'}});assert.equal((await C({hwid,key:a.data.key},true)).status,409);});


const withCookie=(p,c='',origin=ORIGIN)=>{const r=req(p,false,origin);if(c)r.headers.set('cookie',c);return r;};
const PK=async p=>{const r=await panelKeys(req(p,true));return{status:r.status,data:await r.json()};};
const digest=async value=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(value))),b=>b.toString(16).padStart(2,'0')).join('');
async function device(platform){const keys=await crypto.subtle.generateKey({name:'ECDSA',namedCurve:'P-256'},true,['sign','verify']);return {platform,keys,pub:Buffer.from(await crypto.subtle.exportKey('spki',keys.publicKey)).toString('base64')};}
const phone=await device('apk'),phone2=await device('apk'),pc=await device('exe'),pc2=await device('exe');
async function signed(path,p,dev,token='',method='POST'){
 const raw=method==='GET'?'':JSON.stringify(p),time=String(now),nonce=crypto.randomUUID().replaceAll('-','');
 const message=['BSHACK-DEVICE-V1',dev.platform,method,path,time,nonce,await digest(raw)].join('\n');
 const signature=Buffer.from(await crypto.subtle.sign({name:'ECDSA',hash:'SHA-256'},dev.keys.privateKey,new TextEncoder().encode(message))).toString('base64');
 return new Request(ORIGIN+path,{method,headers:{'content-type':'application/json','x-bshack-platform':dev.platform,'x-bshack-time':time,'x-bshack-nonce':nonce,'x-bshack-public-key':dev.pub,'x-bshack-signature':signature,authorization:token?'Bearer '+token:''},...(method==='GET'?{}:{body:raw})});
}
async function call(fn,path,p,dev,token='',method='POST'){const r=await fn(await signed(path,p,dev,token,method));return {status:r.status,data:await r.json()};}
const SS=(p,d=phone,t='')=>call(sellerSession,'/api/seller/session',p,d,t);
const SL=(p,d=phone,t='')=>call(sellerLicenses,'/api/seller/licenses',p,d,t);
const sessionGet=(d,t)=>call(sellerSession,'/api/seller/session',null,d,t,'GET');
const list=(d,t)=>call(sellerLicenses,'/api/seller/licenses',null,d,t,'GET');
let pair,apkKey,exeKey,sellerId,token1,winToken,buyerId,buyerKey;
await test('one issuance creates encrypted APK/EXE pair, separate platform keys and shared seller group',async()=>{
 assert.equal((await panelKeys(req({action:'create'}))).status,403);
 const result=await PK({action:'create',note:'Seller One',term:{kind:'duration',unit:'hours',value:2.75}});assert.equal(result.status,201);pair=result.data;
 assert.equal(pair.keys.length,2);apkKey=pair.keys.find(k=>k.platform==='apk');exeKey=pair.keys.find(k=>k.platform==='exe');sellerId=apkKey.id;assert.ok(apkKey.key.startsWith('BSP-A-'));assert.ok(exeKey.key.startsWith('BSP-W-'));assert.notEqual(apkKey.key,exeKey.key);
 const stored=sql.prepare('SELECT * FROM seller_keys WHERE id=?').get(sellerId);assert.equal(stored.group_id,pair.groupId);assert.equal(stored.activated_at,null);assert.ok(stored.key_cipher.startsWith('v1.'));assert.ok(!JSON.stringify(stored).includes(apkKey.key));
 const r=await panelKeys(new Request(ORIGIN+'/api',{headers:{cookie:adminCookie}}));const publicRows=(await r.json()).licenses;assert.ok(publicRows.every(k=>!('key_hash'in k)&&!('key_cipher'in k)&&!('device_hash'in k)));
});
await test('platform enforcement, first-device binding, repeat activation preserves expiry',async()=>{
 assert.equal((await SS({action:'activate',key:apkKey.key},pc)).status,403);assert.equal((await SS({action:'activate',key:exeKey.key},phone)).status,403);
 now+=600000;const active=await SS({action:'activate',key:apkKey.key});assert.equal(active.status,200);token1=active.data.token;assert.equal(active.data.seller.expires_at,now+9900000);assert.equal(active.data.seller.deviceBound,true);
 const end=active.data.seller.expires_at;now+=10000;assert.equal((await SS({action:'activate',key:apkKey.key})).data.seller.expires_at,end);
 assert.equal((await SS({action:'activate',key:apkKey.key},phone2)).status,409);
 const windows=await SS({action:'activate',key:exeKey.key},pc);assert.equal(windows.status,200);winToken=windows.data.token;assert.equal((await SS({action:'activate',key:exeKey.key},pc2)).status,409);
});
await test('simultaneous first activation accepts exactly one device',async()=>{
 now+=60000;
 const pair=await PK({action:'create',term:{kind:'permanent'}}),key=pair.data.keys.find(k=>k.platform==='exe').key;
 const results=await Promise.all([SS({action:'activate',key},pc),SS({action:'activate',key},pc2)]);
 assert.deepEqual(results.map(r=>r.status).sort(),[200,409]);
 const winner=results[0].status===200?pc:pc2,loser=winner===pc?pc2:pc;
 assert.equal((await SS({action:'activate',key},loser)).status,409);
 assert.equal((await SS({action:'activate',key},winner)).status,200);
});
await test('device signatures prevent replay, body tampering, stolen sessions and browser-cookie bypass',async()=>{
 const proof=await signed('/api/seller/session',null,phone,token1,'GET');assert.equal((await sellerSession(proof.clone())).status,200);assert.equal((await sellerSession(proof.clone())).status,401);
 assert.equal((await sessionGet(phone2,token1)).status,401);assert.equal((await sessionGet(pc,token1)).status,401);
 const original=await signed('/api/seller/session',{action:'activate',key:apkKey.key},phone);const changed=new Request(original.url,{method:'POST',headers:original.headers,body:JSON.stringify({action:'activate',key:exeKey.key})});assert.equal((await sellerSession(changed)).status,401);
 assert.equal((await sellerSession(req({action:'activate',key:apkKey.key}))).status,401);
 const stale=await signed('/api/seller/session',null,phone,token1,'GET');now+=121000;assert.equal((await sellerSession(stale)).status,401);
});
await test('seller pair shares buyer list and arbitrary issue duration; other sellers remain isolated',async()=>{
 const issued=await SL({action:'create',hwid:'BS-'+'C'.repeat(64),term:{kind:'duration',unit:'minutes',value:97.5}},phone,token1);assert.equal(issued.status,201);buyerId=issued.data.id;buyerKey=issued.data.key;
 assert.equal((await C({hwid:'BS-'+'C'.repeat(64)})).data.expiresAt,now+5850000);
 assert.equal((await SL({action:'create',term:{kind:'permanent'}},pc,winToken)).status,201);
 assert.equal((await list(phone,token1)).data.licenses.length,2);assert.equal((await list(pc,winToken)).data.licenses.length,2);
 for(const action of ['extend','set_term'])assert.equal((await SL({action,id:buyerId,term:{kind:'permanent'}},phone,token1)).status,403);
 const other=await PK({action:'create',term:{kind:'permanent'}});const otherActive=await SS({action:'activate',key:other.data.keys.find(k=>k.platform==='apk').key},phone2);assert.equal((await list(phone2,otherActive.data.token)).data.licenses.length,0);
 assert.equal((await SS({action:'activate',key:buyerKey})).status,400);assert.equal((await C({hwid:'BS-'+'D'.repeat(64),key:apkKey.key},true)).status,400);
});
await test('seller can suspend/restore owned buyers, cannot manage other sellers, and deletes permanently',async()=>{
 const other=await PK({action:'create',term:{kind:'permanent'}}),otherToken=(await SS({action:'activate',key:other.data.keys.find(k=>k.platform==='apk').key},phone2)).data.token;
 for(const action of ['revoke','restore','delete'])assert.equal((await SL({action,id:buyerId},phone2,otherToken)).status,404);
 assert.equal((await SL({action:'revoke',id:buyerId},pc,winToken)).status,200);
 assert.equal((await C({hwid:'BS-'+'C'.repeat(64)})).data.active,false);
 assert.equal((await SL({action:'restore',id:buyerId},phone,token1)).status,200);
 assert.equal((await C({hwid:'BS-'+'C'.repeat(64)})).data.active,true);
 const hwid='BS-'+'E'.repeat(64),created=await SL({action:'create',hwid,term:{kind:'permanent'}},phone,token1);
 assert.equal((await SL({action:'delete',id:created.data.id},pc,winToken)).status,200);
 assert.equal(sql.prepare('SELECT id FROM licenses WHERE id=?').get(created.data.id),undefined);
 assert.equal((await C({hwid})).data.active,false);
 assert.equal((await C({hwid,key:created.data.key},true)).status,403);
 assert.ok(!(await list(phone,token1)).data.licenses.some(r=>r.id===created.data.id));
 assert.equal((await SL({action:'restore',id:created.data.id},phone,token1)).status,404);
});
await test('admin deletion removes buyer and seller keys and invalidates seller sessions',async()=>{
 const hwid='BS-'+'F'.repeat(64),buyer=await A({action:'create',hwid,term:{kind:'permanent'}});
 assert.equal((await A({action:'delete',id:buyer.data.id})).status,200);
 assert.equal((await C({hwid})).data.active,false);
 assert.equal((await C({hwid,key:buyer.data.key},true)).status,403);
 const pair=await PK({action:'create',term:{kind:'permanent'}}),k=pair.data.keys.find(k=>k.platform==='exe'),session=await SS({action:'activate',key:k.key},pc2);
 assert.equal((await PK({action:'delete',id:k.id})).status,200);
 assert.equal(sql.prepare('SELECT id FROM seller_keys WHERE id=?').get(k.id),undefined);
 assert.equal(sql.prepare('SELECT seller_id FROM seller_sessions WHERE seller_id=?').get(k.id),undefined);
 assert.equal((await sessionGet(pc2,session.data.token)).status,401);
 assert.equal((await SS({action:'activate',key:k.key},pc2)).status,403);
});
await test('admin edits HWID without rotating key, extending time, or restoring a suspended buyer',async()=>{
 const first='BS-'+'1'.repeat(64),next='BS-'+'2'.repeat(64);
 const issued=await A({action:'create',hwid:first,note:'before',term:{kind:'duration',unit:'hours',value:4}});
 const original=sql.prepare('SELECT * FROM licenses WHERE id=?').get(issued.data.id);
 await A({action:'revoke',id:issued.data.id});now+=1000;
 const changed=await A({action:'update_device',id:issued.data.id,hwid:next.toLowerCase(),note:' after ',term:{kind:'permanent'},revoked:0});assert.equal(changed.status,200);
 const row=sql.prepare('SELECT * FROM licenses WHERE id=?').get(issued.data.id);
 assert.equal(row.hwid,next);assert.equal(row.note,'after');assert.equal(row.key_hash,original.key_hash);assert.equal(row.expires_at,original.expires_at);assert.equal(row.activated_at,original.activated_at);assert.equal(row.revoked,1);assert.equal(row.permanent,0);
 assert.equal((await C({hwid:first})).data.active,false);assert.equal((await C({hwid:next})).data.active,false);
 await A({action:'restore',id:issued.data.id});assert.equal((await C({hwid:next})).data.active,true);
 assert.equal((await C({hwid:first,key:issued.data.key},true)).status,403);
});
await test('missing HWID starts duration once; clear/rebind preserves its expiration and fixed deadlines',async()=>{
 const hwid='BS-'+'3'.repeat(64),issued=await A({action:'create',term:{kind:'duration',unit:'hours',value:3}});now+=1000;
 assert.equal((await A({action:'update_device',id:issued.data.id,hwid})).status,200);
 const row=sql.prepare('SELECT * FROM licenses WHERE id=?').get(issued.data.id);assert.equal(row.expires_at,now+10800000);assert.equal(row.activated_at,now);
 now+=1000;assert.equal((await A({action:'update_device',id:issued.data.id,hwid:''})).status,200);assert.equal((await C({hwid})).data.active,false);
 now+=1000;assert.equal((await A({action:'update_device',id:issued.data.id,hwid})).status,200);assert.equal(sql.prepare('SELECT expires_at FROM licenses WHERE id=?').get(issued.data.id).expires_at,row.expires_at);
 const end=now+1800000,fixed=await A({action:'create',term:{kind:'until',value:new Date(end).toISOString()}});now+=1000;
 await A({action:'update_device',id:fixed.data.id,hwid:'BS-'+'4'.repeat(64)});assert.equal(sql.prepare('SELECT expires_at FROM licenses WHERE id=?').get(fixed.data.id).expires_at,end);
});
await test('device edits validate input and roll back duplicate HWID conflicts',async()=>{
 const created=await A({action:'create',term:{kind:'permanent'}});
 for(const hwid of [null,{},'BS-bad'])assert.equal((await A({action:'update_device',id:created.data.id,hwid})).status,400);
 assert.equal((await A({action:'update_device',id:created.data.id,hwid:'',note:'x'.repeat(101)})).status,400);
 assert.equal((await A({action:'update_device',id:created.data.id,hwid:'BS-'+'3'.repeat(64)})).status,409);
 assert.equal(sql.prepare('SELECT hwid FROM licenses WHERE id=?').get(created.data.id).hwid,null);
 assert.equal((await admin(req({action:'update_device',id:created.data.id,hwid:''}))).status,403);
 assert.equal((await admin(req({action:'update_device',id:created.data.id,hwid:''},true,'https://evil.example'))).status,403);
});
await test('seller updates only its own buyer; paired Windows session sees changes and periods are protected',async()=>{
 const issued=await SL({action:'create',term:{kind:'duration',unit:'minutes',value:25}},phone,token1);
 const hwid='BS-'+'5'.repeat(64);assert.equal((await SL({action:'update_device',id:issued.data.id,hwid,note:'corrected',term:{kind:'permanent'}},pc,winToken)).status,200);
 const row=(await list(phone,token1)).data.licenses.find(r=>r.id===issued.data.id);assert.equal(row.hwid,hwid);assert.equal(row.note,'corrected');assert.equal(row.expires_at,now+1500000);assert.equal(row.permanent,0);
 const foreign=await A({action:'create',term:{kind:'permanent'}});assert.equal((await SL({action:'update_device',id:foreign.data.id,hwid:''},phone,token1)).status,404);
 sql.prepare('UPDATE licenses SET seller_id=? WHERE id=?').run('different-seller-group',foreign.data.id);assert.equal((await SL({action:'update_device',id:foreign.data.id,hwid:''},pc,winToken)).status,404);
 assert.equal((await SL({action:'update_device',id:issued.data.id,hwid:'BS-'+'3'.repeat(64)},phone,token1)).status,409);
 assert.equal((await C({hwid})).data.active,true);
});
await test('admin password is required to recover exact key; copying cannot reset its device binding',async()=>{
 assert.equal((await PK({action:'reveal',id:sellerId,password:'wrong'})).status,403);
 const recovered=await PK({action:'reveal',id:sellerId,password:'Test8pwd'});assert.equal(recovered.status,200);assert.equal(recovered.data.keys[0].key,apkKey.key);
 assert.equal((await SS({action:'activate',key:recovered.data.keys[0].key},phone2)).status,409);
 const noAdmin=await panelKeys(req({action:'reveal',id:sellerId,password:'Test8pwd'}));assert.equal(noAdmin.status,403);
});
await test('revocation/expiry checked on every signed request; restore never releases the device binding',async()=>{
 await PK({action:'revoke',id:sellerId});assert.equal((await sessionGet(phone,token1)).status,401);assert.equal((await SS({action:'activate',key:apkKey.key})).status,403);assert.equal((await sessionGet(pc,winToken)).status,200);assert.equal((await C({hwid:'BS-'+'C'.repeat(64)})).data.active,true);
 await PK({action:'restore',id:sellerId});assert.equal((await sessionGet(phone,token1)).status,401);assert.equal((await SS({action:'activate',key:apkKey.key},phone2)).status,409);token1=(await SS({action:'activate',key:apkKey.key})).data.token;
 const before=sql.prepare('SELECT expires_at FROM seller_keys WHERE id=?').get(sellerId).expires_at;await PK({action:'extend',id:sellerId,term:{kind:'duration',unit:'minutes',value:11}});assert.equal(sql.prepare('SELECT expires_at FROM seller_keys WHERE id=?').get(sellerId).expires_at,before+660000);
 await PK({action:'set_term',id:sellerId,term:{kind:'until',value:new Date(now+60000).toISOString()}});now+=60000;assert.equal((await sessionGet(phone,token1)).status,401);assert.equal((await SL({action:'create',term:{kind:'permanent'}},phone,token1)).status,401);
 await PK({action:'set_term',id:sellerId,term:{kind:'permanent'}});assert.equal((await sessionGet(phone,token1)).status,200);
 await SS({action:'logout'},phone,token1);assert.equal((await sessionGet(phone,token1)).status,401);assert.equal((await SS({action:'activate',key:apkKey.key},phone2)).status,409);
});
await test('legacy hash-only keys can be password-reissued into a pair without losing buyer ownership or fixed expiry',async()=>{
 const legacyId=crypto.randomUUID(),legacyKey='BSP-'+Array(6).fill('ABCD1234').join('-'),expires=now+3000000;
 sql.prepare('INSERT INTO seller_keys(id,key_hash,key_suffix,note,duration_ms,permanent,activated_at,expires_at,revoked,created_at,updated_at) VALUES(?,?,?,?,?,0,?,?,0,?,?)').run(legacyId,await digest(legacyKey),'ABCD1234','Legacy',3000000,now,expires,now,now);
 const buyer=await A({action:'create',note:'Legacy buyer',term:{kind:'permanent'}});sql.prepare('UPDATE licenses SET seller_id=? WHERE id=?').run(legacyId,buyer.data.id);
 assert.equal((await PK({action:'reveal',id:legacyId,password:'Test8pwd'})).status,409);
 const replacement=await PK({action:'replace_legacy',id:legacyId,password:'Test8pwd'});assert.equal(replacement.status,201);assert.equal(replacement.data.groupId,legacyId);assert.equal(sql.prepare('SELECT revoked FROM seller_keys WHERE id=?').get(legacyId).revoked,1);
 const token=(await SS({action:'activate',key:replacement.data.keys.find(k=>k.platform==='apk').key},phone2)).data.token;const status=await sessionGet(phone2,token);assert.equal(status.data.seller.expires_at,expires);assert.equal((await list(phone2,token)).data.licenses[0].id,buyer.data.id);
 assert.equal((await SS({action:'activate',key:legacyKey},phone)).status,403);
});

await test('admin login checks password and origin; logout invalidates its session',async()=>{
 assert.equal((await AUTH({action:'login',username:'owner',password:'wrong'})).status,401);
 const login=await AUTH({action:'login',username:'OWNER',password:'Test8pwd'});assert.equal(login.status,200);
 const cross=req({action:'login',username:'owner',password:'Test8pwd'},false,'https://evil.example');assert.equal((await adminAuth(cross)).status,403);
 await AUTH({action:'logout'},login.cookie);assert.equal((await admin(withCookie({},login.cookie))).status,403);
});
await test('password change requires current password and invalidates all older sessions',async()=>{
 assert.equal((await AUTH({action:'change_password',currentPassword:'wrong',password:'New8pass'},adminCookie)).status,403);
 assert.equal((await AUTH({action:'change_password',currentPassword:'Test8pwd',password:'seven77'},adminCookie)).status,400);
 const changed=await AUTH({action:'change_password',currentPassword:'Test8pwd',password:'New8pass'},adminCookie);assert.equal(changed.status,200);
 assert.equal((await admin(withCookie({},adminCookie))).status,403);adminCookie=changed.cookie;
 assert.equal((await AUTH({action:'login',username:'owner',password:'Test8pwd'})).status,401);
 assert.equal((await AUTH({action:'login',username:'owner',password:'New8pass'})).status,200);
});
await test('admin session expires and persistent IP rate limit rejects excessive attempts',async()=>{
 now+=12*3600000;assert.equal((await admin(withCookie({},adminCookie))).status,403);
 let last;for(let i=0;i<21;i++){const r=req({action:'login',username:'owner',password:'x'.repeat(129)});r.headers.set('cf-connecting-ip','192.0.2.99');last=await adminAuth(r);}assert.equal(last.status,429);
});
Date.now=realNow;sql.close();
