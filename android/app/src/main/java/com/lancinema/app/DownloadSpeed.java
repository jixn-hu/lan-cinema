package com.lancinema.app;

import java.util.ArrayDeque;

/** A short rolling average; paused time and restored byte offsets never count as throughput. */
final class DownloadSpeed {
  private final ArrayDeque<long[]> samples = new ArrayDeque<>();

  long update(long bytes, long elapsedMs, boolean active) {
    if (!active) {
      samples.clear();
      return 0;
    }
    if (!samples.isEmpty() && (bytes < samples.getLast()[1] || elapsedMs <= samples.getLast()[0]))
      samples.clear();
    samples.addLast(new long[] {elapsedMs, bytes});
    while (samples.size() > 2 && samples.getFirst()[0] < elapsedMs - 3000) samples.removeFirst();
    long[] first = samples.getFirst();
    long time = elapsedMs - first[0];
    return time <= 0 ? 0 : Math.max(0, Math.round((bytes - first[1]) * 1000.0 / time));
  }
}
