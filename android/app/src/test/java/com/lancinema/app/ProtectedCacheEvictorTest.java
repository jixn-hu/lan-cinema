package com.lancinema.app;

import static org.junit.Assert.*;

import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheSpan;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.Test;

public class ProtectedCacheEvictorTest {
  static class Fixture {
    Set<String> pinned = new HashSet<>();
    Set<CacheSpan> spans = new HashSet<>();
    ProtectedCacheEvictor policy;
    Cache cache;

    Fixture(long limit) {
      policy =
          new ProtectedCacheEvictor(
              limit,
              pinned,
              new File(".") {
                @Override
                public long getUsableSpace() {
                  return Long.MAX_VALUE;
                }
              });
      cache =
          (Cache)
              Proxy.newProxyInstance(
                  Cache.class.getClassLoader(),
                  new Class[] {Cache.class},
                  (p, m, args) -> {
                    if (m.getName().equals("removeSpan")) {
                      CacheSpan span = (CacheSpan) args[0];
                      spans.remove(span);
                      policy.onSpanRemoved(cache, span);
                      return null;
                    }
                    if (m.getName().equals("getCachedSpans")) {
                      TreeSet<CacheSpan> result =
                          new TreeSet<>(Comparator.comparingLong(s -> s.position));
                      for (CacheSpan s : spans) if (s.key.equals(args[0])) result.add(s);
                      return result;
                    }
                    throw new UnsupportedOperationException(m.getName());
                  });
    }

    CacheSpan add(String key, long length, long touched) {
      CacheSpan span = new CacheSpan(key, 0, length, touched, new File(key));
      spans.add(span);
      policy.onSpanAdded(cache, span);
      return span;
    }
  }

  @Test
  public void evictsOldStreamingSpansButPreservesDownloads() {
    Fixture f = new Fixture(30);
    f.pinned.add("download");
    CacheSpan saved = f.add("download", 100, 1);
    CacheSpan old = f.add("old", 20, 2);
    CacheSpan recent = f.add("recent", 20, 3);
    assertFalse(f.spans.contains(old));
    assertTrue(f.spans.contains(saved));
    assertTrue(f.spans.contains(recent));
    assertEquals(20, f.policy.streamingBytes);
  }

  @Test
  public void clearPreservesFullAndPartialDownloads() {
    Fixture f = new Fixture(100);
    f.pinned.add("full");
    f.pinned.add("partial");
    f.add("full", 300, 1);
    f.add("partial", 30, 2);
    f.add("temporary", 50, 3);
    f.policy.clear(f.cache);
    assertEquals(2, f.spans.size());
    assertEquals(0, f.policy.streamingBytes);
  }

  @Test
  public void promotesPlaybackCacheWithoutCountingItTwice() {
    Fixture f = new Fixture(100);
    CacheSpan span = f.add("video", 60, 1);
    f.pinned.add("video");
    f.policy.pin(f.cache, "video");
    assertEquals(0, f.policy.streamingBytes);
    f.policy.clear(f.cache);
    assertTrue(f.spans.contains(span));
  }

  @Test
  public void settingSmallerLimitEvictsImmediately() {
    Fixture f = new Fixture(100);
    f.add("old", 50, 1);
    CacheSpan latest = f.add("latest", 30, 2);
    f.policy.maxBytes = 35;
    f.policy.evict(f.cache, 0);
    assertEquals(30, f.policy.streamingBytes);
    assertEquals(Collections.singleton(latest), f.spans);
  }

  @Test
  public void twentyGigabyteLimitUsesLongArithmetic() {
    long gb = 1024L * 1024 * 1024;
    Fixture f = new Fixture(20 * gb);
    for (int i = 0; i < 7; i++) f.add("video" + i, 3 * gb, i);
    assertEquals(18 * gb, f.policy.streamingBytes);
    assertEquals(6, f.spans.size());
  }
}
