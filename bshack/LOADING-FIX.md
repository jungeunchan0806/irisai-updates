# Integrated native initialization fix (2.3.2)

The 2.3.0/2.3.1 feature renderer marked BRF ready when its context API
pointer became non-null. It also called BRF's configuration callback during
initialization. A non-null API is insufficient: the native worker can still
be installing hooks at that point. Iris then used the premature ready bit
to install its own game hooks.

The supplied, pinned ARM64 BRF binary provides a stricter signal. Its caller
at `0x34304` reads module progress, and at `0x34310` reads initialization
state. `0x34320` compares state with 3 and passes the result in `w4` to the
menu renderer at `0x34340`. The initializer sets progress to 100 and then
state to 3 only after its setup routine returns successfully. These values
describe native module initialization, **not a game-server login result**.

The Iris bridge now requires that completion flag, progress >= 100, and the
API pointer before applying queued settings or reporting readiness. Pending
settings survive loading. Iris game-hook installation continues to use this
ready bit. Module progress/completion now appear in copied diagnostics.

Regression: the previous bridge reported ready and applied settings for a
`progress=0, interactive=0` frame even with a valid API pointer. The actual
Frida CModule test now covers that case, partial progress, a missing completion
flag at 100%, queued settings, and normal post-initialization changes.

The user's server-connection stall began with 2.3.0, before the connection
recovery patch. This initialization defect is reproduced at the bridge
boundary; the full server-connection stall is not reproduced on the user's
phone. Neither server responses nor original JNI, network, attestation,
game libraries, and signing identity are modified by this small patch.
Actual device confirmation is still needed to establish whether this fixes
the reported loading screen.
