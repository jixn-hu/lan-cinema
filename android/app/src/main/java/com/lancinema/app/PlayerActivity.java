package com.lancinema.app;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.*;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlayerActivity extends Activity {
  CinemaApp app;
  ExoPlayer player;
  PlayerView view;
  JSONObject info;
  TextView status;
  float rate = 1, zoom = 1;
  boolean boosting = false, ended = false;
  Handler handler = new Handler(Looper.getMainLooper());
  final Runnable saveTick =
      new Runnable() {
        public void run() {
          save();
          handler.postDelayed(this, 5000);
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    app = (CinemaApp) getApplication();
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    try {
      info = new JSONObject(getIntent().getStringExtra("video"));
    } catch (Exception e) {
      finish();
      return;
    }
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(Color.BLACK);
    LinearLayout header = new LinearLayout(this);
    header.setGravity(Gravity.CENTER_VERTICAL);
    Button back = button("‹ 返回", v -> finish());
    header.addView(back);
    TextView title = new TextView(this);
    title.setText(info.optString("name", "视频"));
    title.setTextColor(Color.WHITE);
    title.setSingleLine();
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    root.addView(header);
    view = (PlayerView) getLayoutInflater().inflate(R.layout.player, root, false);
    root.addView(view, new LinearLayout.LayoutParams(-1, 0, 1));
    HorizontalScrollView scroll = new HorizontalScrollView(this);
    LinearLayout controls = new LinearLayout(this);
    controls.setGravity(Gravity.CENTER_VERTICAL);
    scroll.addView(controls);
    root.addView(scroll);
    controls.addView(button("−10s", v -> seek(-10000)));
    controls.addView(button("+10s", v -> seek(10000)));
    Button speed = button("1×", v -> {});
    speed.setOnClickListener(
        v ->
            new android.app.AlertDialog.Builder(this)
                .setTitle("播放速度")
                .setItems(
                    new String[] {"0.5×", "0.75×", "1×", "1.25×", "1.5×", "2×", "3×"},
                    (d, n) -> {
                      rate = new float[] {.5f, .75f, 1, 1.25f, 1.5f, 2, 3}[n];
                      if (player != null) player.setPlaybackSpeed(rate);
                      speed.setText(rate + "×");
                    })
                .show());
    controls.addView(speed);
    controls.addView(button("缩小", v -> setZoom(zoom - .25f)));
    controls.addView(button("放大", v -> setZoom(zoom + .25f)));
    controls.addView(
        button(
            "适应 / 填满",
            v ->
                view.setResizeMode(
                    view.getResizeMode() == AspectRatioFrameLayout.RESIZE_MODE_FIT
                        ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        : AspectRatioFrameLayout.RESIZE_MODE_FIT)));
    controls.addView(
        button(
            "横竖屏",
            v ->
                setRequestedOrientation(
                    getResources().getConfiguration().orientation
                            == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)));
    status = new TextView(this);
    status.setText("长按 2× 加速 · 双指缩放 · 双击左右快进退 · 自动缓存");
    status.setTextColor(Color.rgb(201, 245, 145));
    status.setTextSize(11);
    status.setPadding(15, 9, 15, 9);
    root.addView(status);
    setContentView(root);
    GestureDetector gesture =
        new GestureDetector(
            this,
            new GestureDetector.SimpleOnGestureListener() {
              @Override
              public boolean onDown(MotionEvent e) {
                return true;
              }

              @Override
              public boolean onSingleTapConfirmed(MotionEvent e) {
                if (view.isControllerFullyVisible()) view.hideController();
                else view.showController();
                return true;
              }

              @Override
              public boolean onDoubleTap(MotionEvent e) {
                seek(e.getX() < view.getWidth() / 2 ? -10000 : 10000);
                return true;
              }

              @Override
              public void onLongPress(MotionEvent e) {
                if (player != null) {
                  boosting = true;
                  player.setPlaybackSpeed(Math.max(2, rate));
                  status.setText("▶▶ " + Math.max(2, rate) + "× 加速中，松开恢复");
                }
              }
            });
    ScaleGestureDetector scale =
        new ScaleGestureDetector(
            this,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
              @Override
              public boolean onScale(ScaleGestureDetector detector) {
                setZoom(zoom * detector.getScaleFactor());
                return true;
              }
            });
    view.getVideoSurfaceView()
        .setOnTouchListener(
            (v, event) -> {
              scale.onTouchEvent(event);
              if (event.getPointerCount() == 1 && !scale.isInProgress())
                gesture.onTouchEvent(event);
              if (event.getActionMasked() == MotionEvent.ACTION_UP
                  || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                if (boosting && player != null) {
                  player.setPlaybackSpeed(rate);
                  boosting = false;
                  status.setText("自动缓存 · 缩放 " + Math.round(zoom * 100) + "%");
                }
              }
              return true;
            });
  }

  Button button(String name, View.OnClickListener click) {
    Button b = new Button(this);
    b.setText(name);
    b.setTextSize(12);
    b.setTextColor(Color.WHITE);
    b.setOnClickListener(click);
    return b;
  }

  @Override
  public void onResume() {
    super.onResume();
    if (info == null) return;
    if (app.requireUnlock(this)) return;
    if (player == null) {
      MediaRepository media = app.media();
      CacheDataSource.Factory factory = media.sources;
      if (info.optBoolean("offline"))
        factory =
            new CacheDataSource.Factory()
                .setCache(media.cache)
                .setUpstreamDataSourceFactory(null)
                .setCacheWriteDataSinkFactory(null);
      player =
          new ExoPlayer.Builder(this)
              .setLoadControl(
                  new DefaultLoadControl.Builder()
                      .setBufferDurationsMs(15000, 60000, 1200, 2500)
                      .setTargetBufferBytes(64 * 1024 * 1024)
                      .build())
              .setMediaSourceFactory(new DefaultMediaSourceFactory(factory))
              .build();
      player.setWakeMode(C.WAKE_MODE_NETWORK);
      player.setAudioAttributes(AudioAttributes.DEFAULT, true);
      player.setHandleAudioBecomingNoisy(true);
      view.setPlayer(player);
      player.setMediaItem(media.item(info.optString("url")));
      long saved =
          getSharedPreferences("local-progress", 0)
              .getLong(MediaRepository.key(info.optString("url")), 0);
      player.seekTo(Math.max(saved, (long) (info.optDouble("position") * 1000)));
      player.setPlaybackSpeed(rate);
      player.addListener(
          new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
              status.setText(
                  info.optBoolean("offline")
                      ? "离线数据不完整，请连接源设备后重新下载"
                      : "播放失败：请检查源设备是否在线、文件编码是否受手机支持");
            }

            @Override
            public void onPlaybackStateChanged(int state) {
              if (state == Player.STATE_ENDED) {
                ended = true;
                save();
              }
            }
          });
      player.prepare();
      player.play();
      handler.postDelayed(saveTick, 5000);
    }
  }

  void seek(long delta) {
    if (player != null) player.seekTo(Math.max(0, player.getCurrentPosition() + delta));
  }

  void setZoom(float amount) {
    zoom = Math.max(1, Math.min(3, amount));
    View surface = view.getVideoSurfaceView();
    if (surface != null) {
      surface.setScaleX(zoom);
      surface.setScaleY(zoom);
    }
    status.setText("缩放 " + Math.round(zoom * 100) + "% · 长按临时加速");
  }

  void save() {
    if (player == null || player.getDuration() == C.TIME_UNSET) return;
    long pos = ended ? 0 : player.getCurrentPosition();
    String url = info.optString("url"), progress = info.optString("progressUrl");
    getSharedPreferences("local-progress", 0).edit().putLong(MediaRepository.key(url), pos).apply();
    try {
      info.put("position", pos / 1000.0);
    } catch (Exception ignored) {
    }
    if (progress.isEmpty()) return;
    app.io.execute(
        () -> {
          HttpURLConnection connection = null;
          try {
            connection = (HttpURLConnection) new URL(progress).openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setRequestMethod("PUT");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            try (java.io.OutputStream out = connection.getOutputStream()) {
              out.write(
                  ("{\"position\":" + (pos / 1000.0) + "}")
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            connection.getResponseCode();
          } catch (Exception ignored) {
          } finally {
            if (connection != null) connection.disconnect();
          }
        });
  }

  @Override
  public void onPause() {
    save();
    handler.removeCallbacks(saveTick);
    if (player != null) {
      player.release();
      player = null;
      view.setPlayer(null);
    }
    super.onPause();
  }
}
