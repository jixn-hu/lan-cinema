package com.lancinema.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.*;
import android.view.accessibility.AccessibilityManager;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.function.IntConsumer;
import org.json.JSONArray;
import org.json.JSONObject;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlayerActivity extends Activity {
  private CinemaApp app;
  private ExoPlayer player;
  private PlayerView view;
  private JSONObject info;
  private JSONArray queue;
  private int queueIndex;
  private FrameLayout root, controls;
  private View touch, transport, loading, errorPanel, unlock;
  private TextView feedback, position, duration, detail, speed, reset;
  private ImageButton playButton;
  private DefaultTimeBar timeline;
  private ProgressBar miniProgress;
  private Dialog sheet;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final VideoTransform transform = new VideoTransform();
  private GestureDetector taps;
  private ScaleGestureDetector scale;
  private AudioManager audio;
  private float rate = 1, boostRate = 2, focusX, focusY;
  private float downX, downY, lastX, lastY, startBrightness;
  private int startVolume, dragMode, orientationMode;
  private long seekStart, seekTarget;
  private float videoRatio;
  private boolean boosting, ended, locked, multiTouch, moved, scrubbing, controlsVisible = true;
  private boolean resumePlay = true;
  private static final int ACCENT = 0xffc9f591;
  private final Runnable hideControls = () -> showControls(false);
  private final Runnable hideFeedback = () -> feedback.setVisibility(View.GONE);
  private final Runnable hold =
      () -> {
        if (player == null || locked || multiTouch || moved || !player.getPlayWhenReady()) return;
        boosting = true;
        player.setPlaybackSpeed(Math.max(boostRate, rate));
        showControls(false);
        touch.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        message("▶▶  " + rateText(Math.max(boostRate, rate)) + " 加速中 · 松开恢复", false);
      };
  private final Runnable tick =
      new Runnable() {
        @Override
        public void run() {
          updateProgress();
          handler.postDelayed(this, 250);
        }
      };
  private final Runnable saveTick =
      new Runnable() {
        @Override
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
    if (Build.VERSION.SDK_INT >= 28) {
      WindowManager.LayoutParams attributes = getWindow().getAttributes();
      attributes.layoutInDisplayCutoutMode =
          WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
      getWindow().setAttributes(attributes);
    }
    try {
      info =
          new JSONObject(
              state != null && state.containsKey("video")
                  ? state.getString("video")
                  : getIntent().getStringExtra("video"));
      queue = app.playlist == null ? new JSONArray().put(info) : app.playlist;
      queueIndex = 0;
      for (int i = 0; i < queue.length(); i++)
        if (queue.getJSONObject(i).optString("url").equals(info.optString("url"))) queueIndex = i;
    } catch (Exception e) {
      finish();
      return;
    }
    if (state != null) {
      try {
        info.put("position", state.getDouble("position", info.optDouble("position")));
      } catch (Exception ignored) {
      }
      resumePlay = state.getBoolean("playing", true);
    }
    rate = getPreferences(0).getFloat("rate", 1);
    boostRate = getPreferences(0).getFloat("boostRate", 2);
    orientationMode = getPreferences(0).getInt("orientation", 0);
    audio = (AudioManager) getSystemService(AUDIO_SERVICE);
    setContentView(R.layout.player);
    immersive();
    root = findViewById(R.id.player_root);
    controls = findViewById(R.id.player_controls);
    view = findViewById(R.id.video_view);
    view.setPivotX(0);
    view.setPivotY(0);
    touch = findViewById(R.id.gesture_area);
    transport = findViewById(R.id.player_transport);
    loading = findViewById(R.id.player_loading);
    errorPanel = findViewById(R.id.player_error);
    unlock = findViewById(R.id.player_unlock_button);
    feedback = findViewById(R.id.player_feedback);
    position = findViewById(R.id.player_position);
    duration = findViewById(R.id.player_duration);
    detail = findViewById(R.id.player_detail);
    speed = findViewById(R.id.player_speed);
    reset = findViewById(R.id.player_reset);
    timeline = findViewById(R.id.player_timeline);
    miniProgress = findViewById(R.id.player_mini_progress);
    playButton = findViewById(R.id.player_play);
    ((TextView) findViewById(R.id.player_title)).setText(info.optString("name", "视频"));
    ((TextView) findViewById(R.id.player_source))
        .setText(
            info.optBoolean("offline") ? "LAN CINEMA  /  离线观看" : "LAN CINEMA  /  局域网直连 · 自动缓存");
    speed.setText("倍速 " + rateText(rate));
    click(R.id.player_back, this::finish);
    click(R.id.player_play, this::togglePlay);
    click(R.id.player_rewind, () -> seek(-10000));
    click(R.id.player_forward, () -> seek(10000));
    click(R.id.player_speed, this::speedSheet);
    click(R.id.player_fit, this::pictureSheet);
    click(R.id.player_orientation, this::orientationSheet);
    click(R.id.player_more, this::settingsSheet);
    click(R.id.player_next, this::playNext);
    click(R.id.player_playlist, this::playlistSheet);
    updateQueueButtons();
    click(
        R.id.player_reset,
        () -> {
          transform.reset();
          applyTransform();
          message("画面已复位", true);
        });
    click(R.id.player_lock, () -> setLocked(true));
    click(R.id.player_unlock_button, () -> setLocked(false));
    click(
        R.id.player_retry,
        () -> {
          if (player != null) {
            errorPanel.setVisibility(View.GONE);
            player.prepare();
            player.play();
          }
        });
    timeline.addListener(
        new TimeBar.OnScrubListener() {
          @Override
          public void onScrubStart(TimeBar bar, long pos) {
            stopBoost();
            scrubbing = true;
            handler.removeCallbacks(hideControls);
            previewSeek(pos);
          }

          @Override
          public void onScrubMove(TimeBar bar, long pos) {
            previewSeek(pos);
          }

          @Override
          public void onScrubStop(TimeBar bar, long pos, boolean canceled) {
            scrubbing = false;
            if (!canceled) seekTo(pos);
            feedback.setVisibility(View.GONE);
            scheduleHide();
          }
        });
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          int left, top, right, bottom;
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets safe =
                insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            left = safe.left;
            top = safe.top;
            right = safe.right;
            bottom = safe.bottom;
          } else {
            left = insets.getStableInsetLeft();
            top = insets.getStableInsetTop();
            right = insets.getStableInsetRight();
            bottom = insets.getStableInsetBottom();
            if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
              left = Math.max(left, insets.getDisplayCutout().getSafeInsetLeft());
              right = Math.max(right, insets.getDisplayCutout().getSafeInsetRight());
              top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop());
            }
          }
          controls.setPadding(left, top, right, bottom);
          FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) unlock.getLayoutParams();
          lp.leftMargin = left + getResources().getDimensionPixelSize(R.dimen.player_gutter);
          unlock.setLayoutParams(lp);
          FrameLayout.LayoutParams fp = (FrameLayout.LayoutParams) feedback.getLayoutParams();
          fp.topMargin = top + dp(92);
          feedback.setLayoutParams(fp);
          return insets;
        });
    touch.addOnLayoutChangeListener(
        (v, l, t, r, b, ol, ot, or, ob) -> {
          if (r - l != or - ol || b - t != ob - ot) {
            cancelGesture();
            transform.viewport(r - l, b - t);
            applyTransform();
            boolean narrow = (r - l) / getResources().getDisplayMetrics().density < 360;
            findViewById(R.id.player_rewind).setVisibility(narrow ? View.GONE : View.VISIBLE);
            findViewById(R.id.player_forward).setVisibility(narrow ? View.GONE : View.VISIBLE);
          }
        });
    installGestures();
    applyOrientation();
    message("长按加速 · 双指在触摸处放大 · 双击左右快进退", true);
  }

  private void click(int id, Runnable action) {
    findViewById(id)
        .setOnClickListener(
            v -> {
              stopBoost();
              action.run();
              scheduleHide();
            });
  }

  private void updateQueueButtons() {
    View next = findViewById(R.id.player_next);
    next.setEnabled(queueIndex + 1 < queue.length());
    next.setAlpha(next.isEnabled() ? 1 : .35f);
    ((TextView) findViewById(R.id.player_playlist))
        .setText("选集 " + (queueIndex + 1) + "/" + queue.length());
  }

  private void playlistSheet() {
    String[] names = new String[queue.length()];
    for (int i = 0; i < names.length; i++)
      names[i] = (i + 1) + "  " + queue.optJSONObject(i).optString("name", "视频");
    options("播放列表", names, queueIndex, this::playEpisode);
  }

  private void playNext() {
    if (queueIndex + 1 < queue.length()) playEpisode(queueIndex + 1);
  }

  private void playEpisode(int index) {
    if (index < 0 || index >= queue.length() || index == queueIndex) return;
    stopPlayer();
    queueIndex = index;
    try {
      info = new JSONObject(queue.getJSONObject(index).toString());
    } catch (Exception e) {
      return;
    }
    ended = false;
    videoRatio = 0;
    resumePlay = true;
    transform.reset();
    applyTransform();
    errorPanel.setVisibility(View.GONE);
    ((TextView) findViewById(R.id.player_title)).setText(info.optString("name", "视频"));
    ((TextView) findViewById(R.id.player_source))
        .setText(
            info.optBoolean("offline") ? "LAN CINEMA  /  离线观看" : "LAN CINEMA  /  局域网直连 · 自动缓存");
    updateQueueButtons();
    startPlayer();
    showControls(true);
  }

  private int dp(float n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  private static String rateText(float n) {
    return String.format(
        Locale.ROOT,
        "%s×",
        new java.math.BigDecimal(Float.toString(n)).stripTrailingZeros().toPlainString());
  }

  private static String time(long ms) {
    long s = Math.max(0, ms) / 1000;
    return s >= 3600
        ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        : String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60);
  }

  private void immersive() {
    if (Build.VERSION.SDK_INT >= 30) {
      getWindow().setDecorFitsSystemWindows(false);
      // Before attachment the view may not have a controller yet. Window's getter
      // can dereference a missing DecorView during Activity.onCreate on Android 11+.
      WindowInsetsController controller = getWindow().getDecorView().getWindowInsetsController();
      if (controller != null) {
        controller.hide(WindowInsets.Type.systemBars());
        controller.setSystemBarsBehavior(
            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
      }
    } else
      getWindow()
          .getDecorView()
          .setSystemUiVisibility(
              View.SYSTEM_UI_FLAG_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                  | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                  | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
  }

  @Override
  public void onWindowFocusChanged(boolean focused) {
    super.onWindowFocusChanged(focused);
    if (focused) immersive();
  }

  @Override
  public void onConfigurationChanged(Configuration config) {
    super.onConfigurationChanged(config);
    cancelGesture();
    if (sheet != null) sheet.dismiss();
    int gutter = getResources().getDimensionPixelSize(R.dimen.player_gutter);
    for (int id : new int[] {R.id.player_header, R.id.player_bottom}) {
      View v = findViewById(id);
      v.setPadding(gutter, v.getPaddingTop(), gutter, v.getPaddingBottom());
    }
    root.requestApplyInsets();
    immersive();
  }

  private void scheduleHide() {
    handler.removeCallbacks(hideControls);
    AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
    if ((am != null && am.isTouchExplorationEnabled()) || scrubbing || sheet != null) return;
    if (locked || (player != null && player.isPlaying())) handler.postDelayed(hideControls, 4000);
  }

  private void showControls(boolean show) {
    controlsVisible = show;
    controls.setVisibility(show && !locked ? View.VISIBLE : View.GONE);
    unlock.setVisibility(show && locked ? View.VISIBLE : View.GONE);
    miniProgress.setVisibility(show ? View.GONE : View.VISIBLE);
    if (show) scheduleHide();
  }

  private void setLocked(boolean value) {
    cancelGesture();
    locked = value;
    showControls(true);
    message(value ? "触控已锁定 · 点击锁图标解锁" : "触控已解锁", true);
  }

  private void message(String text, boolean dismiss) {
    handler.removeCallbacks(hideFeedback);
    feedback.setText(text);
    feedback.setVisibility(View.VISIBLE);
    if (dismiss) handler.postDelayed(hideFeedback, 1600);
  }

  private void togglePlay() {
    if (player == null) return;
    if (player.getPlaybackState() == Player.STATE_ENDED) {
      ended = false;
      player.seekTo(0);
      player.play();
    } else if (player.getPlayWhenReady()) player.pause();
    else player.play();
    showControls(true);
  }

  private void stopBoost() {
    handler.removeCallbacks(hold);
    if (boosting) {
      boosting = false;
      if (player != null) player.setPlaybackSpeed(rate);
      if (feedback != null) message("已恢复 " + rateText(rate), true);
    }
  }

  private void cancelGesture() {
    stopBoost();
    moved = true;
    multiTouch = true;
    dragMode = 0;
    if (taps != null) {
      MotionEvent cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0, 0, 0);
      taps.onTouchEvent(cancel);
      cancel.recycle();
    }
  }

  private void installGestures() {
    taps =
        new GestureDetector(
            this,
            new GestureDetector.SimpleOnGestureListener() {
              @Override
              public boolean onDown(MotionEvent e) {
                return true;
              }

              @Override
              public boolean onSingleTapConfirmed(MotionEvent e) {
                showControls(!controlsVisible);
                return true;
              }

              @Override
              public boolean onDoubleTap(MotionEvent e) {
                if (!locked) {
                  stopBoost();
                  seek(e.getX() < touch.getWidth() / 2f ? -10000 : 10000);
                } else showControls(true);
                return true;
              }
            });
    taps.setIsLongpressEnabled(false);
    scale =
        new ScaleGestureDetector(
            this,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
              @Override
              public boolean onScaleBegin(ScaleGestureDetector d) {
                stopBoost();
                focusX = d.getFocusX();
                focusY = d.getFocusY();
                showControls(false);
                return !locked;
              }

              @Override
              public boolean onScale(ScaleGestureDetector d) {
                transform.pinch(d.getScaleFactor(), focusX, focusY, d.getFocusX(), d.getFocusY());
                focusX = d.getFocusX();
                focusY = d.getFocusY();
                applyTransform();
                message("缩放 " + Math.round(transform.scale * 100) + "% · 单指拖动画面", true);
                return true;
              }
            });
    scale.setQuickScaleEnabled(false);
    touch.setOnTouchListener(
        (v, e) -> {
          int action = e.getActionMasked();
          if (action == MotionEvent.ACTION_DOWN) {
            downX = lastX = e.getX();
            downY = lastY = e.getY();
            multiTouch = moved = false;
            dragMode = 0;
            seekStart = player == null ? 0 : player.getCurrentPosition();
            startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            startBrightness = getWindow().getAttributes().screenBrightness;
            if (startBrightness < 0)
              startBrightness =
                  Settings.System.getInt(
                          getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128)
                      / 255f;
            handler.removeCallbacks(hideControls);
            if (!locked) handler.postDelayed(hold, 400);
          }
          if (action == MotionEvent.ACTION_POINTER_DOWN) {
            multiTouch = true;
            stopBoost();
            MotionEvent cancel = MotionEvent.obtain(e);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            taps.onTouchEvent(cancel);
            cancel.recycle();
          }
          if (!locked) scale.onTouchEvent(e);
          if (action == MotionEvent.ACTION_MOVE && !multiTouch && !locked) {
            float dx = e.getX() - downX, dy = e.getY() - downY;
            if (Math.hypot(dx, dy) > ViewConfiguration.get(this).getScaledTouchSlop()) {
              if (!moved) {
                moved = true;
                stopBoost();
                MotionEvent cancel = MotionEvent.obtain(e);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                taps.onTouchEvent(cancel);
                cancel.recycle();
              }
              if (transform.scale > 1.01f) {
                transform.pan(e.getX() - lastX, e.getY() - lastY);
                applyTransform();
              } else if (Math.max(Math.abs(dx), Math.abs(dy)) > dp(20)) {
                if (dragMode == 0)
                  dragMode =
                      Math.abs(dx) > Math.abs(dy) ? 1 : (downX < touch.getWidth() / 2f ? 2 : 3);
                showControls(false);
                if (dragMode == 1 && player != null) {
                  long span = Math.min(120000, Math.max(10000, playableDuration() / 3));
                  seekTarget = bounded(seekStart + (long) (dx / touch.getWidth() * span));
                  previewSeek(seekTarget);
                } else if (dragMode == 2) {
                  float brightness =
                      Math.max(.02f, Math.min(1, startBrightness - dy / touch.getHeight() * 1.5f));
                  WindowManager.LayoutParams attrs = getWindow().getAttributes();
                  attrs.screenBrightness = brightness;
                  getWindow().setAttributes(attrs);
                  message("亮度  " + Math.round(brightness * 100) + "%", false);
                } else if (dragMode == 3 && !audio.isVolumeFixed()) {
                  int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                  int volume =
                      Math.max(
                          0,
                          Math.min(
                              max, Math.round(startVolume - dy / touch.getHeight() * max * 1.5f)));
                  try {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
                  } catch (SecurityException ignored) {
                  }
                  message("音量  " + Math.round(volume * 100f / Math.max(1, max)) + "%", false);
                }
              }
            }
          }
          if (!multiTouch && !moved && !boosting) taps.onTouchEvent(e);
          lastX = e.getX();
          lastY = e.getY();
          if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            boolean held = boosting;
            stopBoost();
            if (held) {
              MotionEvent cancel = MotionEvent.obtain(e);
              cancel.setAction(MotionEvent.ACTION_CANCEL);
              taps.onTouchEvent(cancel);
              cancel.recycle();
            }
            if (action == MotionEvent.ACTION_UP && dragMode == 1 && !multiTouch) seekTo(seekTarget);
            if (dragMode != 0) handler.postDelayed(hideFeedback, 800);
            dragMode = 0;
            scheduleHide();
          }
          return true;
        });
  }

  private void applyTransform() {
    view.setScaleX(transform.scale);
    view.setScaleY(transform.scale);
    view.setTranslationX(transform.x);
    view.setTranslationY(transform.y);
    reset.setVisibility(transform.scale > 1.01f ? View.VISIBLE : View.GONE);
  }

  private long playableDuration() {
    return player == null || player.getDuration() == C.TIME_UNSET
        ? 0
        : Math.max(0, player.getDuration());
  }

  private long bounded(long target) {
    return Math.max(0, Math.min(playableDuration(), target));
  }

  private void seekTo(long pos) {
    if (player != null && playableDuration() > 0) {
      ended = false;
      player.seekTo(bounded(pos));
    }
  }

  private void seek(long delta) {
    if (player == null) return;
    seekTo(player.getCurrentPosition() + delta);
    message((delta > 0 ? "快进" : "快退") + " 10 秒 · " + time(player.getCurrentPosition()), true);
  }

  private void previewSeek(long pos) {
    position.setText(time(pos));
    message(time(pos) + "  /  " + time(playableDuration()), false);
  }

  private void updateProgress() {
    if (player == null) return;
    long total = playableDuration(), pos = player.getCurrentPosition();
    if (!scrubbing) {
      timeline.setPosition(pos);
      position.setText(time(pos));
    }
    timeline.setDuration(total);
    timeline.setBufferedPosition(player.getBufferedPosition());
    timeline.setEnabled(total > 0);
    duration.setText(time(total));
    miniProgress.setProgress(total == 0 ? 0 : (int) (pos * 1000 / total));
    miniProgress.setSecondaryProgress(
        total == 0 ? 0 : (int) (player.getBufferedPosition() * 1000 / total));
    if (videoRatio == 0) detail.setText(info.optBoolean("offline") ? "离线观看" : "正在连接视频");
    else
      detail.setText(
          (info.optBoolean("offline")
                  ? "离线"
                  : "已缓冲 " + Math.max(0, (player.getBufferedPosition() - pos) / 1000) + " 秒")
              + " · "
              + rateText(rate));
  }

  private void updatePlayback() {
    if (player == null) return;
    boolean buffering = player.getPlaybackState() == Player.STATE_BUFFERING;
    boolean error = player.getPlayerError() != null;
    loading.setVisibility(buffering ? View.VISIBLE : View.GONE);
    transport.setVisibility(buffering || error ? View.INVISIBLE : View.VISIBLE);
    playButton.setImageResource(
        player.getPlayWhenReady() && !ended ? R.drawable.player_pause : R.drawable.player_play);
    playButton.setContentDescription(
        getString(
            player.getPlayWhenReady() && !ended ? R.string.player_pause : R.string.player_play));
    if (!player.getPlayWhenReady() || ended || error) showControls(true);
    else scheduleHide();
  }

  private void options(String title, String[] names, int selected, IntConsumer action) {
    stopBoost();
    handler.removeCallbacks(hideControls);
    if (sheet != null) sheet.dismiss();
    final Dialog dialog = new Dialog(this);
    sheet = dialog;
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(20), dp(20), dp(20), dp(24));
    content.setBackgroundResource(R.drawable.player_pill);
    TextView heading = new TextView(this);
    heading.setText(title);
    heading.setTextColor(Color.WHITE);
    heading.setTextSize(20);
    heading.setTypeface(null, android.graphics.Typeface.BOLD);
    heading.setPadding(dp(12), 0, 0, dp(16));
    content.addView(heading);
    for (int i = 0; i < names.length; i++) {
      final int choice = i;
      TextView row = new TextView(this);
      row.setText((i == selected ? "✓  " : "    ") + names[i]);
      row.setTextSize(15);
      row.setTextColor(i == selected ? ACCENT : Color.WHITE);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(dp(12), dp(12), dp(12), dp(12));
      row.setMinHeight(dp(52));
      row.setBackgroundResource(R.drawable.player_button_bg);
      row.setFocusable(true);
      row.setOnClickListener(
          v -> {
            Dialog current = sheet;
            if (current != null) current.dismiss();
            action.accept(choice);
          });
      content.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(false);
    scroll.addView(content);
    sheet.setContentView(scroll);
    sheet.setOnDismissListener(
        d -> {
          if (sheet == dialog) sheet = null;
          scheduleHide();
          immersive();
        });
    Window window = sheet.getWindow();
    if (window != null) {
      window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
      window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      WindowManager.LayoutParams attrs = window.getAttributes();
      attrs.dimAmount = .45f;
      window.setAttributes(attrs);
    }
    sheet.show();
    boolean wide = root.getWidth() > root.getHeight();
    int availableWidth = Math.max(dp(240), root.getWidth() - dp(32));
    int maxHeight = Math.max(dp(160), root.getHeight() - dp(32));
    window.setLayout(
        Math.min(dp(wide ? 340 : 440), availableWidth),
        Math.min(dp(100 + names.length * 56), maxHeight));
    window.setGravity(
        wide ? Gravity.END | Gravity.CENTER_VERTICAL : Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
  }

  private void speedSheet() {
    float[] rates = {.5f, .75f, 1, 1.25f, 1.5f, 2, 3};
    String[] names = new String[rates.length];
    int selected = -1;
    for (int i = 0; i < rates.length; i++) {
      names[i] = rateText(rates[i]);
      if (rate == rates[i]) selected = i;
    }
    options(
        "播放倍速",
        names,
        selected,
        n -> {
          rate = rates[n];
          if (player != null) player.setPlaybackSpeed(rate);
          speed.setText("倍速 " + rateText(rate));
          getPreferences(0).edit().putFloat("rate", rate).apply();
        });
  }

  private void pictureSheet() {
    options(
        "画面",
        new String[] {"适应视频 · 保留完整画面", "填满屏幕 · 裁去边缘", "原始比例 · 重置缩放"},
        view.getResizeMode() == AspectRatioFrameLayout.RESIZE_MODE_ZOOM ? 1 : 0,
        n -> {
          view.setResizeMode(
              n == 1
                  ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                  : AspectRatioFrameLayout.RESIZE_MODE_FIT);
          transform.reset();
          applyTransform();
          message(n == 1 ? "已填满屏幕" : "已适应视频", true);
        });
  }

  private void orientationSheet() {
    options(
        "屏幕方向",
        new String[] {"自动 · 手机跟随视频 / 平板跟随设备", "横屏", "竖屏", "锁定当前方向"},
        orientationMode,
        n -> {
          orientationMode = n;
          getPreferences(0).edit().putInt("orientation", n).apply();
          applyOrientation();
        });
  }

  private void applyOrientation() {
    if (isInMultiWindowMode()) return;
    int orientation;
    if (orientationMode == 1) orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
    else if (orientationMode == 2) orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
    else if (orientationMode == 3) orientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED;
    else if (getResources().getConfiguration().smallestScreenWidthDp >= 600)
      orientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
    else if (videoRatio > 0)
      orientation =
          videoRatio >= 1
              ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
              : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
    else orientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    if (getRequestedOrientation() != orientation) setRequestedOrientation(orientation);
  }

  @Override
  public void onMultiWindowModeChanged(boolean multi, Configuration config) {
    super.onMultiWindowModeChanged(multi, config);
    applyOrientation();
  }

  private void settingsSheet() {
    options(
        "播放设置",
        new String[] {"长按加速：" + rateText(boostRate) + " · 点击调整", "手势操作说明", "画面比例与缩放", "屏幕方向"},
        -1,
        n -> {
          if (n == 0)
            options(
                "长按临时加速",
                new String[] {"2×", "2.5×", "3×"},
                boostRate == 2 ? 0 : boostRate == 2.5f ? 1 : 2,
                i -> {
                  boostRate = new float[] {2, 2.5f, 3}[i];
                  getPreferences(0).edit().putFloat("boostRate", boostRate).apply();
                });
          else if (n == 1)
            options(
                "手势操作",
                new String[] {
                  "长按画面：临时加速，松手恢复",
                  "双指开合：以手指位置为中心缩放 1–4×",
                  "放大后：单指拖动画面，点「复位」还原",
                  "双击左 / 右：快退 / 快进 10 秒",
                  "横向滑动：预览进度，松手跳转",
                  "左侧上下：亮度；右侧上下：音量",
                  "单击：显示控制；锁图标：防止误触"
                },
                -1,
                i -> {});
          else if (n == 2) pictureSheet();
          else orientationSheet();
        });
  }

  @Override
  public void onResume() {
    super.onResume();
    if (info == null || app.requireUnlock(this)) return;
    startPlayer();
  }

  private void startPlayer() {
    if (player == null) {
      MediaRepository media = app.media();
      media.track(info);
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
              stopBoost();
              ((TextView) findViewById(R.id.player_error_text))
                  .setText(
                      info.optBoolean("offline")
                          ? "离线数据不完整，请连接源设备后重新下载"
                          : "暂时无法播放。请检查源设备和网络；部分视频编码需要设备硬件支持。\n" + error.getErrorCodeName());
              errorPanel.setVisibility(View.VISIBLE);
              updatePlayback();
            }

            @Override
            public void onVideoSizeChanged(VideoSize size) {
              if (size.height > 0) {
                videoRatio = size.width * size.pixelWidthHeightRatio / size.height;
                applyOrientation();
              }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
              if (state == Player.STATE_ENDED) {
                ended = true;
                stopBoost();
                save();
                int finished = queueIndex;
                handler.post(
                    () -> {
                      if (ended && queueIndex == finished) playNext();
                    });
              }
              if (state == Player.STATE_READY) errorPanel.setVisibility(View.GONE);
              updatePlayback();
            }

            @Override
            public void onIsPlayingChanged(boolean playing) {
              updatePlayback();
            }

            @Override
            public void onPlayWhenReadyChanged(boolean ready, int reason) {
              if (!ready) stopBoost();
              updatePlayback();
            }
          });
      player.prepare();
      player.setPlayWhenReady(resumePlay);
      handler.post(tick);
      handler.postDelayed(saveTick, 5000);
      updatePlayback();
    }
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
  public void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putDouble(
        "position",
        player == null ? info.optDouble("position") : player.getCurrentPosition() / 1000.0);
    state.putBoolean("playing", player == null ? resumePlay : player.getPlayWhenReady());
    if (info != null) state.putString("video", info.toString());
  }

  @Override
  public void onPause() {
    stopPlayer();
    super.onPause();
  }

  private void stopPlayer() {
    cancelGesture();
    handler.removeCallbacks(tick);
    handler.removeCallbacks(saveTick);
    handler.removeCallbacks(hideControls);
    handler.removeCallbacks(hideFeedback);
    if (sheet != null) sheet.dismiss();
    if (player != null) {
      resumePlay = player.getPlayWhenReady();
      save();
      view.setPlayer(null);
      player.release();
      player = null;
    }
  }

  @Override
  public void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    super.onDestroy();
  }

  @Override
  public void onBackPressed() {
    if (locked) {
      setLocked(false);
      return;
    }
    super.onBackPressed();
  }
}
