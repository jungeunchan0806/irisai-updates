// BRF installs its native hooks first. Iris then attaches to the final targets.
function _irisCompatibleWords(mod) {
  if(_NW.every(([off,word])=>mod.base.add(off).readU32()===word))return true;
  // The original memory build-ID check remains mandatory in _vb. Only the
  // installed verifier's exact SHA-256 match permits BRF-modified prologues.
  return _features.ready && !!(globalThis.__irisBoot && globalThis.__irisBoot.gameFileVerified===true);
}
const _wsa = Date.now();
function _wfl() {
  if (_st.disposed) return;
  const mod=[_d(_S.libg),_d(_S.libs),_d(_S.libbw)].map(n=>Process.findModuleByName(n)).find(Boolean);
  if(mod && (_features.ready || _features.error)){_start(mod);return;}
  if(Date.now()-_wsa>=60000){
    _st.error=mod?'native feature initialization timed out':'game library not found';return;
  }
  setTimeout(_wfl,100);
}
