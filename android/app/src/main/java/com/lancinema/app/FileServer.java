package com.lancinema.app;

import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.net.URLEncoder;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

final class FileServer extends NanoHTTPD {
  final CinemaApp app;

  FileServer(CinemaApp app) {
    super(8765);
    this.app = app;
  }

  @Override
  public Response serve(IHTTPSession session) {
    Response response;
    try {
      response = route(session);
    } catch (FileNotFoundException | SecurityException e) {
      response = json(Response.Status.NOT_FOUND, "文件已移除或授权失效");
    } catch (Exception e) {
      response = json(Response.Status.INTERNAL_ERROR, "请求失败，请刷新后重试");
    }
    response.addHeader("Access-Control-Allow-Origin", "*");
    response.addHeader("Access-Control-Allow-Methods", "GET, HEAD, PUT, OPTIONS");
    response.addHeader("Access-Control-Allow-Headers", "Range, If-Range, Content-Type");
    response.addHeader(
        "Access-Control-Expose-Headers", "Content-Range, Content-Length, Accept-Ranges, ETag");
    response.addHeader("X-Content-Type-Options", "nosniff");
    return response;
  }

  Response route(IHTTPSession s) throws Exception {
    String path = s.getUri();
    if (s.getMethod() == Method.OPTIONS)
      return newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "");
    if (path.equals("/api/device"))
      return ok(
          new JSONObject()
              .put("id", app.deviceId)
              .put("name", android.os.Build.MODEL)
              .put("platform", "android")
              .put("version", 1)
              .put(
                  "features",
                  new JSONArray(Arrays.asList("range", "progress", "download", "discovery"))));
    if (path.equals("/api/devices"))
      return ok(
          new JSONObject()
              .put("devices", app.discovery == null ? new JSONArray() : app.discovery.devices())
              .put("error", app.discovery == null ? "" : app.discovery.error));
    if (path.equals("/api/access"))
      return ok(
          new JSONObject()
              .put("local", "http://127.0.0.1:" + getListeningPort())
              .put("lan", PeerDiscovery.addresses(getListeningPort())));
    if (path.equals("/api/dirs")) return ok(new JSONObject().put("dirs", new JSONArray()));
    if (path.equals("/api/files")) {
      List<JSONObject> files = app.shares.files();
      Map<String, String> q = s.getParms();
      String type = q.getOrDefault("type", "all"),
          search = q.getOrDefault("search", "").toLowerCase(Locale.ROOT);
      files.removeIf(
          f ->
              (!type.equals("all") && !f.optString("type").equals(type))
                  || (type.equals("all") && f.optString("type").equals("other"))
                  || !f.optString("name").toLowerCase(Locale.ROOT).contains(search));
      String sort = q.getOrDefault("sort", "name");
      Comparator<JSONObject> cmp =
          sort.equals("size")
              ? Comparator.comparingLong(f -> f.optLong("size"))
              : sort.equals("mtime")
                  ? Comparator.comparingDouble(f -> f.optDouble("mtime"))
                  : Comparator.comparing(f -> f.optString("name").toLowerCase(Locale.ROOT));
      files.sort(q.getOrDefault("order", "asc").equals("desc") ? cmp.reversed() : cmp);
      JSONArray out = new JSONArray();
      for (JSONObject f : files) {
        JSONObject safe = new JSONObject(f.toString());
        safe.remove("uri");
        safe.remove("mime");
        out.put(safe);
      }
      return ok(out);
    }
    if (path.equals("/api/progress"))
      return ok(new JSONObject(app.getSharedPreferences("progress", 0).getAll()));
    if (path.startsWith("/api/progress/")) {
      String id = path.substring("/api/progress/".length());
      if (s.getMethod() == Method.PUT) {
        Map<String, String> body = new HashMap<>();
        s.parseBody(body);
        double pos = new JSONObject(body.getOrDefault("postData", "{}")).optDouble("position", -1);
        if (!Double.isFinite(pos) || pos < 0) return json(Response.Status.BAD_REQUEST, "无效的播放位置");
        app.getSharedPreferences("progress", 0).edit().putFloat(id, (float) pos).apply();
        return ok(new JSONObject().put("ok", true));
      }
      return ok(
          new JSONObject()
              .put("position", app.getSharedPreferences("progress", 0).getFloat(id, 0)));
    }
    if (path.startsWith("/api/file/")) {
      String[] parts = path.split("/");
      if (parts.length != 5) return json(Response.Status.NOT_FOUND, "文件不存在");
      JSONObject target = null;
      for (JSONObject f : app.shares.files())
        if (f.optString("id").equals(parts[3])) {
          target = f;
          break;
        }
      if (target == null) return json(Response.Status.NOT_FOUND, "文件不存在或已停止共享");
      if (parts[4].equals("cover") && target.optString("type").equals("video"))
        return cover(target);
      if (!parts[4].equals("stream") && !parts[4].equals("cover"))
        return json(Response.Status.NOT_FOUND, "文件不存在");
      return stream(s, target);
    }
    if (path.startsWith("/api/")) return json(Response.Status.NOT_FOUND, "接口不存在");
    if (s.getMethod() != Method.GET && s.getMethod() != Method.HEAD)
      return json(Response.Status.METHOD_NOT_ALLOWED, "请求方式不支持");
    String asset = path.equals("/") ? "index.html" : path.substring(1);
    if (!Arrays.asList("index.html", "app.js", "style.css", "vue.global.prod.js").contains(asset))
      return json(Response.Status.NOT_FOUND, "页面不存在");
    String mime =
        asset.endsWith(".js")
            ? "application/javascript"
            : asset.endsWith(".css") ? "text/css" : "text/html; charset=utf-8";
    Response r = newChunkedResponse(Response.Status.OK, mime, app.getAssets().open("web/" + asset));
    r.addHeader(
        "Content-Security-Policy",
        "default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'self' 'unsafe-inline';"
            + " connect-src http: https:; img-src http: https: data:; media-src http: https: blob:;"
            + " frame-src 'none'; object-src 'none'; base-uri 'self'");
    return r;
  }

  Response stream(IHTTPSession s, JSONObject file) throws Exception {
    long size = file.getLong("size"), start = 0, end = size - 1;
    String revision = s.getParms().get("rev");
    if (revision != null)
      try {
        String[] bits = revision.split("-", 2);
        if (bits.length != 2
            || Long.parseLong(bits[0]) != size
            || Double.parseDouble(bits[1]) != file.optDouble("mtime"))
          return json(Response.Status.CONFLICT, "源文件已修改，请刷新后重新下载");
      } catch (NumberFormatException e) {
        return json(Response.Status.CONFLICT, "无效的文件版本，请刷新列表");
      }
    String etag = "\"" + file.getString("id") + "-" + size + "-" + file.optDouble("mtime") + "\"";
    String range = s.getHeaders().get("range"), ifRange = s.getHeaders().get("if-range");
    if (ifRange != null && !ifRange.equals(etag)) range = null;
    if (range != null)
      try {
        ByteRange r = ByteRange.parse(range, size);
        start = r.start;
        end = r.end;
      } catch (IllegalArgumentException e) {
        Response r = json(Response.Status.RANGE_NOT_SATISFIABLE, "无效的分段范围");
        r.addHeader("Content-Range", "bytes */" + size);
        return r;
      }
    InputStream data = new ByteArrayInputStream(new byte[0]);
    if (s.getMethod() != Method.HEAD && size > 0) {
      AssetFileDescriptor afd =
          app.getContentResolver().openAssetFileDescriptor(Uri.parse(file.getString("uri")), "r");
      if (afd == null) throw new FileNotFoundException();
      FileInputStream input = afd.createInputStream();
      try {
        input.getChannel().position(afd.getStartOffset() + start);
      } catch (Exception e) {
        input.close();
        throw e;
      }
      android.os.PowerManager power =
          (android.os.PowerManager) app.getSystemService(android.content.Context.POWER_SERVICE);
      android.os.PowerManager.WakeLock wake =
          power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "LanCinema:file-transfer");
      wake.setReferenceCounted(false);
      wake.acquire(60L * 60 * 1000);
      data =
          new FilterInputStream(input) {
            @Override
            public void close() throws IOException {
              try {
                super.close();
              } finally {
                if (wake.isHeld()) wake.release();
              }
            }
          };
    }
    Response r =
        newFixedLengthResponse(
            range == null ? Response.Status.OK : Response.Status.PARTIAL_CONTENT,
            file.optString("mime", "application/octet-stream"),
            data,
            size == 0 ? 0 : end - start + 1);
    r.addHeader("Accept-Ranges", "bytes");
    r.addHeader("ETag", etag);
    r.addHeader("Cache-Control", "private, max-age=0, must-revalidate");
    if (range != null) r.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
    if ("1".equals(s.getParms().get("download")))
      r.addHeader(
          "Content-Disposition",
          "attachment; filename*=UTF-8''"
              + URLEncoder.encode(file.getString("name"), "UTF-8").replace("+", "%20"));
    return r;
  }

  final Object coverLock = new Object();

  Response cover(JSONObject file) throws Exception {
    File dir = new File(app.getCacheDir(), "covers");
    dir.mkdirs();
    File output = new File(dir, file.getString("id") + "-" + file.optDouble("mtime") + ".jpg");
    synchronized (coverLock) {
      if (!output.isFile()) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
          retriever.setDataSource(app, Uri.parse(file.getString("uri")));
          Bitmap bitmap =
              android.os.Build.VERSION.SDK_INT >= 27
                  ? retriever.getScaledFrameAtTime(
                      1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 360)
                  : retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
          if (bitmap == null) return json(Response.Status.NOT_FOUND, "没有视频封面");
          if (bitmap.getWidth() > 640 || bitmap.getHeight() > 360) {
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 640, 360, true);
            if (scaled != bitmap) bitmap.recycle();
            bitmap = scaled;
          }
          try (FileOutputStream out = new FileOutputStream(output)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out);
          } finally {
            bitmap.recycle();
          }
        } finally {
          retriever.release();
        }
      }
    }
    Response r =
        newFixedLengthResponse(
            Response.Status.OK, "image/jpeg", new FileInputStream(output), output.length());
    r.addHeader("Cache-Control", "public, max-age=86400");
    return r;
  }

  Response ok(Object body) {
    return newFixedLengthResponse(
        Response.Status.OK, "application/json; charset=utf-8", body.toString());
  }

  Response json(Response.Status status, String detail) {
    try {
      return newFixedLengthResponse(
          status,
          "application/json; charset=utf-8",
          new JSONObject().put("detail", detail).toString());
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
