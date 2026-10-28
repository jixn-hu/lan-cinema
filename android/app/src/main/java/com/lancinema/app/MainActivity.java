package com.lancinema.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.*;
import android.widget.Toast;
import org.json.JSONObject;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class MainActivity extends Activity {
  CinemaApp app;
  WebView web;
  String localOrigin, pendingExport;

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    app = (CinemaApp) getApplication();
    if (saved != null) pendingExport = saved.getString("export");
    try {
      app.startSharing();
      app.media();
    } catch (Exception e) {
      new AlertDialog.Builder(this)
          .setTitle("无法启动文件服务")
          .setMessage(e.toString())
          .setPositiveButton("关闭", (d, w) -> finish())
          .show();
      return;
    }
    startForegroundService(new Intent(this, ShareService.class));
    androidx.media3.exoplayer.offline.DownloadService.start(this, VideoDownloadService.class);
    localOrigin = "http://127.0.0.1:" + app.server.getListeningPort();
    web = new WebView(this);
    web.setBackgroundColor(android.graphics.Color.rgb(16, 23, 20));
    WebSettings settings = web.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setMediaPlaybackRequiresUserGesture(false);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    web.addJavascriptInterface(new Bridge(), "LanCinema");
    web.setWebViewClient(
        new WebViewClient() {
          @Override
          public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            return !r.getUrl().toString().startsWith(localOrigin + "/");
          }
        });
    setContentView(web);
    web.loadUrl(localOrigin + "/");
    if (android.os.Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 10);
  }

  @Override
  public void onResume() {
    super.onResume();
    if (web == null) return;
    if (app.requireUnlock(this)) {
      web.setVisibility(View.INVISIBLE);
    } else {
      web.setVisibility(View.VISIBLE);
      web.onResume();
      web.evaluateJavascript("window.dispatchEvent(new Event('lanresume'))", null);
    }
  }

  @Override
  public void onPause() {
    if (web != null) {
      web.onPause();
      if (AppLock.enabled(this)) web.setVisibility(View.INVISIBLE);
    }
    super.onPause();
  }

  void changed() {
    if (web != null)
      web.post(
          () -> web.evaluateJavascript("window.dispatchEvent(new Event('lanfileschanged'))", null));
  }

  void toast(String text) {
    runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
  }

  boolean authorized() {
    return !AppLock.enabled(this) || app.unlocked;
  }

  public final class Bridge {
    @JavascriptInterface
    public void pickFiles() {
      if (!authorized()) return;
      runOnUiThread(
          () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, 11);
          });
    }

    @JavascriptInterface
    public void unshare(String id) {
      if (!authorized()) return;
      app.io.execute(
          () -> {
            app.shares.remove(id);
            changed();
          });
    }

    @JavascriptInterface
    public void play(String json) {
      if (!authorized()) return;
      try {
        JSONObject data = new JSONObject(json);
        validateURL(data.getString("url"));
        org.json.JSONArray queue = data.optJSONArray("playlist");
        if (queue != null)
          for (int i = 0; i < queue.length(); i++)
            validateURL(queue.getJSONObject(i).getString("url"));
        app.playlist = queue;
        data.remove("playlist");
        String video = data.toString();
        runOnUiThread(
            () ->
                startActivity(
                    new Intent(MainActivity.this, PlayerActivity.class).putExtra("video", video)));
      } catch (Exception e) {
        toast("视频地址无效");
      }
    }

    @JavascriptInterface
    public void download(String json) {
      if (!authorized()) return;
      try {
        JSONObject data = new JSONObject(json);
        validateURL(data.getString("url"));
        app.io.execute(
            () -> {
              try {
                app.media().download(data);
              } catch (Exception e) {
                toast("无法开始下载：" + e.getMessage());
              }
            });
      } catch (Exception e) {
        toast("下载地址无效");
      }
    }

    @JavascriptInterface
    public String downloads() {
      return authorized() ? app.media().list().toString() : "[]";
    }

    @JavascriptInterface
    public String favorites() {
      return authorized() ? app.favorites.all().toString() : "[]";
    }

    @JavascriptInterface
    public String toggleFavorite(String json) {
      if (!authorized()) return "[]";
      try {
        return app.favorites.toggle(new JSONObject(json)).toString();
      } catch (Exception e) {
        return app.favorites.all().toString();
      }
    }

    @JavascriptInterface
    public String cacheInfo() {
      return authorized() ? app.media().info().toString() : "{}";
    }

    @JavascriptInterface
    public void clearCache() {
      if (authorized()) app.media().clear();
    }

    @JavascriptInterface
    public void setCacheLimit(int gb) {
      if (authorized()) app.media().setLimit(gb);
    }

    @JavascriptInterface
    public void downloadAction(String id, String action) {
      if (authorized()) app.media().action(id, action);
    }

    @JavascriptInterface
    public void manageLock() {
      if (authorized())
        runOnUiThread(
            () ->
                startActivity(
                    new Intent(MainActivity.this, LockActivity.class).putExtra("manage", true)));
    }

    @JavascriptInterface
    public void exportDownload(String id) {
      if (!authorized()) return;
      try {
        androidx.media3.exoplayer.offline.Download d =
            app.media().downloads.getDownloadIndex().getDownload(id);
        JSONObject info =
            d == null
                ? app.media().tracked.get(id)
                : new JSONObject(
                    new String(d.request.data, java.nio.charset.StandardCharsets.UTF_8));
        if (info == null || !app.media().complete(id, app.media().length(id, info))) return;
        String name = info.optString("name", "video.mp4");
        runOnUiThread(
            () -> {
              pendingExport = id;
              Intent export = new Intent(Intent.ACTION_CREATE_DOCUMENT);
              export.setType("application/octet-stream");
              export.addCategory(Intent.CATEGORY_OPENABLE);
              export.putExtra(Intent.EXTRA_TITLE, name);
              startActivityForResult(export, 12);
            });
      } catch (Exception e) {
        toast("无法导出该文件");
      }
    }
  }

  static void validateURL(String raw) {
    Uri uri = Uri.parse(raw);
    if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
        || uri.getHost() == null) throw new IllegalArgumentException("Invalid URL");
  }

  @Override
  public void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == 12) {
      if (result == RESULT_OK && data != null && data.getData() != null && pendingExport != null)
        app.media().export(pendingExport, data.getData());
      pendingExport = null;
      return;
    }
    if (request != 11 || result != RESULT_OK || data == null) return;
    java.util.List<Uri> uris = new java.util.ArrayList<>();
    if (data.getClipData() != null)
      for (int i = 0; i < data.getClipData().getItemCount(); i++)
        uris.add(data.getClipData().getItemAt(i).getUri());
    else if (data.getData() != null) uris.add(data.getData());
    app.io.execute(
        () -> {
          int count = 0;
          for (Uri uri : uris)
            try {
              app.shares.add(uri, data.getFlags());
              count++;
            } catch (Exception e) {
              toast("无法共享：" + e.getMessage());
            }
          toast("已共享 " + count + " 个文件");
          changed();
        });
  }

  @Override
  public void onBackPressed() {
    if (web != null) {
      web.evaluateJavascript(
          "window.lanBack ? window.lanBack() : false",
          handled -> {
            if (!"true".equals(handled)) moveTaskToBack(true);
          });
    } else super.onBackPressed();
  }

  @Override
  public void onSaveInstanceState(Bundle out) {
    out.putString("export", pendingExport);
    super.onSaveInstanceState(out);
  }

  @Override
  public void onDestroy() {
    if (web != null) {
      web.removeJavascriptInterface("LanCinema");
      web.destroy();
    }
    super.onDestroy();
  }
}
