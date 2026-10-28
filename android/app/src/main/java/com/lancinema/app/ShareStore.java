package com.lancinema.app;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

final class ShareStore {
  final Context context;
  private List<JSONObject> cached;

  ShareStore(Context context) {
    this.context = context;
  }

  synchronized List<JSONObject> files() {
    return files(false);
  }

  synchronized List<JSONObject> files(boolean refresh) {
    if (!refresh && cached != null) return new ArrayList<>(cached);
    List<JSONObject> out = new ArrayList<>();
    try {
      JSONArray entries =
          new JSONArray(context.getSharedPreferences("shares", 0).getString("files", "[]"));
      for (int i = 0; i < entries.length(); i++) {
        JSONObject entry = entries.getJSONObject(i);
        try {
          out.add(metadata(Uri.parse(entry.getString("uri"))));
        } catch (Exception ignored) {
          /* Revoked or deleted: stop advertising. */
        }
      }
    } catch (Exception ignored) {
    }
    if (cached != null && !new JSONArray(cached).toString().equals(new JSONArray(out).toString()))
      context
          .getSharedPreferences("shares", 0)
          .edit()
          .putString("revision", UUID.randomUUID().toString())
          .apply();
    cached = out;
    return new ArrayList<>(out);
  }

  synchronized void add(Uri uri) throws Exception {
    add(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
  }

  synchronized void add(Uri uri, int flags) throws Exception {
    // Verify the provider is seekable: a Range request must never skip gigabytes.
    try (AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
      if (afd == null || afd.getParcelFileDescriptor().getStatSize() < 0)
        throw new IllegalArgumentException("请选择已下载到手机本地的文件，云端文件请先下载");
      try (java.io.FileInputStream stream = new java.io.FileInputStream(afd.getFileDescriptor())) {
        stream.getChannel().position(afd.getStartOffset());
      }
    }
    context
        .getContentResolver()
        .takePersistableUriPermission(
            uri,
            flags
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
    JSONObject file = metadata(uri);
    List<JSONObject> entries = files();
    entries.removeIf(f -> f.optString("id").equals(file.optString("id")));
    entries.add(file);
    save(entries);
  }

  synchronized void remove(String id) {
    List<JSONObject> entries = files();
    for (JSONObject file : entries)
      if (file.optString("id").equals(id))
        try {
          Uri uri = Uri.parse(file.getString("uri"));
          for (android.content.UriPermission grant :
              context.getContentResolver().getPersistedUriPermissions())
            if (grant.getUri().equals(uri))
              context
                  .getContentResolver()
                  .releasePersistableUriPermission(
                      uri,
                      (grant.isReadPermission() ? Intent.FLAG_GRANT_READ_URI_PERMISSION : 0)
                          | (grant.isWritePermission()
                              ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                              : 0));
        } catch (Exception ignored) {
        }
    entries.removeIf(f -> f.optString("id").equals(id));
    save(entries);
  }

  private void save(List<JSONObject> entries) {
    cached = new ArrayList<>(entries);
    context
        .getSharedPreferences("shares", 0)
        .edit()
        .putString("files", new JSONArray(entries).toString())
        .putString("revision", UUID.randomUUID().toString())
        .apply();
  }

  String revision() {
    return context.getSharedPreferences("shares", 0).getString("revision", "initial");
  }

  synchronized void delete(String id, long size, double modified) throws Exception {
    JSONObject selected = null;
    for (JSONObject file : files()) if (file.optString("id").equals(id)) selected = file;
    if (selected == null) throw new java.io.FileNotFoundException();
    Uri uri = Uri.parse(selected.getString("uri"));
    JSONObject current = metadata(uri);
    if (current.getLong("size") != size || current.getDouble("mtime") != modified)
      throw new IllegalStateException("源文件已修改，请刷新后确认删除");
    if (!current.optBoolean("canDelete"))
      throw new SecurityException("源 App 没有删除权限，请在源设备重新选择共享文件并授予写入权限");
    if (!DocumentsContract.deleteDocument(context.getContentResolver(), uri))
      throw new SecurityException("源设备的文件提供程序拒绝删除");
    remove(id);
  }

  JSONObject metadata(Uri uri) throws Exception {
    String name = "文件";
    long size = -1, modified = 0;
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                uri,
                new String[] {
                  OpenableColumns.DISPLAY_NAME,
                  OpenableColumns.SIZE,
                  DocumentsContract.Document.COLUMN_LAST_MODIFIED
                },
                null,
                null,
                null)) {
      if (cursor != null && cursor.moveToFirst()) {
        name = cursor.getString(0);
        size = cursor.isNull(1) ? -1 : cursor.getLong(1);
        modified = cursor.isNull(2) ? 0 : cursor.getLong(2);
      }
    }
    if (size < 0)
      try (AssetFileDescriptor afd =
          context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
        if (afd != null) size = afd.getLength();
      }
    if (size < 0) throw new IllegalArgumentException("文件大小未知，请先保存到手机本地");
    String mime = context.getContentResolver().getType(uri);
    boolean canDelete = false;
    for (android.content.UriPermission grant :
        context.getContentResolver().getPersistedUriPermissions())
      if (grant.getUri().equals(uri) && grant.isWritePermission()) {
        try (Cursor flags =
            context
                .getContentResolver()
                .query(
                    uri,
                    new String[] {DocumentsContract.Document.COLUMN_FLAGS},
                    null,
                    null,
                    null)) {
          canDelete =
              flags != null
                  && flags.moveToFirst()
                  && (flags.getInt(0) & DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0;
        } catch (Exception ignored) {
        }
      }
    String type =
        mime != null && mime.startsWith("video/")
            ? "video"
            : mime != null && mime.startsWith("image/") ? "image" : "other";
    if (name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(mp4|mkv|webm|mov|avi|m4v|ts|flv)$"))
      type = "video";
    return new JSONObject()
        .put(
            "id",
            UUID.nameUUIDFromBytes(uri.toString().getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "")
                .substring(0, 16))
        .put("name", name)
        .put("size", size)
        .put("mtime", modified / 1000.0)
        .put("type", type)
        .put("dir", "手机共享")
        .put("path", name)
        .put("canDelete", canDelete)
        .put("uri", uri.toString())
        .put("mime", mime == null ? "application/octet-stream" : mime);
  }
}
