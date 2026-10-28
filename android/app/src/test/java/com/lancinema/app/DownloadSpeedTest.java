package com.lancinema.app;

import static org.junit.Assert.*;

import org.junit.Test;

public class DownloadSpeedTest {
  @Test
  public void restoredGigabytesAreNotCountedAsNewDownload() {
    DownloadSpeed meter = new DownloadSpeed();
    long offset = 3L * 1024 * 1024 * 1024;
    assertEquals(0, meter.update(offset, 1000, true));
    assertEquals(10 * 1024 * 1024, meter.update(offset + 10 * 1024 * 1024, 2000, true));
  }

  @Test
  public void pauseAndResumeResetTheMeasurement() {
    DownloadSpeed meter = new DownloadSpeed();
    meter.update(0, 0, true);
    assertEquals(1000, meter.update(1000, 1000, true));
    assertEquals(0, meter.update(1000, 2000, false));
    assertEquals(0, meter.update(1000, 60000, true));
    assertEquals(2000, meter.update(3000, 61000, true));
  }

  @Test
  public void stalledDownloadDecaysToZero() {
    DownloadSpeed meter = new DownloadSpeed();
    meter.update(0, 0, true);
    meter.update(100000, 1000, true);
    for (int i = 2; i <= 5; i++) meter.update(100000, i * 1000, true);
    assertEquals(0, meter.update(100000, 6000, true));
  }

  @Test
  public void highRatesUse64BitByteCounts() {
    DownloadSpeed meter = new DownloadSpeed();
    meter.update(0, 0, true);
    assertEquals(5_000_000_000L, meter.update(5_000_000_000L, 1000, true));
  }
}
