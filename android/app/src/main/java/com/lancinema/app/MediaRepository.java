package com.lancinema.app;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.media3.common.MediaItem;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSink;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadCursor;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadNotificationHelper;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.offline.DownloadService;
import androidx.media3.exoplayer.scheduler.Requirements;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class MediaRepository {
  static final String CHANNEL = "video-downloads";
  final CinemaApp app;
  final Handler main = new Handler(Looper.getMainLooper());
  final Set<String> pinned = ConcurrentHashMap.newKeySet();
  final SimpleCache cache;
  final ProtectedCacheEvictor evictor;
  final DefaultHttpDataSource.Factory http =
      new DefaultHttpDataSource.Factory()
          .setUserAgent("LanCinema/1.0")
          .setConnectTimeoutMs(10000)
          .setReadTimeoutMs(20000)
          .setAllowCrossProtocolRedirects(false);
  final CacheDataSource.Factory sources;
  final DownloadManager downloads;
  final DownloadNotificationHelper notifications;
  final File directory;
  final Map<String, Download> liveDownloads = new ConcurrentHashMap<>();
  final Map<String, Long> downloadSpeeds = new ConcurrentHashMap<>();
  final Map<String, DownloadSpeed> speedMeters = new HashMap<>();
  volatile boolean waitingForNetwork;
  final Runnable speedTick =
      new Runnable() {
        @Override
        public void run() {
          sampleDownloads();
        }
      };

  MediaRepository(CinemaApp app) {
    this.app = app;
    pinned.addAll(
        app.getSharedPreferences("media", 0)
            .getStringSet("pinned", java.util.Collections.emptySet()));
    File base = app.getExternalFilesDir(null);
    if (base == null) base = app.getFilesDir();
    directory = new File(base, "video-cache");
    directory.mkdirs();
    evictor = new ProtectedCacheEvictor(limitGb() * 1024L * 1024 * 1024, pinned, directory);
    StandaloneDatabaseProvider database = new StandaloneDatabaseProvider(app);
    cache = new SimpleCache(directory, evictor, database);
    // Playback does not block on a span locked by a background download. Cache misses
    // stream immediately while both operations still share all completed spans.
    sources =
        new CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(http)
            .setCacheWriteDataSinkFactory(
                new CacheDataSink.Factory().setCache(cache).setFragmentSize(2L * 1024 * 1024))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    downloads = new DownloadManager(app, database, cache, http, Executors.newFixedThreadPool(3));
    downloads.setMaxParallelDownloads(2);
    downloads.setMinRetryCount(5);
    downloads.setRequirements(new Requirements(Requirements.NETWORK));
    downloads.addListener(
        new DownloadManager.Listener() {
          @Override
          public void onInitialized(DownloadManager manager) {
            monitorSpeed();
          }

          @Override
          public void onDownloadChanged(
              DownloadManager manager, Download download, Exception error) {
            monitorSpeed();
          }

          @Override
          public void onRequirementsStateChanged(
              DownloadManager manager, Requirements requirements, int notMet) {
            waitingForNetwork = notMet != 0;
            monitorSpeed();
          }

          @Override
          public void onDownloadRemoved(DownloadManager manager, Download download) {
            liveDownloads.remove(download.request.id);
            downloadSpeeds.remove(download.request.id);
            speedMeters.remove(download.request.id);
            pinned.remove(download.request.id);
            app.getSharedPreferences("media", 0)
                .edit()
                .putStringSet("pinned", new HashSet<>(pinned))
                .apply();
          }
        });
    downloads.resumeDownloads();
    notifications = new DownloadNotificationHelper(app, CHANNEL);
  }

  private void monitorSpeed() {
    main.removeCallbacks(speedTick);
    main.post(speedTick);
  }

  private void sampleDownloads() {
    boolean running = false;
    Set<String> ids = new HashSet<>();
    long now = SystemClock.elapsedRealtime();
    for (Download download : downloads.getCurrentDownloads()) {
      String id = download.request.id;
      ids.add(id);
      liveDownloads.put(id, download);
      boolean active = download.state == Download.STATE_DOWNLOADING && !waitingForNetwork;
      running |= active;
      downloadSpeeds.put(
          id,
          speedMeters
              .computeIfAbsent(id, key -> new DownloadSpeed())
              .update(download.getBytesDownloaded(), now, active));
    }
    liveDownloads.keySet().retainAll(ids);
    downloadSpeeds.keySet().retainAll(ids);
    speedMeters.keySet().retainAll(ids);
    if (running) main.postDelayed(speedTick, 1000);
  }

  static String key(String url) {
    try {
      Uri uri = Uri.parse(url);
      Uri.Builder canonical = uri.buildUpon().clearQuery();
      for (String name : uri.getQueryParameterNames())
        if (!name.equals("t"))
          for (String value : uri.getQueryParameters(name))
            canonical.appendQueryParameter(name, value);
      byte[] hash =
          MessageDigest.getInstance("SHA-256")
              .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder();
      for (byte b : hash) out.append(String.format("%02x", b));
      return out.toString();
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid video URL", e);
    }
  }

  MediaItem item(String url) {
    return new MediaItem.Builder().setUri(url).setCustomCacheKey(key(url)).build();
  }

  int limitGb() {
    return app.getSharedPreferences("media", 0).getInt("limitGb", 20);
  }

  void setLimit(int gb) {
    if (gb < 1 || gb > 1024) return;
    app.getSharedPreferences("media", 0).edit().putInt("limitGb", gb).apply();
    evictor.maxBytes = gb * 1024L * 1024 * 1024;
    app.io.execute(
        () -> {
          synchronized (cache) {
            evictor.evict(cache, 0);
          }
        });
  }

  void clear() {
    app.io.execute(
        () -> {
          synchronized (cache) {
            evictor.clear(cache);
          }
        });
  }

  void download(JSONObject data) {
    String url = data.optString("url");
    String id = key(url);
    pinned.add(id);
    app.getSharedPreferences("media", 0)
        .edit()
        .putStringSet("pinned", new HashSet<>(pinned))
        .apply();
    synchronized (cache) {
      evictor.pin(cache, id);
    }
    DownloadRequest request =
        new DownloadRequest.Builder(id, Uri.parse(url))
            .setCustomCacheKey(id)
            .setData(data.toString().getBytes(StandardCharsets.UTF_8))
            .build();
    main.post(
        () -> DownloadService.sendAddDownload(app, VideoDownloadService.class, request, false));
  }

  void action(String id, String action) {
    main.post(
        () -> {
          if (action.equals("remove"))
            DownloadService.sendRemoveDownload(app, VideoDownloadService.class, id, false);
          else if (action.equals("pause"))
            DownloadService.sendSetStopReason(app, VideoDownloadService.class, id, 1, false);
          else if (action.equals("resume"))
            try {
              Download d = downloads.getDownloadIndex().getDownload(id);
              if (d != null)
                DownloadService.sendAddDownload(app, VideoDownloadService.class, d.request, false);
            } catch (Exception ignored) {
            }
        });
  }

  JSONArray list() {
    JSONArray out = new JSONArray();
    try (DownloadCursor cursor = downloads.getDownloadIndex().getDownloads()) {
      while (cursor.moveToNext()) {
        Download stored = cursor.getDownload();
        Download d = liveDownloads.getOrDefault(stored.request.id, stored);
        JSONObject info = new JSONObject(new String(d.request.data, StandardCharsets.UTF_8));
        String[] states = {"等待下载", "暂停", "下载中", "已完成", "下载失败，请检查网络或可用空间", "移除中", "重新下载"};
        out.put(
            new JSONObject()
                .put("id", d.request.id)
                .put("url", d.request.uri.toString())
                .put("name", info.optString("name", "视频"))
                .put("type", info.optString("type", "video"))
                .put("bytes", d.getBytesDownloaded())
                .put("total", d.contentLength)
                .put(
                    "speed",
                    d.state == Download.STATE_DOWNLOADING
                        ? downloadSpeeds.getOrDefault(d.request.id, 0L)
                        : 0L)
                .put("downloading", d.state == Download.STATE_DOWNLOADING)
                .put("complete", d.state == Download.STATE_COMPLETED)
                .put(
                    "paused", d.state == Download.STATE_STOPPED || d.state == Download.STATE_FAILED)
                .put(
                    "status",
                    waitingForNetwork && d.state == Download.STATE_QUEUED
                        ? "等待网络连接"
                        : d.state >= 0 && d.state < states.length ? states[d.state] : "等待中"));
      }
    } catch (Exception ignored) {
    }
    return out;
  }

  JSONObject info() {
    try {
      return new JSONObject()
          .put("bytes", evictor.streamingBytes)
          .put("totalBytes", cache.getCacheSpace())
          .put("limitGb", limitGb())
          .put("free", directory.getUsableSpace())
          .put("lockEnabled", AppLock.enabled(app));
    } catch (Exception e) {
      return new JSONObject();
    }
  }

  void export(String id, Uri destination) {
    app.io.execute(
        () -> {
          try {
            Download d = downloads.getDownloadIndex().getDownload(id);
            if (d == null || d.state != Download.STATE_COMPLETED)
              throw new java.io.IOException("文件尚未完整下载");
            CacheDataSource source =
                new CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(null)
                    .setCacheWriteDataSinkFactory(null)
                    .createDataSource();
            androidx.media3.datasource.DataSpec spec =
                new androidx.media3.datasource.DataSpec.Builder()
                    .setUri(d.request.uri)
                    .setKey(d.request.customCacheKey)
                    .build();
            try (java.io.InputStream input =
                    new androidx.media3.datasource.DataSourceInputStream(source, spec);
                java.io.OutputStream output =
                    app.getContentResolver().openOutputStream(destination, "wt")) {
              if (output == null) throw new java.io.IOException("无法写入所选位置");
              byte[] buffer = new byte[256 * 1024];
              int count;
              while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            main.post(
                () ->
                    android.widget.Toast.makeText(
                            app, "文件已导出，可用其他播放器打开或重新共享", android.widget.Toast.LENGTH_LONG)
                        .show());
          } catch (Exception e) {
            main.post(
                () ->
                    android.widget.Toast.makeText(
                            app, "导出失败：" + e.getMessage(), android.widget.Toast.LENGTH_LONG)
                        .show());
          }
        });
  }

  PendingIntent contentIntent() {
    return PendingIntent.getActivity(
        app,
        0,
        new Intent(app, MainActivity.class),
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }
}
