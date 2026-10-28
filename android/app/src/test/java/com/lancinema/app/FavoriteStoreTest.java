package com.lancinema.app;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class FavoriteStoreTest {
  CinemaApp app;

  @Before
  public void setup() {
    app = (CinemaApp) RuntimeEnvironment.getApplication();
    app.getSharedPreferences("favorites", 0).edit().clear().commit();
  }

  @Test
  public void persistsSourceAndFileAcrossStoreRecreationAndRemovesOnlyChosenFavorite()
      throws Exception {
    JSONObject pc =
        new JSONObject()
            .put("key", "pc:video")
            .put("deviceName", "我的电脑")
            .put("file", new JSONObject().put("id", "video").put("name", "电影.mp4"));
    JSONObject phone =
        new JSONObject()
            .put("key", "phone:video")
            .put("file", new JSONObject().put("id", "video").put("name", "电影.mp4"));
    app.favorites.toggle(pc);
    app.favorites.toggle(phone);
    FavoriteStore reopened = new FavoriteStore(app);
    assertEquals(2, reopened.all().length());
    assertEquals("phone:video", reopened.all().getJSONObject(0).getString("key"));
    assertEquals("我的电脑", reopened.all().getJSONObject(1).getString("deviceName"));
    reopened.toggle(pc);
    assertFalse(app.favorites.contains("pc:video"));
    assertTrue(app.favorites.contains("phone:video"));
    assertEquals(1, app.favorites.all().length());
  }

  @Test
  public void invalidBookmarkDoesNotReplaceSavedVideos() throws Exception {
    app.favorites.toggle(new JSONObject().put("key", "pc:video").put("file", new JSONObject()));
    app.favorites.toggle(new JSONObject().put("key", "invalid"));
    assertEquals(1, new FavoriteStore(app).all().length());
  }
}
