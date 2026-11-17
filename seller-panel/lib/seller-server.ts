import {adminAllowed,db,json,body,hash,key,event,sameOrigin,createLicense} from './license-server';
import {resolveTerm,statusOf} from './license-domain';
import {deviceProof,type Device} from './device-proof';
import {encryptKey,decryptKey} from './key-vault';
import {reauthenticateAdmin} from './admin-auth';
type SellerKey={id:string,key_hash:string,key_suffix:string,note:string,duration_ms:number|null,permanent:number,activated_at:number|null,expires_at:number|null,revoked:number,created_at:number,updated_at:number,group_id:string|null,platform:'apk'|'exe'|null,device_hash:string|null,key_cipher:string|null};
const SESSION_MS=30*86400000;
const status=(row:SellerKey)=>statusOf({...row,hwid:row.activated_at===null?null:'activated'});
function publicKey(row:SellerKey){const {key_hash,key_cipher,device_hash,...safe}=row;return {...safe,status:status(row),recoverable:!!key_cipher,deviceBound:!!device_hash,deviceSuffix:device_hash?.slice(-8)||null};}
function tokenFrom(r:Request){return r.headers.get('authorization')?.replace(/^Bearer /,'')||'';}
async function proof(r:Request){const raw=r.method==='GET'?'':await r.clone().text();if(raw.length>4096)return null;return deviceProof(r,raw);}
async function authenticated(r:Request,device:Device){
 const token=tokenFrom(r);if(!/^[a-f0-9]{64}$/.test(token))return null;
 const session=await db().prepare('SELECT seller_id,expires_at FROM seller_sessions WHERE token_hash=?').bind(await hash(token)).first<{seller_id:string,expires_at:number}>();
 if(!session||session.expires_at<=Date.now())return null;
 const row=await db().prepare('SELECT * FROM seller_keys WHERE id=?').bind(session.seller_id).first<SellerKey>();
 return row&&row.device_hash===device.hash&&row.platform===device.platform&&status(row)==='active'?row:null;
}
const attempts=new Map<string,{n:number,until:number}>();
function limited(r:Request){const ip=r.headers.get('cf-connecting-ip')||'unknown',now=Date.now();if(attempts.size>10000)for(const [k,v]of attempts)if(v.until<=now)attempts.delete(k);let a=attempts.get(ip);if(!a||a.until<=now){a={n:0,until:now+60000};attempts.set(ip,a);}return ++a.n>20;}
async function issuePair(note:string,term:{duration_ms:number|null,permanent:number,expires_at:number|null},groupId=crypto.randomUUID(),revoked=0){
 const now=Date.now(),keys=[],operations=[];
 for(const platform of ['apk','exe'] as const){
  const id=crypto.randomUUID(),k=key(platform==='apk'?'BSP-A':'BSP-W');
  operations.push(db().prepare('INSERT INTO seller_keys(id,key_hash,key_suffix,note,duration_ms,permanent,activated_at,expires_at,revoked,created_at,updated_at,group_id,platform,key_cipher) VALUES(?,?,?,?,?,?,NULL,?,?,?,?,?,?,?)').bind(id,await hash(k),k.slice(-8),note,term.duration_ms,term.permanent,term.expires_at,revoked,now,now,groupId,platform,await encryptKey(k,id)),event(id,'seller_key_issue_'+platform,now));
  keys.push({id,platform,key:k});
 }
 return {keys,operations,groupId};
}
export async function panelKeys(r:Request){
 if(!await adminAllowed(r))return json({error:'관리자만 판매자 패널 키를 관리할 수 있습니다.'},403);
 if(r.method==='GET'){const {results}=await db().prepare('SELECT * FROM seller_keys ORDER BY created_at DESC,platform LIMIT 1000').all();return json({licenses:results.map(row=>publicKey(row as unknown as SellerKey))});}
 if(!sameOrigin(r))return json({error:'요청 출처가 올바르지 않습니다.'},403);
 try{
  const p=await body(r),now=Date.now();
  if(p.action==='create'){
   const term=resolveTerm(p.term,false,now),note=String(p.note||'').trim();if(note.length>100)return json({error:'메모는 100자 이내로 입력해 주세요.'},400);
   const pair=await issuePair(note,term);await db().batch(pair.operations);return json({keys:pair.keys,groupId:pair.groupId},201);
  }
  if(typeof p.id!=='string')return json({error:'패널 키를 선택해 주세요.'},400);
  const row=await db().prepare('SELECT * FROM seller_keys WHERE id=?').bind(p.id).first<SellerKey>();if(!row)return json({error:'패널 키를 찾을 수 없습니다.'},404);
  if(p.action==='reveal'||p.action==='replace_legacy'){
   const denied=await reauthenticateAdmin(r,p.password);if(denied)return denied;
   if(p.action==='reveal'){
    if(!row.key_cipher)return json({error:'이전 버전의 키는 원문이 저장되지 않았습니다. 새 키 쌍으로 재발급해 주세요.'},409);
    const k=await decryptKey(row.key_cipher,row.id);await event(row.id,'seller_key_reveal',now).run();return json({keys:[{id:row.id,platform:row.platform,key:k}]});
   }
   if(row.platform!==null||row.key_cipher||row.revoked)return json({error:'이 작업은 이전 버전 키에만 사용할 수 있습니다.'},400);
   const pair=await issuePair(row.note,{duration_ms:row.duration_ms,permanent:row.permanent,expires_at:row.expires_at},row.group_id||row.id,row.revoked);
   await db().batch([...pair.operations,db().prepare('UPDATE seller_keys SET revoked=1,updated_at=? WHERE id=?').bind(now,row.id),db().prepare('DELETE FROM seller_sessions WHERE seller_id=?').bind(row.id),event(row.id,'seller_key_replace_legacy',now)]);
   return json({keys:pair.keys,groupId:pair.groupId},201);
  }
  if(p.action==='extend'||p.action==='set_term'){
   const term=resolveTerm(p.term,row.activated_at!==null,now);
   if(p.action==='extend'&&p.term.kind==='duration'){
    if(row.permanent)return json({error:'영구 키는 연장할 필요가 없습니다.'},400);
    if(row.activated_at!==null||row.expires_at!==null)term.expires_at=Math.max(row.expires_at||0,now)+term.duration_ms!;
    else term.duration_ms=(row.duration_ms||0)+term.duration_ms!;
    if((term.expires_at||now+term.duration_ms!)>253402300799000)return json({error:'기간이 너무 깁니다.'},400);
   }
   await db().batch([db().prepare('UPDATE seller_keys SET duration_ms=?,permanent=?,expires_at=?,updated_at=? WHERE id=?').bind(term.duration_ms,term.permanent,term.expires_at,now,row.id),event(row.id,'seller_key_'+p.action,now)]);
  }else if(p.action==='revoke'||p.action==='restore'){
   const operations=[db().prepare('UPDATE seller_keys SET revoked=?,updated_at=? WHERE id=?').bind(p.action==='revoke'?1:0,now,row.id),event(row.id,'seller_key_'+p.action,now)];
   if(p.action==='revoke')operations.push(db().prepare('DELETE FROM seller_sessions WHERE seller_id=?').bind(row.id));
   await db().batch(operations);
  }else if(p.action==='delete'){
   await db().batch([db().prepare('DELETE FROM seller_sessions WHERE seller_id=?').bind(row.id),db().prepare('DELETE FROM seller_keys WHERE id=?').bind(row.id),event(row.id,'seller_key_delete',now)]);
  }else return json({error:'지원하지 않는 작업입니다.'},400);
  return json({ok:true});
 }catch{return json({error:'저장하지 못했습니다. 기간과 입력값을 확인해 주세요.'},400);}
}
export async function sellerSession(r:Request){
 const device=await proof(r);if(!device)return json({error:'새 판매자 APK 또는 EXE에서 열어 주세요. 기기 인증 또는 기기 시간이 올바르지 않습니다.'},401);
 if(r.method==='GET'){const row=await authenticated(r,device);return row?json({seller:publicKey(row)}):json({error:'설정에서 유효한 판매자 패널 키를 입력해 주세요.'},401);}
 try{
  const p=await body(r);
  if(p.action==='logout'){
   if(await authenticated(r,device))await db().prepare('DELETE FROM seller_sessions WHERE token_hash=?').bind(await hash(tokenFrom(r))).run();
   return json({ok:true});
  }
  if(p.action!=='activate')return json({error:'지원하지 않는 작업입니다.'},400);
  if(limited(r))return json({error:'요청이 많습니다. 1분 뒤에 다시 시도해 주세요.'},429);
  const k=typeof p.key==='string'?p.key.trim().toUpperCase():'';
  if(!/^BSP-(?:[AW]-)?(?:[A-F0-9]{8}-){5}[A-F0-9]{8}$/.test(k))return json({error:'판매자 패널 키 형식을 확인해 주세요.'},400);
  const kh=await hash(k),now=Date.now();
  const row=await db().prepare('SELECT * FROM seller_keys WHERE key_hash=?').bind(kh).first<SellerKey>();
  if(!row||['expired','revoked'].includes(status(row)))return json({error:'만료·차단되었거나 유효하지 않은 패널 키입니다.'},403);
  if(row.platform&&row.platform!==device.platform)return json({error:row.platform==='apk'?'APK용 키입니다. Android 앱에서 사용해 주세요.':'EXE용 키입니다. Windows 앱에서 사용해 주세요.'},403);
  if(row.device_hash&&row.device_hash!==device.hash)return json({error:'이미 다른 기기에 등록된 패널 키입니다.'},409);
  await db().prepare('UPDATE seller_keys SET device_hash=?,platform=COALESCE(platform,?),group_id=COALESCE(group_id,id),key_cipher=COALESCE(key_cipher,?),activated_at=COALESCE(activated_at,?),expires_at=CASE WHEN permanent=1 THEN NULL WHEN expires_at IS NOT NULL THEN expires_at ELSE ?+duration_ms END,updated_at=? WHERE id=? AND revoked=0 AND (device_hash IS NULL OR device_hash=?) AND (platform IS NULL OR platform=?) AND (permanent=1 OR expires_at IS NULL OR expires_at>?)').bind(device.hash,device.platform,await encryptKey(k,row.id),now,now,now,row.id,device.hash,device.platform,now).run();
  const current=await db().prepare('SELECT * FROM seller_keys WHERE id=?').bind(row.id).first<SellerKey>();
  if(!current||current.device_hash!==device.hash||current.platform!==device.platform||status(current)!=='active')return json({error:'이미 다른 기기에 등록되었거나 사용할 수 없는 키입니다.'},409);
  const token=Array.from(crypto.getRandomValues(new Uint8Array(32)),x=>x.toString(16).padStart(2,'0')).join('');
  await db().batch([db().prepare('DELETE FROM seller_sessions WHERE expires_at<=?').bind(now),db().prepare('INSERT INTO seller_sessions(token_hash,seller_id,expires_at,created_at) VALUES(?,?,?,?)').bind(await hash(token),row.id,now+SESSION_MS,now),event(row.id,'seller_key_activate',now)]);
  return json({seller:publicKey(current),token});
 }catch{return json({error:'패널을 활성화하지 못했습니다. 다시 시도해 주세요.'},400);}
}
export async function sellerLicenses(r:Request){
 const device=await proof(r),seller=device?await authenticated(r,device):null;if(!seller)return json({error:'인증된 판매자 앱에서 열어 주세요. 설정에서 키와 이용 기간을 확인해 주세요.'},401);
 if(r.method==='GET'){
  const {results}=await db().prepare('SELECT id,key_suffix,hwid,note,duration_ms,permanent,activated_at,expires_at,revoked,created_at,updated_at FROM licenses WHERE seller_id=? ORDER BY created_at DESC LIMIT 1000').bind(seller.group_id||seller.id).all();
  return json({licenses:results.map(row=>({...row,status:statusOf(row as any)}))});
 }
 try{
  const p=await body(r),owner=seller.group_id||seller.id;
  if(p.action==='create')return await createLicense(p,owner);
  if(!['revoke','restore','delete'].includes(p.action))return json({error:'판매자는 기존 구독의 기간 변경·연장을 할 수 없습니다.'},403);
  if(typeof p.id!=='string')return json({error:'구독을 선택해 주세요.'},400);
  const row=await db().prepare('SELECT id FROM licenses WHERE id=? AND seller_id=?').bind(p.id,owner).first<{id:string}>();
  if(!row)return json({error:'구독을 찾을 수 없습니다.'},404);
  const mutation=p.action==='delete'
   ?db().prepare('DELETE FROM licenses WHERE id=? AND seller_id=?').bind(row.id,owner)
   :db().prepare('UPDATE licenses SET revoked=?,updated_at=? WHERE id=? AND seller_id=?').bind(p.action==='revoke'?1:0,Date.now(),row.id,owner);
  await db().batch([mutation,event(row.id,'seller_'+p.action,Date.now())]);
  return json({ok:true});
 }
 catch{return json({error:'등록하지 못했습니다. HWID와 기간을 확인해 주세요.'},400);}
}
