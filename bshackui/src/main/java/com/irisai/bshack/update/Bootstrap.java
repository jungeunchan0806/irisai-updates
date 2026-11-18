package com.irisai.bshack.update;
import android.app.Application;
/** Loaded only from the installed APK; downloaded code cannot replace this verifier. */
public final class Bootstrap {
 public static String select(Application app)throws Exception{
  boolean game=GameFileIdentity.verify(app);
  String features=com.irisai.bshack.NativeFeatures.load(app);
  return new PatchStore(app).select().put("featuresError",features).put("gameFileVerified",game).toString();
 }
}
