package com.lancinema.app;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class CinemaApp extends Application implements Application.ActivityLifecycleCallbacks {
  final ExecutorService io = Executors.newFixedThreadPool(4);
  ShareStore shares;
  FileServer server;
  PeerDiscovery discovery;
  String deviceId;
  org.json.JSONArray playlist;
  private MediaRepository media;
  volatile boolean unlocked = false;
  private int visible = 0;
  private long backgroundAt = 0;

  @Override
  public void onCreate() {
    super.onCreate();
    deviceId = getSharedPreferences("device", 0).getString("id", null);
    if (deviceId == null) {
      deviceId = UUID.randomUUID().toString();
      getSharedPreferences("device", 0).edit().putString("id", deviceId).apply();
    }
    shares = new ShareStore(this);
    registerActivityLifecycleCallbacks(this);
  }

  synchronized MediaRepository media() {
    if (media == null) media = new MediaRepository(this);
    return media;
  }

  synchronized void startSharing() throws java.io.IOException {
    if (server != null) return;
    FileServer next = new FileServer(this);
    next.start(30000, false);
    server = next;
    discovery = new PeerDiscovery(this, deviceId);
    try {
      discovery.start(server.getListeningPort());
    } catch (Exception e) {
      discovery.error = "自动发现暂不可用，可手动输入地址";
    }
  }

  synchronized void stopSharing() {
    if (discovery != null) {
      discovery.stop();
      discovery = null;
    }
    if (server != null) {
      server.stop();
      server = null;
    }
  }

  boolean requireUnlock(Activity activity) {
    if (AppLock.enabled(this) && !unlocked) {
      activity.startActivity(new Intent(activity, LockActivity.class));
      return true;
    }
    return false;
  }

  public void onActivityStarted(Activity a) {
    if (visible++ == 0 && backgroundAt > 0 && SystemClock.elapsedRealtime() - backgroundAt > 30000)
      unlocked = false;
  }

  public void onActivityStopped(Activity a) {
    if (--visible == 0) backgroundAt = SystemClock.elapsedRealtime();
  }

  public void onActivityCreated(Activity a, Bundle b) {}

  public void onActivityResumed(Activity a) {}

  public void onActivityPaused(Activity a) {}

  public void onActivitySaveInstanceState(Activity a, Bundle b) {}

  public void onActivityDestroyed(Activity a) {}
}
