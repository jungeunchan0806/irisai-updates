import {env} from 'cloudflare:workers';
import {decode64,encode64} from './device-proof';
const encoder=new TextEncoder();
async function encryptionKey(){
 if(!env.SELLER_KEY_ENCRYPTION_KEY)throw Error('Key vault unavailable');
 const bytes=decode64(env.SELLER_KEY_ENCRYPTION_KEY);if(bytes.length!==32)throw Error('Key vault misconfigured');
 return crypto.subtle.importKey('raw',bytes,{name:'AES-GCM'},false,['encrypt','decrypt']);
}
export async function encryptKey(value:string,id:string){const iv=crypto.getRandomValues(new Uint8Array(12));const encrypted=await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:encoder.encode(id)},await encryptionKey(),encoder.encode(value));return 'v1.'+encode64(iv)+'.'+encode64(new Uint8Array(encrypted));}
export async function decryptKey(value:string,id:string){const [v,iv,data]=value.split('.');if(v!=='v1')throw Error('Unsupported ciphertext');return new TextDecoder().decode(await crypto.subtle.decrypt({name:'AES-GCM',iv:decode64(iv),additionalData:encoder.encode(id)},await encryptionKey(),decode64(data)));}
