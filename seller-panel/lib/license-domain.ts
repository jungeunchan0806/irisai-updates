export const HWID_RE = /^BS-[A-F0-9]{64}$/;
export type Term = {kind:'duration',value:number,unit:'minutes'|'hours'|'days'}|{kind:'until',value:string}|{kind:'permanent'};
export type StoredTerm={permanent:number,duration_ms:number|null,expires_at:number|null};
export function normalizeHwid(v: unknown) { return typeof v === 'string' ? v.trim().toUpperCase() : ''; }
export function resolveTerm(term:Term, activated:boolean, now=Date.now()):StoredTerm {
 if(term?.kind==='permanent')return {permanent:1,duration_ms:null,expires_at:null};
 if(term?.kind==='until'){
  const t=Date.parse(term.value);if(!Number.isFinite(t)||t<=now||t>253402300799000)throw new Error('미래의 만료 날짜를 입력해 주세요.');
  return {permanent:0,duration_ms:null,expires_at:t};
 }
 if(term?.kind==='duration'){
  const factor={minutes:60000,hours:3600000,days:86400000}[term.unit];
  const ms=Math.round(Number(term.value)*factor);
  if(!factor||!Number.isFinite(ms)||ms<60000||ms+now>253402300799000)throw new Error('기간은 1분 이상이며 9999년 이내여야 합니다.');
  return {permanent:0,duration_ms:ms,expires_at:activated?now+ms:null};
 }
 throw new Error('기간을 선택해 주세요.');
}
export function statusOf(row: {revoked: number, hwid: string|null, expires_at: number|null,permanent:number}, now=Date.now()) {
 if(row.revoked)return 'revoked';
 if(!row.permanent&&row.expires_at!==null&&row.expires_at<=now)return 'expired';
 if(!row.hwid)return 'pending';
 return row.permanent||row.expires_at!==null?'active':'pending';
}
export function leaseFor(row: {revoked:number,hwid:string|null,expires_at:number|null,permanent:number}|null,now=Date.now()) {
 const active=!!row&&statusOf(row,now)==='active';
 return {active,permanent:active&&!!row!.permanent,expiresAt:active?row!.expires_at:null,serverTime:now,
 leaseSeconds:active?(row!.permanent?300:Math.max(0,Math.min(300,Math.floor((row!.expires_at!-now)/1000)))):0,
 status:row?statusOf(row,now):'unregistered'};
}
