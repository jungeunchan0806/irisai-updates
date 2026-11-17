import {env} from 'cloudflare:workers';
import {pbkdf2Async} from '@noble/hashes/pbkdf2.js';
import {sha256} from '@noble/hashes/sha2.js';
import {db,json,body,hash,sameOrigin,event} from './server-utils';
const COOKIE='__Host-bshack-admin',TTL=12*3600000,ITERATIONS=600000;
type Account={id:number,username:string,password_hash:string,updated_at:number};
function hex(bytes:Uint8Array){return Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('');}
function equal(a:string,b:string){let diff=a.length^b.length;for(let i=0;i<Math.max(a.length,b.length);i++)diff|=(a.charCodeAt(i)||0)^(b.charCodeAt(i)||0);return diff===0;}
function tokenFrom(r:Request){return(r.headers.get('cookie')||'').split(';').map(v=>v.trim()).find(v=>v.startsWith(COOKIE+'='))?.slice(COOKIE.length+1)||'';}
function cookie(token:string,seconds=TTL/1000){return `${COOKIE}=${token}; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=${seconds}`;}
function validPassword(p:unknown){return typeof p==='string'&&p.length>=8&&p.length<=128;}
export async function passwordHash(password:string,salt=hex(crypto.getRandomValues(new Uint8Array(16)))){
 const saltBytes=Uint8Array.from(salt.match(/../g)!,v=>parseInt(v,16));
 const value=await pbkdf2Async(sha256,password,saltBytes,{c:ITERATIONS,dkLen:32});
 return `pbkdf2-sha256$${ITERATIONS}$${salt}$${hex(value)}`;
}
async function verify(password:unknown,stored:string){
 if(typeof password!=='string'||password.length>128)return false;
 const parts=stored.split('$');if(parts.length!==4||parts[0]!=='pbkdf2-sha256'||parts[1]!==String(ITERATIONS)||!/^[a-f0-9]{32}$/.test(parts[2])||!/^[a-f0-9]{64}$/.test(parts[3]))return false;
 return equal(await passwordHash(password,parts[2]),stored);
}
export async function getAdmin(r:Request){
 const token=tokenFrom(r);if(!/^[a-f0-9]{64}$/.test(token))return null;
 const session=await db().prepare('SELECT expires_at FROM admin_sessions WHERE token_hash=?').bind(await hash(token)).first<{expires_at:number}>();
 if(!session||session.expires_at<=Date.now())return null;
 return db().prepare('SELECT username FROM admin_account WHERE id=1').first<{username:string}>();
}
async function limited(r:Request){
 const now=Date.now(),window=Math.floor(now/900000),ip=r.headers.get('cf-connecting-ip')||'unknown';
 const bucket=await hash('admin-login|'+ip+'|'+window);
 await db().prepare('DELETE FROM auth_limits WHERE expires_at<=?').bind(now).run();
 const count=await db().prepare('INSERT INTO auth_limits(bucket,attempts,expires_at) VALUES(?,1,?) ON CONFLICT(bucket) DO UPDATE SET attempts=attempts+1 RETURNING attempts').bind(bucket,(window+1)*900000).first<{attempts:number}>();
 return !count||count.attempts>20;
}
async function session(username:string){
 const token=hex(crypto.getRandomValues(new Uint8Array(32))),now=Date.now();
 await db().batch([db().prepare('DELETE FROM admin_sessions WHERE expires_at<=?').bind(now),db().prepare('INSERT INTO admin_sessions(token_hash,expires_at,created_at) VALUES(?,?,?)').bind(await hash(token),now+TTL,now)]);
 const out=json({admin:{username}});out.headers.set('Set-Cookie',cookie(token));return out;
}
export async function reauthenticateAdmin(r:Request,password:unknown){
 if(!await getAdmin(r))return json({error:'관리자 로그인이 필요합니다.'},401);
 if(await limited(r))return json({error:'비밀번호 확인 시도가 많습니다. 15분 후 다시 시도해 주세요.'},429);
 const account=await db().prepare('SELECT * FROM admin_account WHERE id=1').first<Account>();
 if(!account||!await verify(password,account.password_hash))return json({error:'관리자 비밀번호가 올바르지 않습니다.'},403);
 return null;
}
export async function adminAuth(r:Request){
 if(r.method==='GET'){
  const admin=await getAdmin(r);if(admin)return json({admin});
  const account=await db().prepare('SELECT id FROM admin_account WHERE id=1').first();
  return json({admin:null,setupRequired:!account,setupAvailable:!account&&!!env.ADMIN_SETUP_HASH});
 }
 if(!sameOrigin(r))return json({error:'요청 출처가 올바르지 않습니다.'},403);
 try{
  const p=await body(r);
  if(p.action==='logout'){
   const token=tokenFrom(r);if(token)await db().prepare('DELETE FROM admin_sessions WHERE token_hash=?').bind(await hash(token)).run();
   const out=json({ok:true});out.headers.set('Set-Cookie',cookie('',0));return out;
  }
  if(!['login','setup','change_password'].includes(p.action))return json({error:'지원하지 않는 작업입니다.'},400);
  if(await limited(r))return json({error:'로그인 시도가 많습니다. 15분 뒤에 다시 시도해 주세요.'},429);
  const account=await db().prepare('SELECT * FROM admin_account WHERE id=1').first<Account>();
  if(p.action==='setup'){
   if(account)return json({error:'관리자 계정이 이미 설정되어 있습니다.'},409);
   if(!env.ADMIN_SETUP_HASH||typeof p.setupCode!=='string'||!equal(await hash(p.setupCode.trim()),env.ADMIN_SETUP_HASH))return json({error:'초기 설정 코드가 올바르지 않습니다.'},403);
   const username=typeof p.username==='string'?p.username.trim().toLowerCase():'';
   if(!/^[a-z0-9_.-]{3,32}$/.test(username)||!validPassword(p.password))return json({error:'아이디는 영문·숫자·._- 3~32자, 비밀번호는 8~128자로 입력해 주세요.'},400);
   const pw=await passwordHash(p.password),now=Date.now();
   try{await db().batch([db().prepare('INSERT INTO admin_account(id,username,password_hash,updated_at) VALUES(1,?,?,?)').bind(username,pw,now),event('admin','admin_setup',now)]);}catch{return json({error:'계정이 이미 설정되었거나 저장에 실패했습니다.'},409);}
   return session(username);
  }
  if(p.action==='change_password'){
   if(!account||!await getAdmin(r))return json({error:'관리자 로그인이 필요합니다.'},401);
   if(!await verify(p.currentPassword,account.password_hash))return json({error:'현재 비밀번호가 올바르지 않습니다.'},403);
   if(!validPassword(p.password))return json({error:'새 비밀번호는 8~128자로 입력해 주세요.'},400);
   const pw=await passwordHash(p.password),now=Date.now();
   await db().batch([db().prepare('UPDATE admin_account SET password_hash=?,updated_at=? WHERE id=1').bind(pw,now),db().prepare('DELETE FROM admin_sessions'),event('admin','admin_password_change',now)]);
   return session(account.username);
  }
  const username=typeof p.username==='string'?p.username.trim().toLowerCase():'';
  if(!account||!await verify(p.password,account.password_hash)||!equal(username,account.username))return json({error:'아이디 또는 비밀번호가 올바르지 않습니다.'},401);
  return session(account.username);
 }catch{return json({error:'로그인을 처리하지 못했습니다. 다시 시도해 주세요.'},400);}
}
