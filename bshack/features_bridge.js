// Reuse the existing native feature implementation; never load a second engine.
const _features = { module:null, bridge:null, snapshot:null, submit:null,
  buffer:Memory.alloc(104), error:'', frame:-1, seenAt:0, ready:false, config:{} };
const _featureSlots = [1,2,4,5,9,14,16,17,18,19,25,26,48,49,50,51,52,53,54,55];
function _featuresBind() {
  if (_features.module || _features.error) return;
  if(globalThis.__irisBoot && globalThis.__irisBoot.featuresError){_features.error=globalThis.__irisBoot.featuresError;return;}
  let mod = Process.findModuleByName('libbrf.so');
  if (!mod) {
    if (!_panelHost) return;
    try {
      const error=String(_panelHost.loadNativeFeatures());
      if(error){_features.error=error;return;}
      mod=Process.findModuleByName('libbrf.so');
      if(!mod){_features.error='Native feature initialization did not load BRF';return;}
    }catch(e){_features.error=String(e);return;}
  }
  try {
    if (Process.arch !== 'arm64' || mod.size < 0x87000 ||
        mod.base.add(0x34e48).readU32() !== 0xd10343ff ||
        mod.base.add(0x33380).readU32() !== 0xa9be7bfd ||
        mod.base.add(0x3339c).readU32() !== 0x79000c3f)
      throw new Error('Unsupported feature module; original module left intact');
    const state=Memory.alloc(1024);state.writeByteArray(new Uint8Array(1024));
    const bridge = new CModule("__IRIS_FEATURE_NATIVE_SOURCE__", {iris_apply:mod.base.add(0x33380),iris_state:state});
    _features.snapshot = new NativeFunction(bridge.snapshot,'void',['pointer']);
    _features.submit = new NativeFunction(bridge.submit,'int',['int','int']);
    Interceptor.replace(mod.base.add(0x34e48),bridge.render);
    _features.bridge=bridge;_features.state=state;_features.module=mod;
  } catch(e) { _features.error=String(e); }
}
function _featuresPoll() {
  _featuresBind();
  if (!_panelHost) return;
  if (!_features.snapshot) {
    if(_features.error)_panelHost.updateFeatures(JSON.stringify({ready:false,values:{},error:_features.error}));
    return;
  }
  try {
    _features.snapshot(_features.buffer);
    const p=_features.buffer, frame=p.add(4).readS32(), now=_panelTime();
    if(frame!==_features.frame){_features.frame=frame;_features.seenAt=now;}
    _features.ready=p.readS32()!==0 && now-_features.seenAt<3000;
    for(const slot of _featureSlots) _features.config[String(slot)]=p.add(16+slot).readU8();
    for(const slot of [56,60,64]) _features.config[String(slot)]=p.add(16+slot).readS32();
    _panelHost.updateFeatures(JSON.stringify({ready:_features.ready,values:_features.config,
      fps:p.add(8).readS32(),error:p.add(12).readS32()?'설정을 적용하지 못했습니다':_features.error}));
    for(let i=0;i<16;i++) {
      const text=_panelHost.pollFeature();if(!text)break;
      const command=JSON.parse(String(text));
      if(_panelForeground && _features.ready && !_features.submit(command.slot,command.value))
        _features.error='Invalid feature setting';
    }
  } catch(e) { _features.error=String(e); }
}
