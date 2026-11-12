package com.irisai.autogoal.ingame;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import java.util.IdentityHashMap;
import java.util.Map;

/** Starts in the game's own process, before its Application.onCreate. No IPC API. */
public final class InitProvider extends ContentProvider {
    @Override public boolean onCreate() {
        if (getContext() == null || !getContext().getPackageName().equals("com.supercell.brawlstars")) return false;
        Application app = (Application) getContext().getApplicationContext();
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            final Map<Activity, GameController> controllers = new IdentityHashMap<>();
            @Override public void onActivityResumed(Activity a) {
                if (!a.getClass().getName().equals("com.supercell.brawlstars.GameApp")) return;
                try { controllers.computeIfAbsent(a, GameController::new).resume(); }
                catch (RuntimeException | LinkageError e) { Log.e("IrisAutoGoal", "Attach failed", e); }
            }
            @Override public void onActivityPaused(Activity a) {
                GameController c = controllers.get(a); if (c != null) c.pause();
            }
            @Override public void onActivityDestroyed(Activity a) {
                GameController c = controllers.remove(a); if (c != null) c.close();
            }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
        });
        return true;
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
