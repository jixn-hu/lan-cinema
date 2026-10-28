package com.lancinema.app;

import static org.junit.Assert.*;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35})
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class PlayerQueueTest {
  @Test
  public void nextEpisodeChangesRealPlayerAndRestoresCurrentEpisodeAfterRecreation()
      throws Exception {
    CinemaApp app = (CinemaApp) RuntimeEnvironment.getApplication();
    app.playlist = new JSONArray();
    for (int i = 1; i <= 2; i++)
      app.playlist.put(
          new JSONObject()
              .put("url", "http://127.0.0.1:9/episode" + i + ".mp4")
              .put("name", "第" + i + "集"));
    Intent intent = new Intent().putExtra("video", app.playlist.getJSONObject(0).toString());
    ActivityController<PlayerActivity> controller =
        Robolectric.buildActivity(PlayerActivity.class, intent).setup().visible();
    ActivityController<PlayerActivity> restored = null;
    try {
      PlayerActivity activity = controller.get();
      assertTrue(activity.findViewById(R.id.player_next).isEnabled());
      Object oldPlayer = ReflectionHelpers.getField(activity, "player");
      activity.findViewById(R.id.player_next).performClick();
      ShadowLooper.idleMainLooper();
      assertEquals(
          "第2集", ((TextView) activity.findViewById(R.id.player_title)).getText().toString());
      assertNotSame(oldPlayer, ReflectionHelpers.getField(activity, "player"));
      assertFalse(activity.findViewById(R.id.player_next).isEnabled());
      Bundle state = new Bundle();
      controller.saveInstanceState(state).pause().stop().destroy();
      restored = Robolectric.buildActivity(PlayerActivity.class, intent).setup(state).visible();
      assertEquals(
          "第2集", ((TextView) restored.get().findViewById(R.id.player_title)).getText().toString());
    } finally {
      if (restored != null) restored.pause().stop().destroy();
      else if (!controller.get().isDestroyed()) controller.pause().stop().destroy();
      app.media().main.removeCallbacksAndMessages(null);
      app.media().downloads.release();
      app.media().cache.release();
    }
  }
}
