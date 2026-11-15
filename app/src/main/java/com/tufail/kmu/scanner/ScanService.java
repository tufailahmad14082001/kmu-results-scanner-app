package com.tufail.kmu.scanner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps the scanner alive while a scan runs.
 *
 * The scanner page is a WebView: when the app goes to the background or the
 * phone locks, Android pauses the WebView and a long scan stops. This small
 * foreground service shows one quiet notification ("KMU Scanner is
 * running") and keeps the app's process alive while the page scans, so the
 * scan can finish even if the user switches to another app or the screen
 * turns off. It does no scanning itself — it only holds the process open.
 *
 * Started and stopped from the page through the ScanBridge in MainActivity:
 * the page calls ScanBridge.startScan() when a scan starts and
 * ScanBridge.stopScan() when it finishes.
 */
public class ScanService extends Service {
    private static final int NOTIF_ID = 1;
    private static final String CHANNEL_ID = "kmu_scanner_running";

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("KMU Scanner is running")
                .setContentText("Scanning your results — you can use other apps, the scan keeps going.")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Scanner running", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shown while the KMU scanner is reading your results.");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
