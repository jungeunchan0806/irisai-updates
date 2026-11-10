// Input timing contract, independent of Android and Frida.
export class Gate {
  constructor(now) { this.now=now; this.enabled=false; this.epoch=0; this.sequence=-1; this.heartbeat=-Infinity; }
  arm(enabled) { this.enabled=enabled===true; this.epoch++; this.sequence=-1; this.heartbeat=this.now(); return this.epoch; }
  ping(epoch) { if(!this.enabled || epoch!==this.epoch)return false; this.heartbeat=this.now();return true; }
  valid(epoch) { return this.enabled && epoch===this.epoch && this.now()>=this.heartbeat && this.now()-this.heartbeat<=1000; }
  accept(c) {
    if(!c || !this.valid(c.epoch) || !Number.isSafeInteger(c.sequence) || c.sequence<=this.sequence)return false;
    if(!Number.isFinite(c.duration)||c.duration<25||c.duration>180)return false;
    for(const k of ['x1','y1','x2','y2'])if(!Number.isFinite(c[k])||c[k]<0||c[k]>1)return false;
    this.sequence=c.sequence; return true;
  }
}
