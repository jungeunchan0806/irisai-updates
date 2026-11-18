// CI only: mapped native memory and synthetic network changes, never the game APK.
const NULL2=ptr(0),_bs={snapshot:null},_O={j:76};
// Frida hides its own Memory.alloc regions from Process.findRangeByAddress.
// Use the process allocator, as real game objects do, so pointer checks stay real.
const _fixtureCalloc=new NativeFunction(Module.getGlobalExportByName("calloc"),"pointer",["ulong","ulong"]);
let _fixtureBattle=_fixtureCalloc(1,256),_fixtureTick=0,_fixtureAdvance=true,_fixtureInputs=0;
let _fixtureNetwork=null,_fixtureNetworkGeneration=100,_fixtureHost=null;
function _gmb(){return _fixtureBattle;}
function _cfm(p,size){const r=Process.findRangeByAddress(p);return !!r&&r.protection.includes('r')&&p.add(size).compare(r.base.add(r.size))<=0;}
setInterval(()=>{
 if(_fixtureAdvance)_fixtureBattle.add(76).writeS32(++_fixtureTick);
 if(_connectionBeforeTick(_fixtureBattle)&&_ag.enabled&&_license.isActive())_fixtureInputs++;
},50);
function _fixtureLink(mode){
 if(!_fixtureHost){
  _fixtureHost=_panelHost;
  _panelHost=new Proxy(_fixtureHost,{get(target,key){
   if(key==='connectionStatus'&&_fixtureNetwork)return ()=>JSON.stringify(_fixtureNetwork);
   return target[key];
  }});
 }
 _fixtureAdvance=mode!=='stalled';
 const network=mode==='offline'?'offline':'online';
 if(!_fixtureNetwork||_fixtureNetwork.network!==network||mode==='handoff')_fixtureNetworkGeneration++;
 _fixtureNetwork={network,focused:mode!=='screen',generation:_fixtureNetworkGeneration};
 return true;
}
