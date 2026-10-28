package com.lancinema.app;

import android.app.Notification;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadService;
import androidx.media3.exoplayer.scheduler.Scheduler;
import java.util.List;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class VideoDownloadService extends DownloadService {
  public VideoDownloadService() {
    super(42, 1000, MediaRepository.CHANNEL, R.string.download_channel, 0);
  }

  @Override
  protected DownloadManager getDownloadManager() {
    return ((CinemaApp) getApplication()).media().downloads;
  }

  @Override
  protected Scheduler getScheduler() {
    return null;
  }

  @Override
  protected Notification getForegroundNotification(
      List<Download> downloads, int unmetRequirements) {
    MediaRepository media = ((CinemaApp) getApplication()).media();
    return media.notifications.buildProgressNotification(
        this,
        R.drawable.ic_cinema,
        media.contentIntent(),
        "局域影库 · 正在下载",
        downloads,
        unmetRequirements);
  }

  @Override
  public void onTimeout(int startId, int fgsType) {
    ((CinemaApp) getApplication()).media().downloads.pauseDownloads();
    stopSelf();
  }
}
