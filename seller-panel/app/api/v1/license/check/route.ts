import {client} from '@/lib/license-server';
export const dynamic='force-dynamic';
export async function POST(r:Request){return client(r);}
