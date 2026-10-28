package com.lancinema.app;

import static org.junit.Assert.*;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import androidx.media3.exoplayer.ExoPlayer;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class PlayerInteractionTest {
  private PlayerActivity activity;
  private ExoPlayer player;
  private View touch;
  private long start;

  @Before
  public void setup() {
    Intent intent =
        new Intent().putExtra("video", "{\"url\":\"http://127.0.0.1/video\",\"name\":\"测试视频\"}");
    activity = Robolectric.buildActivity(PlayerActivity.class, intent).create().get();
    View root = activity.findViewById(R.id.player_root);
    root.measure(
        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
    root.layout(0, 0, 1080, 1920);
    touch = activity.findViewById(R.id.gesture_area);
    player = new ExoPlayer.Builder(activity).build();
    player.setPlaybackSpeed(1.5f);
    player.setPlayWhenReady(true);
    ReflectionHelpers.setField(activity, "player", player);
    ReflectionHelpers.setField(activity, "rate", 1.5f);
    start = SystemClock.uptimeMillis();
  }

  @After
  public void teardown() {
    activity.onPause();
    activity.onDestroy();
  }

  private void event(int action, float x, float y) {
    MotionEvent e = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0);
    touch.dispatchTouchEvent(e);
    e.recycle();
  }

  private void waitHold() {
    ShadowLooper.shadowMainLooper().idleFor(450, TimeUnit.MILLISECONDS);
  }

  @Test
  public void holdBoostsAndReleaseRestoresChosenSpeed() {
    event(MotionEvent.ACTION_DOWN, 500, 800);
    waitHold();
    assertEquals(2, player.getPlaybackParameters().speed, 0);
    event(MotionEvent.ACTION_UP, 500, 800);
    assertEquals(1.5f, player.getPlaybackParameters().speed, 0);
  }

  @Test
  public void canceledTouchAlwaysRestoresSpeed() {
    event(MotionEvent.ACTION_DOWN, 500, 800);
    waitHold();
    event(MotionEvent.ACTION_CANCEL, 500, 800);
    assertEquals(1.5f, player.getPlaybackParameters().speed, 0);
  }

  @Test
  public void movingBeforeHoldDoesNotStartBoost() {
    event(MotionEvent.ACTION_DOWN, 500, 800);
    event(MotionEvent.ACTION_MOVE, 600, 800);
    waitHold();
    assertEquals(1.5f, player.getPlaybackParameters().speed, 0);
  }

  @Test
  public void touchLockDisablesHold() {
    activity.findViewById(R.id.player_lock).performClick();
    event(MotionEvent.ACTION_DOWN, 500, 800);
    waitHold();
    assertEquals(1.5f, player.getPlaybackParameters().speed, 0);
    assertEquals(View.GONE, activity.findViewById(R.id.player_controls).getVisibility());
    assertEquals(View.VISIBLE, activity.findViewById(R.id.player_unlock_button).getVisibility());
  }

  @Test
  public void pausedVideoDoesNotBoostOrStartPlaying() {
    player.pause();
    event(MotionEvent.ACTION_DOWN, 500, 800);
    waitHold();
    assertEquals(1.5f, player.getPlaybackParameters().speed, 0);
    assertFalse(player.getPlayWhenReady());
  }

  @Test
  public void secondFingerCancelsHoldUntilAllFingersLeave() {
    event(MotionEvent.ACTION_DOWN, 500, 800);
    waitHold();
    assertEquals(2, player.getPlaybackParameters().speed, 0);
    MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
    MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
    for (int i = 0; i < 2; i++) {
      properties[i] = new MotionEvent.PointerProperties();
      properties[i].id = i;
      properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
      coords[i] = new MotionEvent.PointerCoords();
      coords[i].x = 400 + i * 200;
      coords[i].y = 800;
      coords[i].pressure = 1;
      coords[i].size = 1;
    }
    MotionEvent second =
        MotionEvent.obtain(
            start,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,
            properties,
            coords,
            0,
            0,
            1,
            1,
            0,
            0,
            0,
            0);
    touch.dispatchTouchEvent(second);
    second.recycle();
    waitHold();
    assertEquals(1.5f, player.getPlaybackParameters().speed, 0);
  }

  @Test
  @Config(qualifiers = "sw800dp-w1280dp-h800dp-land-mdpi")
  public void tabletFollowsDeviceOrientationAndKeepsControlsSeparateFromPicture() {
    assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, activity.getRequestedOrientation());
    VideoTransform transform = ReflectionHelpers.getField(activity, "transform");
    transform.pinch(2, 300, 400, 300, 400);
    ReflectionHelpers.callInstanceMethod(activity, "applyTransform");
    assertEquals(2, activity.findViewById(R.id.video_view).getScaleX(), 0);
    assertEquals(1, activity.findViewById(R.id.player_controls).getScaleX(), 0);
    assertEquals(1, touch.getScaleX(), 0);
  }
}
