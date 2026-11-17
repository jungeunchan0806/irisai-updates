// Executed inside the existing licensed engine closure. No Java calls from game hooks.
let _panelHost = null, _panelPending = false, _panelAttempts = 0;
let _panelWanted = false, _panelQueued = false, _panelForeground = false, _panelError = '';
const _Java = frida_java_bridge_default;
function _panelStop() { _panelWanted = false; _panelQueued = false; _st.requested = false; }
function _panelActivate() {
  if (_panelWanted && _panelForeground && _st.started) _st.requested = true;
  _panelWanted = false; _panelQueued = false;
}
function _panelSurface(surface) {
  if (!_panelHost) return;
  try { _panelHost.attach(_Java.cast(surface.getContext(), _Java.use('android.app.Activity'))); }
  catch (e) { _panelError = String(e); }
}
function _panelInitialize(app) {
  const input = app.getAssets().open('iris-panel.dex');
  const out = _Java.use('java.io.ByteArrayOutputStream').$new();
  const buffer = _Java.array('byte', new Array(8192).fill(0));
  try { let n; while ((n = input.read(buffer)) > 0) out.write(buffer, 0, n); }
  finally { input.close(); }
  const bytes = out.toByteArray(); out.close();
  const loader = _Java.use('dalvik.system.InMemoryDexClassLoader').$new(
    _Java.use('java.nio.ByteBuffer').wrap(bytes), app.getClassLoader());
  _panelHost = _Java.ClassFactory.get(loader).use('com.irisai.bshack.PanelHost');
  _panelHost.initialize(_Java.cast(app, _Java.use('android.app.Application')));
  console.log('[IRIS BSHACK] UI DEX loaded');
}
function _panelFindActivity() {
  const records = _Java.use('android.app.ActivityThread').currentActivityThread().mActivities.value;
  const iter = records.values().iterator();
  while (iter.hasNext()) {
    const record = _Java.cast(iter.next(), _Java.use('android.app.ActivityThread$ActivityClientRecord'));
    if (!record.paused.value && record.activity.value) {
      const activity = record.activity.value;
      _panelHost.attach(activity);
      _license.bindView(activity.getWindow().getDecorView());
    }
  }
}
function _panelPhase() {
  if (_panelError) return 'UI 연결 오류';
  if (!_st.started) return _st.error ? '게임 연결 실패' : '게임 로딩 중';
  const auth = _license.status();
  if (!auth.ready) return '인증 준비 중';
  if (auth.error) return '인증 연결 실패';
  if (_panelWanted) return '인증 확인 중';
  if (auth.checked && !auth.active) return auth.licenseStatus === 'revoked' ? '이용 정지' : auth.licenseStatus === 'expired' ? '기간 만료' : 'HWID 등록 필요';
  if (_ag.lastError) return '오토골 오류 · 다시 켜세요';
  if (_ag.enabled && _ag.lastWaitReason === 'inputRejected') return _MS.checked ? '게임 입력 연결 대기' : '조이스틱을 한 번 움직이세요';
  const labels = {idle:'브롤볼 대기',findBall:'공 찾는 중',dribble:'골대로 운반',carry:'골대로 운반',shoot:'조준 슛'};
  return labels[_ag.phase] || (_ag.enabled ? '오토골 실행 중' : '브롤볼 대기');
}
function _panelPoll() {
  if (_st.disposed || _panelPending) return;
  _panelPending = true;
  try {
    _Java.perform(() => _Java.scheduleOnMainThread(() => {
      try {
        if (!_panelHost) {
          if (++_panelAttempts > 40) return;
          const app = _Java.use('android.app.ActivityThread').currentApplication();
          if (!app) return;
          _panelInitialize(app);
        }
        if (!_panelHost.isResumed() && _panelAttempts < 40) {
          _panelAttempts++;
          try { _panelFindActivity(); } catch (e) { console.log('[IRIS BSHACK] Waiting for activity: ' + e); }
        }
        _panelForeground = _panelHost.isResumed();
        if (!_panelForeground) _panelStop();
        for (let i = 0; i < 8; i++) {
          const command = _panelHost.poll();
          if (!command) break;
          if (command === 2) _panelStop();
          else if (command === 3) _license.copy();
          else if (command === 1) {
            if (_ag.enabled || _st.requested === true || _panelWanted) _panelStop();
            else if (_panelForeground && !_st.error) { _panelWanted = true; _panelQueued = true; }
          }
        }
        if (_panelWanted && _panelQueued && _st.started && _license.status().ready) {
          _panelQueued = false; _license.tap();
        }
        const status = _license.status();
        if (status.error && !_panelQueued) _panelWanted = false;
        _art();
        _panelHost.update(_ag.enabled, _panelPhase());
      } catch (e) { _panelError = String(e); _panelStop(); console.error('[IRIS BSHACK] UI: ' + e); }
      finally { _panelPending = false; }
    }));
  } catch (e) { _panelPending = false; _panelStop(); console.error('[IRIS BSHACK] Schedule: ' + e); }
}
setInterval(_panelPoll, 250);
