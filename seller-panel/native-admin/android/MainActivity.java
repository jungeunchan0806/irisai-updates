package com.bshack.licensepanel;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.net.Uri;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayInputStream;

public final class MainActivity extends Activity {
 private static final String HOST="bshack-license-panel.jungeunchan0806.chatgpt.site";
 private static final String START="https://"+HOST+"/?app=admin-android&version=1.2.0";
 private WebView web;
 private boolean failed;
 private boolean allowed(Uri uri){
  if(!"https".equals(uri.getScheme())||!HOST.equals(uri.getHost())||(uri.getPort()!=-1&&uri.getPort()!=443))return false;
  String path=uri.getPath();
  return path!=null&&!path.startsWith("/signin-with-chatgpt")&&!path.startsWith("/signout-with-chatgpt")&&!path.startsWith("/callback");
 }
 @Override public void onCreate(Bundle state){
  super.onCreate(state);getWindow().setStatusBarColor(Color.rgb(18,21,29));getWindow().setNavigationBarColor(Color.rgb(18,21,29));
  CookieManager.getInstance().setAcceptCookie(true);
  web=new WebView(this);web.setFitsSystemWindows(true);web.setBackgroundColor(Color.rgb(18,21,29));
  WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setCacheMode(WebSettings.LOAD_NO_CACHE);settings.setUserAgentString(settings.getUserAgentString()+" BSHACK-Admin/1.2.0");
  CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
  web.addJavascriptInterface(new ClipboardBridge(),"BSHACKClipboard");
  web.setWebViewClient(new WebViewClient(){
   @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest request){return block(request.getUrl());}
   @Override public boolean shouldOverrideUrlLoading(WebView v,String url){return block(Uri.parse(url));}
   private boolean block(Uri uri){if(allowed(uri))return false;showError("관리자 로그인 페이지 이외의 연결은 열지 않습니다. 다시 연결해 주세요.");return true;}
   @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest request){
    if(allowed(request.getUrl()))return null;
    return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",java.util.Collections.<String,String>emptyMap(),new ByteArrayInputStream(new byte[0]));
   }
   @Override public void onReceivedError(WebView v,WebResourceRequest request,WebResourceError error){if(request.isForMainFrame())showError("관리자 서버에 연결하지 못했습니다. 인터넷 연결을 확인하고 다시 시도해 주세요.");}
   @Override public void onPageFinished(WebView v,String url){if(!failed)CookieManager.getInstance().flush();}
  });
  openPanel();
 }
 private void openPanel(){failed=false;setContentView(web);web.loadUrl(START);}
 private void showError(String message){runOnUiThread(()->{if(failed)return;failed=true;web.stopLoading();LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(40,80,40,40);box.setBackgroundColor(Color.rgb(18,21,29));TextView text=new TextView(this);text.setText(message);text.setTextColor(Color.WHITE);text.setTextSize(17);box.addView(text);Button retry=new Button(this);retry.setText("관리자 로그인 다시 열기");retry.setOnClickListener(v->openPanel());box.addView(retry);setContentView(box);});}
 public final class ClipboardBridge {
  @JavascriptInterface public void copyText(final String value){if(value==null||value.length()>16384)return;runOnUiThread(()->{if(!failed&&web.getUrl()!=null&&allowed(Uri.parse(web.getUrl()))){ClipboardManager clipboard=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText("BSHACK",value));}});}
 }
 @Override public void onBackPressed(){if(!failed&&web.canGoBack())web.goBack();else super.onBackPressed();}
 @Override protected void onDestroy(){if(web!=null){web.removeJavascriptInterface("BSHACKClipboard");web.destroy();}super.onDestroy();}
}
