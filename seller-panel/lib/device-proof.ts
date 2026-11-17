import {db,hash} from './server-utils';
const encoder=new TextEncoder();
export type Device={platform:'apk'|'exe',hash:string};
export function decode64(v:string){return Uint8Array.from(atob(v),c=>c.charCodeAt(0));}
export function encode64(v:Uint8Array){return btoa(String.fromCharCode(...v));}
export async function deviceProof(r:Request,raw:string):Promise<Device|null>{
 try{
  const platform=r.headers.get('x-bshack-platform');if(platform!=='apk'&&platform!=='exe')return null;
  const time=r.headers.get('x-bshack-time')||'',nonce=r.headers.get('x-bshack-nonce')||'',pub=r.headers.get('x-bshack-public-key')||'',sig=r.headers.get('x-bshack-signature')||'';
  if(!/^\d{13}$/.test(time)||Math.abs(Date.now()-Number(time))>120000||!/^[a-f0-9]{32}$/.test(nonce)||pub.length>256||sig.length>128)return null;
  const publicBytes=decode64(pub),signature=decode64(sig);if(signature.length!==64)return null;
  const key=await crypto.subtle.importKey('spki',publicBytes,{name:'ECDSA',namedCurve:'P-256'},false,['verify']);
  const message=['BSHACK-DEVICE-V1',platform,r.method,new URL(r.url).pathname,time,nonce,await hash(raw)].join('\n');
  if(!await crypto.subtle.verify({name:'ECDSA',hash:'SHA-256'},key,signature,encoder.encode(message)))return null;
  const fingerprint=await hash(encode64(publicBytes));
  await db().batch([db().prepare('DELETE FROM device_nonces WHERE expires_at<=?').bind(Date.now()),db().prepare('INSERT INTO device_nonces(id,expires_at) VALUES(?,?)').bind(fingerprint+':'+nonce,Date.now()+300000)]);
  return {platform,hash:fingerprint};
 }catch{return null;}
}
