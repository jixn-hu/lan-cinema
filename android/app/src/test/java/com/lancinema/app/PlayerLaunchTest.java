package com.lancinema.app;

import static org.junit.Assert.*;

import android.content.Intent;
import android.view.View;
import androidx.media3.exoplayer.ExoPlayer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 30, 35})
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class PlayerLaunchTest {
  @Test
  public void launchesAndAttachesRealPlayerThroughFullActivityLifecycle() {
    Intent intent =
        new Intent()
            .putExtra(
                "video",
                "{\"url\":\"http://127.0.0.1:9/test.mp4\",\"name\":\"测试视频\",\"position\":0}");
    ActivityController<PlayerActivity> controller =
        Robolectric.buildActivity(PlayerActivity.class, intent);
    CinemaApp app = null;
    try {
      controller.setup().visible();
      PlayerActivity activity = controller.get();
      app = (CinemaApp) activity.getApplication();
      ShadowLooper.idleMainLooper();
      assertFalse(activity.isFinishing());
      assertNotNull(ReflectionHelpers.<ExoPlayer>getField(activity, "player"));
      assertEquals(View.VISIBLE, activity.findViewById(R.id.player_root).getVisibility());
    } finally {
      if (app != null) {
        controller.pause().stop().destroy();
        app.media().downloads.release();
        app.media().cache.release();
      }
    }
  }
}
