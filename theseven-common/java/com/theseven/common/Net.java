package com.theseven.common;

import android.security.keystore.*;
import android.util.Base64;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

public final class Net {
  private static final String HOST = "https://bshack-license-panel.jungeunchan0806.chatgpt.site";
  private static final String ALIAS = "theseven.device.v1";

  public static byte[] read(InputStream in, int cap) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] b = new byte[8192];
    int n;
    while ((n = in.read(b)) != -1) {
      if (out.size() + n > cap) throw new IOException("크기 제한");
      out.write(b, 0, n);
    }
    return out.toByteArray();
  }

  public static String hex(byte[] bytes) {
    StringBuilder s = new StringBuilder();
    for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02x", b & 255));
    return s.toString();
  }

  static byte[] digest(byte[] b) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(b);
  }

  static String b64(byte[] b) {
    return Base64.encodeToString(b, Base64.NO_WRAP);
  }

  private static synchronized KeyStore keys() throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    if (!ks.containsAlias(ALIAS)) {
      KeyPairGenerator g = KeyPairGenerator.getInstance("EC", "AndroidKeyStore");
      g.initialize(
          new KeyGenParameterSpec.Builder(
                  ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
              .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
              .setDigests(KeyProperties.DIGEST_SHA256)
              .build());
      g.generateKeyPair();
    }
    return ks;
  }

  private static String pub(KeyStore ks) throws Exception {
    return b64(ks.getCertificate(ALIAS).getPublicKey().getEncoded());
  }

  public static String fingerprint() throws Exception {
    return hex(digest(pub(keys()).getBytes(StandardCharsets.US_ASCII)));
  }

  static byte[] rawSignature(byte[] der) throws IOException {
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(der));
    if (in.readUnsignedByte() != 48 || in.readUnsignedByte() != der.length - 2)
      throw new IOException();
    byte[] raw = new byte[64];
    for (int i = 0; i < 2; i++) {
      if (in.readUnsignedByte() != 2) throw new IOException();
      int n = in.readUnsignedByte();
      if (n < 1 || n > 33) throw new IOException();
      byte[] integer = new byte[n];
      in.readFully(integer);
      if (n == 33 && integer[0] != 0) throw new IOException();
      int len = Math.min(n, 32);
      System.arraycopy(integer, n - len, raw, i * 32 + 32 - len, len);
    }
    return raw;
  }

  public static JSONObject call(String endpoint, JSONObject payload) throws Exception {
    String path = "/api/theseven/" + endpoint;
    if (!endpoint.equals("seller") && !endpoint.equals("subscription"))
      throw new IllegalArgumentException();
    byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8), nonce = new byte[16];
    new SecureRandom().nextBytes(nonce);
    String timestamp = Long.toString(System.currentTimeMillis()), n = hex(nonce);
    String signed =
        String.join("\n", "BSHACK-DEVICE-V1", "apk", "POST", path, timestamp, n, hex(digest(body)));
    KeyStore ks = keys();
    Signature sig = Signature.getInstance("SHA256withECDSA");
    sig.initSign((PrivateKey) ks.getKey(ALIAS, null));
    sig.update(signed.getBytes(StandardCharsets.UTF_8));
    HttpsURLConnection c = (HttpsURLConnection) new URL(HOST + path).openConnection();
    try {
      c.setConnectTimeout(7000);
      c.setReadTimeout(8000);
      c.setInstanceFollowRedirects(false);
      c.setRequestMethod("POST");
      c.setDoOutput(true);
      c.setRequestProperty("Content-Type", "application/json");
      c.setRequestProperty("X-BSHACK-Platform", "apk");
      c.setRequestProperty("X-BSHACK-Time", timestamp);
      c.setRequestProperty("X-BSHACK-Nonce", n);
      c.setRequestProperty("X-BSHACK-Public-Key", pub(ks));
      c.setRequestProperty("X-BSHACK-Signature", b64(rawSignature(sig.sign())));
      try (OutputStream o = c.getOutputStream()) {
        o.write(body);
      }
      int code = c.getResponseCode();
      try (InputStream i = code >= 400 ? c.getErrorStream() : c.getInputStream()) {
        if (i == null) throw new IOException();
        JSONObject result = new JSONObject(new String(read(i, 512000), StandardCharsets.UTF_8));
        if (code >= 500 || code >= 300 && code < 400) throw new IOException();
        return result;
      }
    } finally {
      c.disconnect();
    }
  }

  public static JSONObject fields(Object... kv) {
    JSONObject o = new JSONObject();
    try {
      for (int i = 0; i < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
    } catch (JSONException e) {
      throw new IllegalArgumentException(e);
    }
    return o;
  }

  public static String date(long t) {
    return t <= 0 ? "—" : java.text.DateFormat.getDateTimeInstance().format(new Date(t));
  }

  public static String status(String s) {
    switch (s) {
      case "active":
        return "활성";
      case "pending":
        return "미사용";
      case "unregistered":
        return "미등록";
      case "invalid":
        return "잘못된 키";
      case "expired":
        return "만료";
      case "revoked":
        return "철회";
      case "unapproved":
        return "판매자 기기 등록 필요";
      case "bound":
        return "다른 기기에 등록된 키";
      case "device_conflict":
        return "이 기기에 다른 구독이 등록됨";
      case "device_error":
        return "기기 서명 또는 시각 확인 필요";
      case "rate_limited":
        return "요청이 많습니다. 잠시 후 다시 시도하세요";
      default:
        return "네트워크 또는 서버 응답 오류";
    }
  }
}
