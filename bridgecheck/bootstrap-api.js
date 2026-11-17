rpc.exports={
 boot(){return {selected:globalThis.__irisBoot||null,canary:globalThis.__irisPatchFixture===true};},
 installfixture(){return new Promise((resolve,reject)=>_bootJava.perform(()=>{try{
  const app=_bootJava.use('android.app.ActivityThread').currentApplication();
  const bytes=_bootRead(app.getAssets().open('iris-bootstrap.dex'));
  const loader=_bootJava.use('dalvik.system.InMemoryDexClassLoader').$new(_bootJava.use('java.nio.ByteBuffer').wrap(bytes),app.getClassLoader());
  const Store=_bootJava.ClassFactory.get(loader).use('com.irisai.bshack.update.PatchStore');
  const m=Store.verifyManifest(_bootRead(app.getAssets().open('manifest.json')),_bootRead(app.getAssets().open('manifest.sig')));
  Store.$new(app).install(m,_bootRead(app.getAssets().open('patch.zip')),22000);
  resolve(true);
 }catch(e){reject(String(e));}}));}
};
