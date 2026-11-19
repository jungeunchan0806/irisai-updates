package dev.iris.inspect;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.*;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.*;
import org.json.*;

/** A read-only UI inspector for our blank emulator. Android keeps the player's service running. */
public final class Inspect extends Instrumentation {
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  private static String string(CharSequence s) {
    return s == null ? "" : s.toString();
  }

  private void visit(AccessibilityNodeInfo node, JSONArray out) throws JSONException {
    if (node == null) return;
    try {
      if (!node.isVisibleToUser() || out.length() > 700) return;
      Rect r = new Rect();
      node.getBoundsInScreen(r);
      out.put(
          new JSONObject()
              .put("class", string(node.getClassName()))
              .put("package", string(node.getPackageName()))
              .put("text", string(node.getText()))
              .put("hint", string(node.getHintText()))
              .put("content-desc", string(node.getContentDescription()))
              .put("resource-id", string(node.getViewIdResourceName()))
              .put("bounds", r.toShortString())
              .put("enabled", String.valueOf(node.isEnabled())));
      for (int i = 0; i < node.getChildCount(); i++) visit(node.getChild(i), out);
    } finally {
      node.recycle();
    }
  }

  public void onStart() {
    Bundle results = new Bundle();
    try {
      UiAutomation ui = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
      AccessibilityServiceInfo info = ui.getServiceInfo();
      info.flags |=
          AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
              | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
      ui.setServiceInfo(info);
      JSONArray nodes = new JSONArray();
      for (AccessibilityWindowInfo window : ui.getWindows()) {
        try {
          visit(window.getRoot(), nodes);
        } finally {
          window.recycle();
        }
      }
      results.putString("snapshot", nodes.toString());
      finish(Activity.RESULT_OK, results);
    } catch (Exception e) {
      results.putString("error", e.toString());
      finish(Activity.RESULT_CANCELED, results);
    }
  }
}
