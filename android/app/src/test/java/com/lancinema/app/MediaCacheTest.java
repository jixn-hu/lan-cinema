package com.lancinema.app;

import static org.junit.Assert.*;

import androidx.media3.datasource.DataSourceInputStream;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.ContentMetadataMutations;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class MediaCacheTest {
  CinemaApp app;
  MediaRepository media;
  JSONObject info;
  String id;

  @Before
  public void setup() throws Exception {
    app = (CinemaApp) RuntimeEnvironment.getApplication();
    media = app.media();
    info =
        new JSONObject()
            .put("url", "http://127.0.0.1:9/test.mp4?rev=100-1")
            .put("name", "episode.mp4")
            .put("type", "video")
            .put("size", 100);
    id = MediaRepository.key(info.getString("url"));
    media.track(info);
    ContentMetadataMutations mutations = new ContentMetadataMutations();
    ContentMetadataMutations.setContentLength(mutations, 100);
    media.cache.applyContentMetadataMutations(id, mutations);
  }

  void span(long start, int size) throws Exception {
    CacheSpan hole = media.cache.startReadWrite(id, start, size);
    try {
      File file = media.cache.startFile(id, start, size);
      try (FileOutputStream out = new FileOutputStream(file)) {
        out.write(new byte[size]);
      }
      media.cache.commitFile(file, size);
    } finally {
      media.cache.releaseHoleSpan(hole);
    }
  }

  @After
  public void cleanup() {
    media.main.removeCallbacksAndMessages(null);
    media.downloads.release();
    media.cache.release();
  }

  @Test
  public void disjointPlaybackCacheShowsActualBytesAndRemainsTemporary() throws Exception {
    span(0, 40);
    span(60, 40);
    JSONObject row = media.list().getJSONObject(0);
    assertEquals(80, row.getLong("bytes"));
    assertEquals(100, row.getLong("total"));
    assertTrue(row.getBoolean("temporary"));
    assertFalse(row.getBoolean("complete"));
    assertFalse(media.pinned.contains(id));
  }

  @Test
  public void completePlaybackCacheBecomesProtectedDownloadAndPlaysWithoutNetwork()
      throws Exception {
    span(0, 100);
    ShadowLooper.idleMainLooper();
    assertTrue(media.list().getJSONObject(0).getBoolean("complete"));
    assertTrue(media.pinned.contains(id));
    synchronized (media.cache) {
      media.evictor.clear(media.cache);
    }
    assertTrue(media.cache.isCached(id, 0, 100));
    CacheDataSource source =
        new CacheDataSource.Factory()
            .setCache(media.cache)
            .setUpstreamDataSourceFactory(null)
            .createDataSource();
    DataSpec spec = new DataSpec.Builder().setUri(info.getString("url")).setKey(id).build();
    try (DataSourceInputStream input = new DataSourceInputStream(source, spec)) {
      assertEquals(100, input.readAllBytes().length);
    }
  }

  @Test
  public void threeGigabyteSparseCacheDoesNotOverflowOrCountAsComplete() throws Exception {
    long total = 3L * 1024 * 1024 * 1024 + 10;
    ContentMetadataMutations mutations = new ContentMetadataMutations();
    ContentMetadataMutations.setContentLength(mutations, total);
    media.cache.applyContentMetadataMutations(id, mutations);
    span(total - 10, 10);
    JSONObject row = media.list().getJSONObject(0);
    assertEquals(total, row.getLong("total"));
    assertEquals(10, row.getLong("bytes"));
    assertFalse(row.getBoolean("complete"));
  }
}
