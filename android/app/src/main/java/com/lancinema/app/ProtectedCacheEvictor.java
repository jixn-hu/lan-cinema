package com.lancinema.app;

import androidx.media3.common.C;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheEvictor;
import androidx.media3.datasource.cache.CacheSpan;
import java.io.File;
import java.util.Set;
import java.util.TreeSet;

/** Evicts only streaming spans. Download requests pin their cache key across restarts. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class ProtectedCacheEvictor implements CacheEvictor {
  final Set<String> pinned;
  final File directory;
  final TreeSet<CacheSpan> lru =
      new TreeSet<>(
          (a, b) -> {
            int c = Long.compare(a.lastTouchTimestamp, b.lastTouchTimestamp);
            return c == 0 ? a.compareTo(b) : c;
          });
  volatile long maxBytes, streamingBytes;

  ProtectedCacheEvictor(long maxBytes, Set<String> pinned, File directory) {
    this.maxBytes = maxBytes;
    this.pinned = pinned;
    this.directory = directory;
  }

  public boolean requiresCacheSpanTouches() {
    return true;
  }

  public void onCacheInitialized() {}

  public synchronized void onStartFile(Cache cache, String key, long position, long length) {
    evict(cache, pinned.contains(key) || length == C.LENGTH_UNSET ? 0 : length);
  }

  public synchronized void onSpanAdded(Cache cache, CacheSpan span) {
    if (!pinned.contains(span.key) && lru.add(span)) streamingBytes += span.length;
    evict(cache, 0);
  }

  public synchronized void onSpanRemoved(Cache cache, CacheSpan span) {
    if (lru.remove(span)) streamingBytes -= span.length;
  }

  public synchronized void onSpanTouched(Cache cache, CacheSpan oldSpan, CacheSpan newSpan) {
    onSpanRemoved(cache, oldSpan);
    onSpanAdded(cache, newSpan);
  }

  synchronized void pin(Cache cache, String key) {
    for (CacheSpan s : cache.getCachedSpans(key)) if (lru.remove(s)) streamingBytes -= s.length;
  }

  synchronized void evict(Cache cache, long incoming) {
    while (!lru.isEmpty()
        && (streamingBytes + incoming > maxBytes
            || directory.getUsableSpace() < 256L * 1024 * 1024)) {
      CacheSpan oldest = lru.first();
      cache.removeSpan(oldest);
    }
  }

  synchronized void clear(Cache cache) {
    for (CacheSpan s : new java.util.ArrayList<>(lru)) cache.removeSpan(s);
  }
}
