package com.lkxuii.rrswitch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * 前台服务：定期重写 force_pad_floor_fps，维持闲时锁定。
 *
 * 为什么需要它：该设置的生效状态会被 ColorOS 的 VRR 服务覆盖
 * （切换应用、退出视频、系统策略重算等），不是"设一次永久有效"。
 * 实测每 20 秒重写一次可以稳定维持。
 */
public class LockService extends Service {

    private static final String CHANNEL = "rrlock";
    private static final long INTERVAL_MS = 20000L;

    private Handler handler;
    private Runnable task;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL, "刷新率锁定", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            c.setSound(null, null);
            nm.createNotificationChannel(c);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        Notification n = b.setContentTitle("刷新率已锁定")
                .setContentText("闲时保持 60Hz，每 20 秒校准")
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .build();
        startForeground(1001, n);

        handler = new Handler(Looper.getMainLooper());
        task = new Runnable() {
            @Override public void run() {
                reapply();
                if (handler != null) handler.postDelayed(this, INTERVAL_MS);
            }
        };
        handler.post(task);
        return START_STICKY;
    }

    private void reapply() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (!Shizuku.pingBinder()
                            || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                        return;
                    }
                    Method m = Shizuku.class.getDeclaredMethod("newProcess",
                            String[].class, String[].class, String.class);
                    m.setAccessible(true);
                    Process p = (Process) m.invoke(null, new String[]{"/system/bin/sh", "-c",
                            "settings put global force_pad_floor_fps 0; sleep 0.2; "
                                    + "settings put global force_pad_floor_fps 60"}, null, null);
                    p.waitFor();
                } catch (Throwable ignored) {
                }
            }
        }, "rr-lock").start();
    }

    @Override
    public void onDestroy() {
        if (handler != null) {
            handler.removeCallbacks(task);
            handler = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
