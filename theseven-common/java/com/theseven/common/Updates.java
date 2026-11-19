package com.theseven.common;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.provider.Settings;
import android.util.Base64;
import android.widget.Toast;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

public final class Updates {
  public interface Data {
    int version();

    void apply(String catalog) throws Exception;
  }

  private static final String BASE =
      "https://raw.githubusercontent.com/jungeunchan0806/irisai-updates/theseven-practice/theseven-release/";

  static byte[] fetch(String filename, int limit) throws Exception {
    if (!filename.matches("[A-Za-z0-9._-]+")) throw new IOException();
    HttpsURLConnection c = (HttpsURLConnection) new URL(BASE + filename).openConnection();
    try {
      c.setConnectTimeout(9000);
      c.setReadTimeout(12000);
      c.setUseCaches(false);
      c.setInstanceFollowRedirects(false);
      if (c.getResponseCode() != 200) throw new IOException();
      try (InputStream in = c.getInputStream()) {
        return Net.read(in, limit);
      }
    } finally {
      c.disconnect();
    }
  }

  public static JSONObject verify(byte[] manifest, byte[] signature, byte[] der) throws Exception {
    Signature s = Signature.getInstance("SHA256withRSA");
    s.initVerify(KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der)));
    s.update(manifest);
    if (!s.verify(signature)) throw new GeneralSecurityException("패치 서명 오류");
    JSONObject m = new JSONObject(new String(manifest, StandardCharsets.UTF_8));
    if (m.getInt("schema") != 1 || !"theseven-practice".equals(m.getString("channel")))
      throw new IOException();
    return m;
  }

  static byte[] checked(JSONObject entry, int max) throws Exception {
    byte[] bytes = fetch(entry.getString("file"), max);
    if (!Net.hex(Net.digest(bytes)).equals(entry.getString("sha256")))
      throw new GeneralSecurityException("업데이트 해시 오류");
    return bytes;
  }

  public static void check(Activity a, Data data, boolean manual, Runnable onChange) {
    new Thread(
            () -> {
              try {
                JSONObject envelope =
                    new JSONObject(
                        new String(fetch("theseven-update.json", 16000), StandardCharsets.UTF_8));
                JSONObject m;
                try (InputStream key = a.getAssets().open("theseven_patch.der")) {
                  m =
                      verify(
                          Base64.decode(envelope.getString("payload"), 0),
                          Base64.decode(envelope.getString("signature"), 0),
                          Net.read(key, 2048));
                }
                JSONObject apk = m.getJSONObject("apps").getJSONObject(a.getPackageName()),
                    catalog = m.getJSONObject("catalog");
                long installed =
                    a.getPackageManager()
                        .getPackageInfo(a.getPackageName(), 0)
                        .getLongVersionCode();
                boolean nativeNeeded = apk.getLong("versionCode") > installed;
                boolean dataNeeded =
                    data != null
                        && catalog.getInt("version") > data.version()
                        && catalog.getInt("minApp") <= installed;
                a.runOnUiThread(
                    () -> {
                      if (a.isFinishing() || a.isDestroyed()) return;
                      if (!nativeNeeded && !dataNeeded) {
                        if (manual) message(a, "최신 버전입니다");
                        return;
                      }
                      new AlertDialog.Builder(a)
                          .setTitle("업데이트 확인")
                          .setMessage(m.optString("notes"))
                          .setPositiveButton(
                              "업데이트",
                              (d, w) ->
                                  new Thread(
                                          () -> {
                                            try {
                                              if (dataNeeded) {
                                                String raw =
                                                    new String(
                                                        checked(catalog, 2000000),
                                                        StandardCharsets.UTF_8);
                                                if (new JSONObject(raw).getInt("version")
                                                    != catalog.getInt("version"))
                                                  throw new IOException();
                                                data.apply(raw);
                                              }
                                              if (nativeNeeded) {
                                                byte[] bytes = checked(apk, 16000000);
                                                File tmp =
                                                    File.createTempFile(
                                                        "theseven-", ".apk", a.getCacheDir());
                                                try {
                                                  try (FileOutputStream f =
                                                      new FileOutputStream(tmp)) {
                                                    f.write(bytes);
                                                    f.getFD().sync();
                                                  }
                                                  PackageManager pm = a.getPackageManager();
                                                  PackageInfo next =
                                                      pm.getPackageArchiveInfo(
                                                          tmp.getPath(),
                                                          PackageManager.GET_SIGNING_CERTIFICATES);
                                                  PackageInfo own =
                                                      pm.getPackageInfo(
                                                          a.getPackageName(),
                                                          PackageManager.GET_SIGNING_CERTIFICATES);
                                                  if (next == null
                                                      || !own.packageName.equals(next.packageName)
                                                      || next.getLongVersionCode()
                                                          != apk.getLong("versionCode")
                                                      || !java.util.Arrays.equals(
                                                          next.signingInfo.getApkContentsSigners(),
                                                          own.signingInfo.getApkContentsSigners()))
                                                    throw new GeneralSecurityException();
                                                  if (!tmp.renameTo(
                                                      new File(
                                                          a.getCacheDir(), "theseven-update.apk")))
                                                    throw new IOException();
                                                } finally {
                                                  tmp.delete();
                                                }
                                              }
                                              a.runOnUiThread(
                                                  () -> {
                                                    if (a.isDestroyed() || a.isFinishing()) return;
                                                    if (dataNeeded) onChange.run();
                                                    if (nativeNeeded) {
                                                      if (!a.getPackageManager()
                                                          .canRequestPackageInstalls()) {
                                                        message(a, "설치 허용 후 업데이트 확인을 다시 눌러 주세요");
                                                        a.startActivity(
                                                            new Intent(
                                                                Settings
                                                                    .ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                                Uri.parse(
                                                                    "package:"
                                                                        + a.getPackageName())));
                                                      } else {
                                                        Uri uri =
                                                            Uri.parse(
                                                                "content://"
                                                                    + a.getPackageName()
                                                                    + ".theseven.update"
                                                                    + UpdateFile.PATH);
                                                        a.startActivity(
                                                            new Intent(Intent.ACTION_VIEW)
                                                                .setDataAndType(
                                                                    uri,
                                                                    "application/vnd.android.package-archive")
                                                                .addFlags(
                                                                    Intent
                                                                        .FLAG_GRANT_READ_URI_PERMISSION));
                                                      }
                                                    } else message(a, "연습 데이터 업데이트 완료");
                                                  });
                                            } catch (Exception e) {
                                              a.runOnUiThread(
                                                  () -> message(a, "업데이트 실패 · 연결 또는 서명을 확인하세요"));
                                            }
                                          })
                                      .start())
                          .setNegativeButton("나중에", null)
                          .show();
                    });
              } catch (Exception e) {
                if (manual) a.runOnUiThread(() -> message(a, "업데이트 정보를 확인할 수 없습니다"));
              }
            })
        .start();
  }

  static void message(Activity a, String s) {
    Toast.makeText(a, s, Toast.LENGTH_LONG).show();
  }
}
