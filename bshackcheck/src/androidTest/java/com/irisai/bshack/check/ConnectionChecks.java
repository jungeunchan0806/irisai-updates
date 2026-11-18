package com.irisai.bshack.check;
import android.app.Activity;
import android.view.*;
import android.widget.TextView;
import java.lang.reflect.*;
import java.util.List;
import org.json.JSONObject;

final class ConnectionChecks {
 private static boolean text(View v,String label){if(v instanceof TextView&&label.contentEquals(((TextView)v).getText()))return true;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(text(((ViewGroup)v).getChildAt(i),label))return true;return false;}
 private static void require(boolean b,String label,List<String> checks){if(!b)throw new AssertionError(label);checks.add(label);}
 static void run(Activity activity,Class<?> host,List<String> checks){try{
  JSONObject state=new JSONObject((String)host.getMethod("connectionStatus").invoke(null));
  require(java.util.Arrays.asList("online","offline","unknown").contains(state.getString("network"))&&state.getLong("generation")>=0,"Android default-network observer is readable from child-loaded DEX",checks);
  require(state.getBoolean("focused")==activity.hasWindowFocus(),"connection observer reports actual game-window focus",checks);
  Method update=host.getMethod("updateConnection",String.class);
  update.invoke(null,"{\"reason\":\"offline\",\"farmBlocked\":true}");
  require(text(activity.findViewById(android.R.id.content),"Waiting for network"),"connection hold is visible in the original Iris menu",checks);
  update.invoke(null,"{\"reason\":\"ready\",\"farmBlocked\":false}");
  require(text(activity.findViewById(android.R.id.content),"Game state updating"),"connection recovery refreshes the existing status card",checks);
 }catch(Exception e){throw new AssertionError(e);}}
}
