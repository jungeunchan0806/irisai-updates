package com.theseven.common;

import android.content.*;
import android.database.*;
import android.net.*;
import android.os.*;
import java.io.*;

public final class UpdateFile extends ContentProvider {
  static final String PATH = "/update.apk";

  public boolean onCreate() {
    return true;
  }

  public String getType(Uri u) {
    return "application/vnd.android.package-archive";
  }

  public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
    if (!PATH.equals(u.getPath()) || !"r".equals(mode)) throw new FileNotFoundException();
    return ParcelFileDescriptor.open(
        new File(getContext().getCacheDir(), "theseven-update.apk"),
        ParcelFileDescriptor.MODE_READ_ONLY);
  }

  public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
    if (!PATH.equals(u.getPath())) return null;
    String[] cols = p == null ? new String[] {"_display_name", "_size"} : p;
    MatrixCursor m = new MatrixCursor(cols);
    MatrixCursor.RowBuilder row = m.newRow();
    for (String c : cols)
      row.add(
          c.equals("_display_name")
              ? "theseven-update.apk"
              : c.equals("_size")
                  ? (Object) new File(getContext().getCacheDir(), "theseven-update.apk").length()
                  : null);
    return m;
  }

  public Uri insert(Uri u, ContentValues v) {
    throw new UnsupportedOperationException();
  }

  public int delete(Uri u, String s, String[] a) {
    throw new UnsupportedOperationException();
  }

  public int update(Uri u, ContentValues v, String s, String[] a) {
    throw new UnsupportedOperationException();
  }
}
