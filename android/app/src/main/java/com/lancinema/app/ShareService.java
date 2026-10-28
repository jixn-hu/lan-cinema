package com.lancinema.app;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

public final class ShareService extends Service {
  @Override
  public void onCreate() {
    super.onCreate();
    NotificationManager manager = getSystemService(NotificationManager.class);
    manager.createNotificationChannel(
        new NotificationChannel("sharing", "局域网文件共享", NotificationManager.IMPORTANCE_LOW));
    PendingIntent open =
        PendingIntent.getActivity(
            this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
    Notification notification =
        new Notification.Builder(this, "sharing")
            .setSmallIcon(R.drawable.ic_cinema)
            .setContentTitle("局域影库 · 文件共享已开启")
            .setContentText("同一局域网设备可访问你选择的文件")
            .setContentIntent(open)
            .setOngoing(true)
            .build();
    if (android.os.Build.VERSION.SDK_INT >= 29)
      startForeground(41, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
    else startForeground(41, notification);
    try {
      ((CinemaApp) getApplication()).startSharing();
    } catch (Exception e) {
      stopSelf();
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int id) {
    return START_NOT_STICKY;
  }

  @Override
  public void onDestroy() {
    ((CinemaApp) getApplication()).stopSharing();
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent i) {
    return null;
  }
}
