package com.lkxuii.rrswitch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * 前台服务：维持「静止 60Hz」的锁定状态。
 *
 * 双通道：
 * 1. 事件驱动 —— 亮屏、解锁时立即校准（覆盖最常见的失效时机）
 * 2. 定时兜底 —— 每 10 秒一次（应用切换没有轻量监听手段，只能靠它）
 *
 * 之所以要反复重写：force_pad_floor_fps 的生效状态会被 ColorOS 的 VRR 服务覆盖，
 * 且写入相同值不会触发回调，必须用 0→60 制造一次变化。
 */
public class LockService extends Service {

    private static final String CHANNEL = "rrlock";
    private static final long INTERVAL_MS = 10000L;
    private static final long FOREGROUND_POLL_MS = 1000L;

    private Handler handler;
    private Runnable task;
    private Runnable fgTask;
    private BroadcastReceiver screenReceiver;
    private String lastPkg = "";

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
        Notification n = b.setContentTitle("保持静止 60Hz")
                .setContentText("亮屏/解锁即时校准，兜底每 10 秒")
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .build();
        startForeground(1001, n);

        handler = new Handler(Looper.getMainLooper());

        // 通道一：事件驱动
        if (screenReceiver == null) {
            screenReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (Intent.ACTION_SCREEN_ON.equals(action)
                            || Intent.ACTION_USER_PRESENT.equals(action)) {
                        if (handler != null) {
                            // 等系统完成亮屏后的模式初选，再覆盖它
                            handler.postDelayed(new Runnable() {
                                @Override public void run() { reapply(true); }
                            }, 600);
                        }
                    }
                }
            };
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_USER_PRESENT);
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(screenReceiver, f);
                }
            } catch (Throwable ignored) {
            }
        }

        // 通道二：定时兜底
        if (task == null) {
            task = new Runnable() {
                @Override public void run() {
                    reapply(false);
                    if (handler != null) handler.postDelayed(this, INTERVAL_MS);
                }
            };
        }
        handler.removeCallbacks(task);
        handler.post(task);

        // 通道三：前台应用变化（需要「使用情况访问权限」，未授权时该检查会安全地无效）
        if (fgTask == null) {
            fgTask = new Runnable() {
                @Override public void run() {
                    checkForeground();
                    if (handler != null) handler.postDelayed(this, FOREGROUND_POLL_MS);
                }
            };
        }
        handler.removeCallbacks(fgTask);
        handler.post(fgTask);

        return START_STICKY;
    }

    /** 检测前台应用是否变化，变化时立即校准。依赖 UsageStats，未授权时静默跳过。 */
    private void checkForeground() {
        // 无障碍已开启时由 AppSwitchService 实时接管，这里跳过，避免重复写入
        if (accessibilityActive()) return;
        try {
            android.app.usage.UsageStatsManager usm =
                    (android.app.usage.UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            if (usm == null) return;
            long now = System.currentTimeMillis();
            java.util.List<android.app.usage.UsageStats> list = usm.queryUsageStats(
                    android.app.usage.UsageStatsManager.INTERVAL_BEST, now - 15000, now);
            if (list == null || list.isEmpty()) return;
            String top = null;
            long latest = 0;
            for (android.app.usage.UsageStats s : list) {
                if (s.getLastTimeUsed() > latest) {
                    latest = s.getLastTimeUsed();
                    top = s.getPackageName();
                }
            }
            if (top != null && !top.equals(lastPkg)) {
                lastPkg = top;
                reapply(true);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 无障碍服务是否真的在运行（不能用 enabled_accessibility_services 判断） */
    private boolean accessibilityActive() {
        try {
            android.view.accessibility.AccessibilityManager am =
                    (android.view.accessibility.AccessibilityManager)
                            getSystemService(ACCESSIBILITY_SERVICE);
            if (am == null) return false;
            java.util.List<android.accessibilityservice.AccessibilityServiceInfo> list =
                    am.getEnabledAccessibilityServiceList(
                            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (list == null) return false;
            for (android.accessibilityservice.AccessibilityServiceInfo info : list) {
                String id = info.getId();
                if (id != null && id.startsWith(getPackageName())) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void reapply(boolean force) {
        RrApply.apply(force);
    }

    @Override
    public void onDestroy() {
        if (screenReceiver != null) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Throwable ignored) {
            }
            screenReceiver = null;
        }
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
