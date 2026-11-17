import {env} from 'cloudflare:workers';
export function db(){if(!env.DB)throw new Error('Database unavailable');return env.DB;}
export function json(data:unknown,status=200){return Response.json(data,{status,headers:{'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'}});}
export function sameOrigin(r:Request){return r.headers.get('origin')===new URL(r.url).origin;}
export async function body(r:Request){if(!r.headers.get('content-type')?.startsWith('application/json'))throw new Error('JSON 요청만 허용됩니다.'); const text=await r.text();if(text.length>4096)throw new Error('요청이 너무 큽니다.');return JSON.parse(text);}
export async function hash(value:string){return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(value))),b=>b.toString(16).padStart(2,'0')).join('');}
export function key(prefix='BSK'){const b=crypto.getRandomValues(new Uint8Array(24));return prefix+'-'+Array.from(b,x=>x.toString(16).padStart(2,'0')).join('').toUpperCase().match(/.{1,8}/g)!.join('-');}
export function event(id:string,action:string,now:number){return db().prepare('INSERT INTO audit(id,license_id,action,at) VALUES(?,?,?,?)').bind(crypto.randomUUID(),id,action,now);}
