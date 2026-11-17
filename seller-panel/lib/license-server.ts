import {db,json,sameOrigin,body,hash,key,event} from './server-utils';
import {getAdmin} from './admin-auth';
export {db,json,sameOrigin,body,hash,key,event} from './server-utils';
export async function adminAllowed(r:Request){return !!(await getAdmin(r));}
import { HWID_RE, normalizeHwid, resolveTerm, statusOf, leaseFor } from './license-domain';
export type LicenseRow={id:string,key_hash:string,key_suffix:string,hwid:string|null,note:string,duration_ms:number|null,permanent:number,activated_at:number|null,expires_at:number|null,revoked:number,created_at:number,updated_at:number};
const limits=new Map<string,{count:number,until:number}>();
function limited(r:Request){const ip=r.headers.get('cf-connecting-ip')||'unknown';const now=Date.now();if(limits.size>10000)for(const [k,v]of limits)if(v.until<now)limits.delete(k);let v=limits.get(ip);if(!v||v.until<now){v={count:0,until:now+60000};limits.set(ip,v);}return ++v.count>100;}
export async function admin(r:Request){
 if(!await adminAllowed(r))return json({error:'관리자 아이디와 비밀번호로 로그인해 주세요.'},403);
 if(r.method==='GET'){
  const {results}=await db().prepare('SELECT id,key_suffix,hwid,seller_id,note,duration_ms,permanent,activated_at,expires_at,revoked,created_at,updated_at FROM licenses ORDER BY created_at DESC LIMIT 1000').all();
  return json({licenses:results.map(row=>({...row,status:statusOf(row as unknown as LicenseRow)})),role:'admin'});
 }
 if(!sameOrigin(r))return json({error:'요청 출처가 올바르지 않습니다.'},403);
 try{
  const p=await body(r),now=Date.now();
  if(p.action==='create')return await createLicense(p);
  if(p.action==='update_device')return await updateLicenseDevice(p);
  if(typeof p.id!=='string')return json({error:'구독을 선택해 주세요.'},400);
  const row=await db().prepare('SELECT * FROM licenses WHERE id=?').bind(p.id).first<LicenseRow>();if(!row)return json({error:'구독을 찾을 수 없습니다.'},404);
  if(p.action==='extend'||p.action==='set_term'){
   const term=resolveTerm(p.term,!!row.hwid,now);
   if(p.action==='extend'&&p.term.kind==='duration'){
    if(row.permanent)return json({error:'영구 구독은 연장할 필요가 없습니다. 기간 변경을 사용해 주세요.'},400);
    if(row.hwid)term.expires_at=Math.max(row.expires_at||0,now)+term.duration_ms!;
    else term.duration_ms=(row.duration_ms||0)+term.duration_ms!;
    if((term.expires_at||now+term.duration_ms!)>253402300799000)return json({error:'기간이 너무 깁니다.'},400);
   }
   await db().batch([db().prepare('UPDATE licenses SET duration_ms=?,permanent=?,expires_at=?,updated_at=? WHERE id=?').bind(term.duration_ms,term.permanent,term.expires_at,now,row.id),event(row.id,p.action,now)]);
  }else if(p.action==='revoke'||p.action==='restore'){
   await db().batch([db().prepare('UPDATE licenses SET revoked=?,updated_at=? WHERE id=?').bind(p.action==='revoke'?1:0,now,row.id),event(row.id,p.action,now)]);
  }else if(p.action==='delete'){
   await db().batch([db().prepare('DELETE FROM licenses WHERE id=?').bind(row.id),event(row.id,'delete',now)]);
  }else return json({error:'지원하지 않는 작업입니다.'},400);
  return json({ok:true});
 }catch(e){console.error('license admin error',e instanceof Error?e.message:'unknown');return json({error:'저장하지 못했습니다. 입력을 확인하고 다시 시도해 주세요.'},400);}
}
export async function client(r:Request,redeem=false){
 if(limited(r))return json({error:'잠시 후 다시 시도해 주세요.'},429);
 try{
  const p=await body(r),hwid=normalizeHwid(p.hwid);if(!HWID_RE.test(hwid))return json({error:'올바르지 않은 HWID입니다.'},400);
  if(redeem){
   const k=typeof p.key==='string'?p.key.trim().toUpperCase():'';
   if(!/^BSK-(?:[A-F0-9]{8}-){5}[A-F0-9]{8}$/.test(k))return json({error:'라이선스키 형식을 확인해 주세요.'},400);
   const now=Date.now(),kh=await hash(k);
   const row=await db().prepare('SELECT * FROM licenses WHERE key_hash=?').bind(kh).first<LicenseRow>();
   if(!row||row.revoked||statusOf(row,now)==='expired'||row.hwid&&row.hwid!==hwid)return json({error:'등록할 수 없는 라이선스키입니다.'},403);
   if(!row.hwid){
    try{await db().batch([db().prepare('UPDATE licenses SET hwid=?,activated_at=?,expires_at=?,updated_at=? WHERE id=? AND hwid IS NULL AND revoked=0').bind(hwid,now,row.permanent?null:(row.expires_at||now+row.duration_ms!),now,row.id),event(row.id,'redeem',now)]);}catch{return json({error:'이 HWID에 이미 구독이 등록되어 있습니다.'},409);}
   }
   const latest=await db().prepare('SELECT * FROM licenses WHERE key_hash=?').bind(kh).first<LicenseRow>();
   if(!latest||latest.hwid!==hwid)return json({error:'다른 기기에 등록된 키입니다.'},409);
   return json(leaseFor(latest));
  }
  const row=await db().prepare('SELECT * FROM licenses WHERE hwid=?').bind(hwid).first<LicenseRow>();
  return json(leaseFor(row));
 }catch{return json({error:'인증 요청을 처리하지 못했습니다.'},400);}
}

export async function createLicense(p:any,sellerId:string|null=null){
 const now=Date.now();
   const hwid=normalizeHwid(p.hwid)||null,term=resolveTerm(p.term,!!hwid,now);
   if(hwid&&!HWID_RE.test(hwid))return json({error:'HWID 또는 기간을 확인해 주세요.'},400);
   const note=String(p.note||'').trim();if(note.length>100)return json({error:'메모는 100자 이내로 입력해 주세요.'},400);
   const k=key(),id=crypto.randomUUID();
   try{await db().batch([db().prepare('INSERT INTO licenses(id,key_hash,key_suffix,hwid,note,duration_ms,permanent,activated_at,expires_at,revoked,created_at,updated_at,seller_id) VALUES(?,?,?,?,?,?,?,?,?,0,?,?,?)').bind(id,await hash(k),k.slice(-8),hwid,note,term.duration_ms,term.permanent,hwid?now:null,term.expires_at,now,now,sellerId),event(id,hwid?'register':'issue',now)]);}catch(e){if(String(e).includes('UNIQUE'))return json({error:'이미 등록된 HWID입니다. 기존 구독을 연장해 주세요.'},409);throw e;}
   return json({id,key:k},201);
  }

/** Edit only device identity and note; preserve the key, ownership and subscription terms. */
export async function updateLicenseDevice(p:any,sellerId?:string){
 if(typeof p.id!=='string'||typeof p.hwid!=='string')return json({error:'구독과 HWID를 확인해 주세요.'},400);
 const hwid=normalizeHwid(p.hwid)||null;
 if(hwid&&!HWID_RE.test(hwid))return json({error:'HWID는 BS-로 시작하는 64자리 영문·숫자 식별값이어야 합니다.'},400);
 if(p.note!==undefined&&(typeof p.note!=='string'||p.note.trim().length>100))return json({error:'메모는 100자 이내로 입력해 주세요.'},400);
 const where=sellerId===undefined?'id=?':'id=? AND seller_id=?';
 const scope=sellerId===undefined?[p.id]:[p.id,sellerId];
 const row=await db().prepare('SELECT id FROM licenses WHERE '+where).bind(...scope).first<{id:string}>();
 if(!row)return json({error:'구독을 찾을 수 없습니다.'},404);
 const now=Date.now();
 try{
  await db().batch([
   db().prepare(`UPDATE licenses SET hwid=?,note=COALESCE(?,note),
    activated_at=CASE WHEN ? IS NOT NULL THEN COALESCE(activated_at,?) ELSE activated_at END,
    expires_at=CASE WHEN ? IS NOT NULL AND permanent=0 AND expires_at IS NULL AND activated_at IS NULL THEN ?+duration_ms ELSE expires_at END,
    updated_at=? WHERE `+where).bind(hwid,p.note===undefined?null:p.note.trim(),hwid,now,hwid,now,now,...scope),
   event(row.id,sellerId===undefined?'update_device':'seller_update_device',now)
  ]);
 }catch(e){if(String(e).includes('UNIQUE'))return json({error:'이미 다른 구독에 등록된 HWID입니다.'},409);throw e;}
 return json({ok:true});
}
