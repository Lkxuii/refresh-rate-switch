package com.lkxuii.rrswitch;

import android.content.pm.PackageManager;

import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * 刷新率校准的执行逻辑，被 LockService 和 AppSwitchService 共用。
 *
 * 关键点：
 * 1. rate_mode=4 保持智能模式（活动可高刷）
 * 2. floor 用 0→60 强制制造一次变化——写相同值不会触发 OPPO 服务的回调，
 *    这是「设置值正确但实际不生效」的根源
 */
final class RrApply {

    private static Method sNewProcess;

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

    static void apply() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (sNewProcess == null) return;
                    if (!Shizuku.pingBinder()) return;
                    if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return;
                    Process p = (Process) sNewProcess.invoke(null, new String[]{"/system/bin/sh", "-c",
                            "settings put secure oplus_customize_screen_refresh_rate 4; "
                                    + "settings put global force_pad_floor_fps 0; sleep 0.2; "
                                    + "settings put global force_pad_floor_fps 60"}, null, null);
                    p.waitFor();
                } catch (Throwable ignored) {
                }
            }
        }, "rr-apply").start();
    }
}
