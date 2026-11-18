"""Apply only auditable script changes to the user-provided BSHACK baseline."""
from pathlib import Path
import hashlib

ROOT=Path(__file__).resolve().parent

def once(text, old, new):
    assert text.count(old)==1, f'Expected one patch anchor: {old[:80]}'
    return text.replace(old,new)

def patch(data):
    text=data.decode()
    if text.startswith("📦\n"):
        text=text.split("✄\n",1)[1]
    start=text.index('// core.mjs\n');end=text.index('// agent.js\n')
    text=text[:start]+(ROOT/'licensing_bundle.js').read_text()+'\n'+text[end:]
    text=once(text,'      const _license = createLicense', (ROOT/'connection_guard.js').read_text()+'\n'+(ROOT/'panel_bridge.js').read_text()+'\n      const _license = createLicense')
    text=once(text,'          _st.requested = !(_st.requested === null ? _ag.enabled : _st.requested);',
      '          _panelActivate();')
    text=once(text,'      const _license = createLicense(frida_java_bridge_default, {',
      '      const _license = createLicense(frida_java_bridge_default, {\n        surface: _panelSurface,\n        cancel() { _panelWanted = false; },\n        notice(message) { console.log("[IRIS BSHACK] " + message); },')
    # Enforce stop even when the render loop pauses but battle ticks continue.
    text=once(text,'      function _uag(bmc, now) {',
      '      function _uag(bmc, now) {\n        if (_ag.enabled && (!_panelForeground || _panelTime()-_panelLastTick>3000 || _st.requested === false || !_license.isActive())) {\n          _ag.enabled = false; _ag.stopPending = true; _st.requested = false;\n        }')
    # Android View UI replaces fragile native HUD allocation; retain game engine itself.
    start=text.index('      function _enu(now) {');end=text.index('      function _art() {',start)
    text=text[:start]+'      function _enu(now) {} // UI is owned by PanelHost on Android main thread.\n'+text[end:]
    start=text.index('      function _art() {');end=text.index('      function _ih() {',start)
    text=text[:start]+(ROOT/'engine_state.js').read_text()+'\n'+text[end:]
    text=once(text,'          _uag(args[0], Date.now());',
      '          try { _art(); if (_connectionBeforeTick(args[0])) _uag(args[0], Date.now()); }\n'
      '          catch (error) { _connection.hold("context"); _connectionPrune(); _ag.lastError=String(error); }')
    text=once(text,'      function _smi(x, y) {','      function _smi(x, y) {\n        if (!_connection.status().ready) return false;')
    start=text.index('      function _gmb() {');end=text.index('      function _gmc() {',start)
    text=text[:start]+'''      function _gmb() {
        try {
          const game=_gmg();
          if(!_cfm(game,_O.c+8))return NULL2;
          const battle=game.add(_O.c).readPointer();
          return _cfm(battle,256)?battle:NULL2;
        } catch (_) { return NULL2; }
      }
'''+text[end:]
    text=once(text,'                _MS.battle = _MS.battleClient = NULL2;',
      '                _MS.battle = _MS.battleClient = NULL2;\n                _connection.clear(); _connectionPrune();')

    # No unused original source-map or bundle metadata following the actual module.
    text=text.split('export default require_agent();')[0]+'export default require_agent();\n'
    # Share the already initialized Java runtime with the immutable bootstrap.
    # Two independent bridges would install competing ART/main-loop hooks.
    helpers=text[:text.index('// frida-shim:')]
    text=helpers+'\nvar frida_java_bridge_default=globalThis.__irisJava;\nfunction init_node_globals() {}\nfunction init_frida_java_bridge() {}\n'+text[text.index('// core.mjs\n'):]
    body=text.encode()
    return f"📦\n{len(body)} /agent.js\n✄\n".encode()+body
