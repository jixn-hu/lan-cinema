package com.lancinema.app;

import static org.junit.Assert.*;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ShareDeleteTest {
  static final Uri URI = Uri.parse("content://test.documents/document/video");
  CinemaApp app;
  Provider provider;
  ShareStore shares;

  public static class Provider extends ContentProvider {
    File file;
    boolean reject;

    @Override
    public boolean onCreate() {
      return true;
    }

    @Override
    public String getType(Uri uri) {
      return "video/mp4";
    }

    @Override
    public Cursor query(
        Uri uri, String[] projection, String selection, String[] args, String order) {
      if (!file.exists()) throw new IllegalStateException("deleted");
      MatrixCursor cursor = new MatrixCursor(projection);
      Object[] row = new Object[projection.length];
      for (int i = 0; i < projection.length; i++) {
        if (projection[i].equals(OpenableColumns.DISPLAY_NAME)) row[i] = "video.mp4";
        else if (projection[i].equals(OpenableColumns.SIZE)) row[i] = file.length();
        else if (projection[i].equals(DocumentsContract.Document.COLUMN_FLAGS))
          row[i] = DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
        else row[i] = 1000L;
      }
      cursor.addRow(row);
      return cursor;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
      if (reject) throw new SecurityException("provider denied deletion");
      if (!"android:deleteDocument".equals(method)) throw new IllegalArgumentException(method);
      assertEquals(URI, extras.getParcelable("uri"));
      assertTrue(file.delete());
      return new Bundle();
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
      throw new UnsupportedOperationException();
    }
  }

  @Before
  public void setup() throws Exception {
    app = (CinemaApp) RuntimeEnvironment.getApplication();
    provider = new Provider();
    ProviderInfo info = new ProviderInfo();
    info.authority = URI.getAuthority();
    provider.attachInfo(app, info);
    provider.file = File.createTempFile("delete-test", ".mp4", app.getCacheDir());
    java.nio.file.Files.write(provider.file.toPath(), new byte[100]);
    ShadowContentResolver.registerProviderInternal(URI.getAuthority(), provider);
    app.getSharedPreferences("shares", 0)
        .edit()
        .putString(
            "files", new JSONArray().put(new JSONObject().put("uri", URI.toString())).toString())
        .commit();
    shares = app.shares;
  }

  @Test
  public void writeAuthorizedDeleteRemovesProviderFileAndChangesRevision() throws Exception {
    app.getContentResolver()
        .takePersistableUriPermission(
            URI, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    JSONObject file = shares.files().get(0);
    String before = shares.revision();
    shares.delete(file.getString("id"), 100, 1);
    assertFalse(provider.file.exists());
    assertTrue(shares.files().isEmpty());
    assertNotEquals(before, shares.revision());
  }

  @Test
  public void readOnlyGrantDoesNotDeleteOrUnshare() throws Exception {
    app.getContentResolver()
        .takePersistableUriPermission(URI, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    JSONObject file = shares.files().get(0);
    assertThrows(SecurityException.class, () -> shares.delete(file.getString("id"), 100, 1));
    assertTrue(provider.file.exists());
    assertEquals(1, shares.files().size());
  }

  @Test
  public void providerRejectionDoesNotPretendDeletionSucceeded() throws Exception {
    app.getContentResolver()
        .takePersistableUriPermission(
            URI, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    JSONObject file = shares.files().get(0);
    String before = shares.revision();
    provider.reject = true;
    assertThrows(SecurityException.class, () -> shares.delete(file.getString("id"), 100, 1));
    assertTrue(provider.file.exists());
    assertEquals(1, shares.files().size());
    assertEquals(before, shares.revision());
  }
}
