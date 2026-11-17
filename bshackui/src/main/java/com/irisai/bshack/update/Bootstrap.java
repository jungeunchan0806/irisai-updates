package com.irisai.bshack.update;
import android.app.Application;
/** Loaded only from the installed APK; downloaded code cannot replace this verifier. */
public final class Bootstrap {
 public static String select(Application app)throws Exception{return new PatchStore(app).select().toString();}
}
