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
    text=once(text,'      const _license = createLicense', (ROOT/'panel_bridge.js').read_text()+'\n      const _license = createLicense')
    text=once(text,'          _st.requested = !(_st.requested === null ? _ag.enabled : _st.requested);',
      '          if (_panelWanted && _panelForeground && _st.started) _st.requested = true;\n          _panelWanted = false;')
    text=once(text,'      const _license = createLicense(frida_java_bridge_default, {',
      '      const _license = createLicense(frida_java_bridge_default, {\n        surface: _panelSurface,\n        cancel() { _panelWanted = false; },\n        notice(message) { console.log("[IRIS BSHACK] " + message); },')
    # Android View UI replaces fragile native HUD allocation; retain game engine itself.
    start=text.index('      function _enu(now) {');end=text.index('      function _art() {',start)
    text=text[:start]+'      function _enu(now) {} // UI is owned by PanelHost on Android main thread.\n'+text[end:]
    start=text.index('        if (changed) {',text.index('      function _art() {'));end=text.index('      function _ih() {',start)
    text=text[:start]+'      }\n'+text[end:]
    # No unused original source-map or bundle metadata following the actual module.
    text=text.split('export default require_agent();')[0]+'export default require_agent();\n'
    body=text.encode()
    return f"📦\n{len(body)} /agent.js\n✄\n".encode()+body
