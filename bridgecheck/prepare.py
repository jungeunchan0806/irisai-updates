import json
from pathlib import Path
r=Path(__file__).resolve().parents[1]
prefix=(r/'bridgecheck/java-bridge.js').read_text().split('// frida-shim:')[0]
engine=prefix+'''function init_node_globals(){};
const frida_java_bridge_default=globalThis.__irisJava;
const _st={started:true,disposed:false,requested:null};
const _ag={enabled:false,phase:'idle',carriedKey:null,ball:null};const _MS={checked:0};
'''+(r/'bshack/licensing_bundle.js').read_text()+'''
init_licensing();
const _license=createLicense(frida_java_bridge_default,{toggle(){_panelActivate();},cancel(){_panelWanted=false;},disabled(){_st.requested=false;}});
'''+(r/'bshack/engine_state.js').read_text()+(r/'bshack/features_bridge.js').read_text().replace('\"__IRIS_FEATURE_NATIVE_SOURCE__\"',json.dumps((r/'bshack/features_native.c').read_text()))+(r/'bshack/connection_guard.js').read_text()+(r/'bshack/panel_bridge.js').read_text()+(r/'bridgecheck/update-isolation.js').read_text()+(r/'bridgecheck/connection-fixture.js').read_text()+(r/'bridgecheck/fixture-api.js').read_text()+'\nexport default true;\n'
out=r/'bridgecheck/generated';out.mkdir(parents=True,exist_ok=True)
(out/'iris-engine.mjs').write_text(engine)
print('Prepared production bootstrap/bridge with isolated native-state fixture.')

(out/'features-native-test.js').write_text((r/'bshack/features-native-test.js').read_text().replace('SOURCE',json.dumps((r/'bshack/features_native.c').read_text())))
