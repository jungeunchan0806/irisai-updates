package com.irisai.bshack.check;
import android.app.*;
import android.content.DialogInterface;
import java.lang.reflect.*;
import java.util.List;
final class DialogChecks {
 private static void require(boolean value,String label,List<String> checks){if(!value)throw new AssertionError(label);checks.add(label);}
 static void run(Activity activity,ClassLoader loader,List<String> checks){try{
  Class<?> type=loader.loadClass("com.irisai.bshack.update.UpdateManager");Object manager=type.getConstructor(Application.class).newInstance(activity.getApplication());
  Method attach=type.getMethod("attach",Activity.class,boolean.class),pause=type.getMethod("pause",Activity.class);
  attach.invoke(manager,activity,false);
  Method show=type.getDeclaredMethod("show",String.class,String.class,String.class,Runnable.class);show.setAccessible(true);
  AlertDialog dialog=(AlertDialog)show.invoke(manager,"업데이트 하시겠습니까?","새 패치와 변경 내용","확인",null);
  require(dialog.isShowing()&&dialog.getButton(DialogInterface.BUTTON_POSITIVE).isShown(),"update prompt shows confirmation",checks);
  require(!dialog.getButton(DialogInterface.BUTTON_NEGATIVE).isShown()&&!dialog.getButton(DialogInterface.BUTTON_NEUTRAL).isShown(),"update prompt has no decline button",checks);
  dialog.onBackPressed();require(dialog.isShowing(),"back does not dismiss the required update prompt",checks);
  pause.invoke(manager,activity);require(!dialog.isShowing(),"update dialog detaches while activity is paused",checks);
  attach.invoke(manager,activity,false);Field field=type.getDeclaredField("dialog");field.setAccessible(true);AlertDialog restored=(AlertDialog)field.get(manager);
  require(restored.isShowing(),"pending update prompt returns on resume",checks);
  restored.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
 }catch(Exception e){throw new AssertionError(e);}}
}
