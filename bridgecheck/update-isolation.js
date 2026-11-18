// CI only. Updater dialogs and signature checks have separate explicit fixtures.
// Never let a changing public channel steal focus during connection/input tests.
const _initializeFixturePanel=_panelInitialize;
_panelInitialize=function(app){
  _initializeFixturePanel(app);
  const type=_panelHost.class;
  const hostField=type.getDeclaredField('host');hostField.setAccessible(true);
  const updatesField=type.getDeclaredField('updates');updatesField.setAccessible(true);
  const updates=updatesField.get(hostField.get(null));
  const flag=updates.getClass().getDeclaredField('startupChecked');flag.setAccessible(true);
  flag.setBoolean(updates,true);
};
