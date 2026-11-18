package com.supercell.brawlstars;
import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;
public final class GameApp extends com.supercell.titan.GameApp {
 public FrameLayout root;
 private android.graphics.Bitmap frame;
 public void frame(String name){
  try(java.io.InputStream in=getAssets().open("frames/"+name+".jpg")){
   if(frame!=null)frame.recycle();frame=android.graphics.BitmapFactory.decodeStream(in);paint();
  }catch(java.io.IOException e){throw new RuntimeException(e);}
 }
 private void paint(){
  if(surface==null||!surface.getHolder().getSurface().isValid())return;
  android.graphics.Canvas c=surface.getHolder().lockCanvas();if(c==null)return;
  try{c.drawColor(0xff163b42);if(frame!=null)c.drawBitmap(frame,null,new android.graphics.Rect(0,0,c.getWidth(),c.getHeight()),null);}
  finally{surface.getHolder().unlockCanvasAndPost(c);}
 }
 @Override public void onCreate(Bundle state) { super.onCreate(state); root = new FrameLayout(this); root.setBackgroundColor(0xff163b42);surface=new android.view.SurfaceView(this);root.addView(surface,new FrameLayout.LayoutParams(-1,-1));
  surface.getHolder().addCallback(new android.view.SurfaceHolder.Callback(){public void surfaceCreated(android.view.SurfaceHolder h){paint();}public void surfaceChanged(android.view.SurfaceHolder h,int f,int w,int height){paint();}public void surfaceDestroyed(android.view.SurfaceHolder h){}});
  setContentView(root);if(getIntent().getBooleanExtra("bridgecheck",false))System.loadLibrary("irisgadget"); }
}
