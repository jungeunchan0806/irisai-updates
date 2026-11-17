// Pure engine state commit: shared by the UI scheduler and the game update hook.
// No Android API, native HUD allocation or game input is invoked here.
function _art() {
  if (!_license.isActive() && (_ag.enabled || _st.requested === true)) _st.requested = false;
  if (_st.requested === null) return;
  _ag.enabled = !!_st.requested;
  _st.requested = null;
  _ag.stopPending = !_ag.enabled;
  _ag.lastCheckAt = _ag.lastCarryCheckAt = _ag.lastMoveAt = 0;
  _ag.carriedKey = _ag.pickupRoute = _ag.ball = null;
  _ag.route = _ag.routeGoal = _ag.routePlan = null;
  _ag.routeFailureAt = 0;
  _ag.lastWaitReason = _ag.lastError = null;
  if (_ag.enabled) _ag.moveTarget = null;
  _ag.phase = _ag.enabled ? 'findBall' : 'idle';
}
