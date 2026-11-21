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

        // 通道二：定时兜底（游戏或白名单应用在前台时跳过，避免中途扰动 SurfaceFlinger）
        if (task == null) {
            task = new Runnable() {
                @Override public void run() {
                    if (!shouldSkipTuning()) {
                        reapply(false);
                    }
                    if (handler != null) handler.postDelayed(this, INTERVAL_MS);
                }
            };
        }
        handler.removeCallbacks(task);
        handler.post(task);

        return START_STICKY;
    }

    /**
     * 当前应用是否应跳过校准。
     * 规则：
     * 1. 游戏永远跳过（SF 负载高）
     * 2. 「需要校准」列表留空 → 全部校准；非空 → 只校准列表内的
     */
    private boolean shouldSkipTuning() {
        String pkg = currentPkg();
        if (pkg == null || pkg.isEmpty()) return false;
        if (isGame(pkg)) return true;
        try {
            java.util.Set<String> need = getSharedPreferences("rr", MODE_PRIVATE)
                    .getStringSet("needlist", java.util.Collections.<String>emptySet());
            if (!need.isEmpty() && !need.contains(pkg)) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 判定是否为游戏。游戏期间 SF 负载高，不做定时校准以免加剧风险。 */
    private boolean isGame(String pkg) {
        try {
            android.content.pm.ApplicationInfo ai =
                    getPackageManager().getApplicationInfo(pkg, 0);
            if (ai.category == android.content.pm.ApplicationInfo.CATEGORY_GAME) return true;
        } catch (Throwable ignored) {
        }
        String l = pkg.toLowerCase();
        return l.contains(".tmgp") || l.contains(".game") || l.contains("gamecenter")
                || l.startsWith("com.mihoyo") || l.startsWith("com.netease")
                || l.startsWith("com.hypergryph") || l.startsWith("com.riotgames")
                || l.startsWith("com.supercell") || l.startsWith("com.mojang")
                || l.startsWith("com.epicgames") || l.startsWith("com.garena");
    }

    /**
     * 取当前前台包名。
     * UsageStats 的两条路在 ColorOS 上都不靠谱：
     * - getLastTimeUsed() 恒为 1970（字段没写）
     * - queryEvents 的 MOVE_TO_FOREGROUND 也不总是及时
     * 所以直接读系统焦点，通过 Shizuku 执行 dumpsys。
     */
    private String currentPkg() {
        try {
            String out = RrApply.exec("dumpsys window | grep -m1 mCurrentFocus");
            int i = out.indexOf(" u0 ");
            if (i < 0) return null;
            String rest = out.substring(i + 4).trim();
            int sp = rest.indexOf(' ');
            if (sp > 0) rest = rest.substring(0, sp);
            int slash = rest.indexOf('/');
            if (slash > 0) rest = rest.substring(0, slash);
            return rest.isEmpty() ? null : rest;
        } catch (Throwable ignored) {
            return null;
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
