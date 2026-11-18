// Client-side recovery only. A changing local tick is not a server acknowledgement.
// This code never changes packets, extends a license, or hides a server disconnect.
class ConnectionGate {
  constructor(clock) {
    this.clock=clock;this.network='unknown';this.focused=true;this.networkGeneration=null;
    this.key=null;this.tick=null;this.pulseAt=0;this.progressAt=0;this.stableAt=0;
    this.samples=0;this.epoch=0;this.barrier='waiting';this.recoveries=0;
  }
  hold(reason,now=this.clock()) {
    if(this.barrier!==reason){this.epoch++;this.barrier=reason;}
    this.samples=0;this.stableAt=now;
  }
  sync(data) {
    const state=['online','offline','unknown'].includes(data.network)?data.network:'unknown';
    const focused=data.focused===true;
    const generation=Number.isSafeInteger(data.generation)?data.generation:0;
    if(this.network!==state||this.focused!==focused||this.networkGeneration!==generation)
      this.hold(state==='offline'?'offline':!focused?'screen':'recovering');
    this.network=state;this.focused=focused;this.networkGeneration=generation;
  }
  observe(key,tick) {
    const now=this.clock();
    if(!key||!Number.isInteger(tick)){this.clear();return;}
    const changed=this.key!==key;
    const stale=this.key!==null&&(now-this.pulseAt>1800||now-this.progressAt>1800);
    const backwards=!changed&&this.tick!==null&&tick<this.tick;
    if(changed||backwards){this.hold('recovering',now);this.tick=null;}
    else if(stale)this.hold('stalled',now);
    this.key=key;this.pulseAt=now;
    if(this.tick!==tick){
      this.tick=tick;this.progressAt=now;
      if(this.focused&&this.network!=='offline'){
        if(!this.samples)this.stableAt=now;
        this.samples=Math.min(100,this.samples+1);
      }
    }
    if(this.focused&&this.network!=='offline'&&this.samples>=3&&now-this.stableAt>=600){
      if(this.barrier){this.recoveries++;this.barrier='';}
    }
  }
  clear() {
    if(this.key!==null)this.epoch++;
    this.key=null;this.tick=null;this.samples=0;this.barrier='waiting';
  }
  status() {
    const now=this.clock();let reason;
    if(!this.focused)reason='screen';
    else if(this.network==='offline')reason='offline';
    else if(this.key===null)reason='waiting';
    else if(now-this.pulseAt>1800||now-this.progressAt>1800){reason='stalled';this.hold(reason,now);}
    else if(this.samples<3||now-this.stableAt<600||this.barrier)reason='recovering';
    else reason='ready';
    return {reason,ready:reason==='ready',farmBlocked:reason==='offline'||reason==='screen'||(this.key!==null&&reason!=='ready'),
      network:this.network,epoch:this.epoch,recoveries:this.recoveries,
      tickAgeMs:this.key===null?null:Math.max(0,Math.round(now-this.progressAt))};
  }
}
const _connection=new ConnectionGate(()=>_panelTime());
let _connectionEpoch=-1;
function _connectionPrune() {
  if(_connectionEpoch===_connection.epoch)return;
  _connectionEpoch=_connection.epoch;
  _bs.snapshot=null;_ag.battle=NULL2;_ag.ball=_ag.carriedKey=_ag.pickupRoute=null;
  _ag.moveTarget=_ag.route=_ag.routeGoal=_ag.routePlan=null;
  _ag.mapSize=_ag.goal=null;_ag.routeFailureAt=0;
  _ag.lastCheckAt=_ag.lastCarryCheckAt=_ag.lastMoveAt=_ag.lastBallScanAt=0;
  if(_ag.ballDataNames)_ag.ballDataNames.clear();
}
function _connectionBeforeTick(bmc) {
  try {
    const active=_gmb();
    if(!active||active.isNull()||!active.equals(bmc)||!_cfm(bmc,256)){
      _connection.clear();_connectionPrune();return false;
    }
    if(_connection.key!==bmc.toString())_MS.battle=_MS.battleClient=NULL2;
    _connection.observe(bmc.toString(),bmc.add(_O.j).readS32());
    const status=_connection.status();_connectionPrune();
    if(!status.ready){_ag.lastWaitReason='connectionWait';return false;}
    if(_ag.lastWaitReason==='connectionWait')_ag.lastWaitReason=null;
    return true;
  }catch(error){
    _connection.hold('context');_connectionPrune();_ag.lastWaitReason='connectionWait';
    return false;
  }
}
function _connectionPoll() {
  _connection.sync(JSON.parse(String(_panelHost.connectionStatus())));
  const status=_connection.status();_connectionPrune();
  _panelHost.updateConnection(JSON.stringify(status));
  return status;
}
