package com.lkxuii.rrswitch;

import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * 刷新率校准的执行逻辑，被 LockService 和 AppSwitchService 共用。
 *
 * 两档策略：
 * - force=true：强制重写（0→60 制造变化）。用于亮屏、解锁、应用切换这类
 *   状态最可能已经失效的时机。
 * - force=false：先读后写。值已经正确就完全不碰设置，
 *   避免无谓的显示模式切换（频繁切换会给 SurfaceFlinger 造成压力）。
 *
 * 另外处理 Shizuku 断连：系统软重启会让 shizuku_server 进程存活
 * 但 binder 句柄失效，此时所有写入会静默失败。这里注册重连监听，
 * 恢复后自动补一次校准。
 */
final class RrApply {

    private static final String CMD_WRITE =
            "settings put secure oplus_customize_screen_refresh_rate 4; "
                    + "settings put global force_pad_floor_fps 0; sleep 0.2; "
                    + "settings put global force_pad_floor_fps 60";

    private static Method sNewProcess;
    private static volatile boolean sWaiting;

    static {
        try {
            Method m = Shizuku.class.getDeclaredMethod("newProcess",
                    String[].class, String[].class, String.class);
            m.setAccessible(true);
            sNewProcess = m;
        } catch (Throwable ignored) {
        }
    }

    private RrApply() {
    }

    static void apply(final boolean force) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!ensureReady()) {
                        android.util.Log.w("RrApply", "shizuku not ready, will retry on reconnect");
                        return;
                    }

                    boolean need = force;
                    if (!need) {
                        String floor = exec("settings get global force_pad_floor_fps");
                        String mode = exec("settings get secure oplus_customize_screen_refresh_rate");
                        need = !"60".equals(floor) || !"4".equals(mode);
                        android.util.Log.d("RrApply", "read floor=" + floor + " mode=" + mode + " need=" + need);
                    }
                    if (need) {
                        exec(CMD_WRITE);
                        android.util.Log.d("RrApply", "write done, force=" + force);
                    }
                } catch (Throwable t) {
                    android.util.Log.e("RrApply", "apply failed force=" + force, t);
                }
            }
        }, "rr-apply").start();
    }

    /** 检查 Shizuku 是否可用；不可用时注册一次重连监听，恢复后自动补校准。 */
    private static boolean ensureReady() {
        if (sNewProcess == null) return false;

        if (Shizuku.pingBinder()) {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        }

        if (!sWaiting) {
            sWaiting = true;
            try {
                Shizuku.addBinderReceivedListenerSticky(new Shizuku.OnBinderReceivedListener() {
                    @Override
                    public void onBinderReceived() {
                        sWaiting = false;
                        android.util.Log.d("RrApply", "binder reconnected, reapply");
                        apply(true);
                    }
                });
                android.util.Log.d("RrApply", "waiting for binder reconnect");
            } catch (Throwable t) {
                sWaiting = false;
                android.util.Log.e("RrApply", "register binder listener failed", t);
            }
        }
        return false;
    }

    private static String exec(String cmd) throws Exception {
        Process p = (Process) sNewProcess.invoke(null,
                new String[]{"/system/bin/sh", "-c", cmd}, null, null);
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        try {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        } finally {
            try {
                r.close();
            } catch (Throwable ignored) {
            }
        }
        p.waitFor();
        return sb.toString().trim();
    }
}
