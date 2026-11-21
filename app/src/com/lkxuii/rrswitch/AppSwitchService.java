package com.lkxuii.rrswitch;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * 监听应用切换（窗口变化），切换后立即校准刷新率。
 *
 * 权限边界：配置里 canRetrieveWindowContent="false"，
 * 本服务不读取任何窗口内容，只从事件中取包名做变化判断。
 */
public class AppSwitchService extends AccessibilityService {

    /** 当前前台包名，供 LockService 判断是否游戏（无障碍事件最准确） */
    static volatile String sCurrentPkg = "";

    private String lastPkg = "";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence seq = event.getPackageName();
        if (seq == null) return;
        String pkg = seq.toString();
        if (pkg.equals(lastPkg)) return;
        lastPkg = pkg;
        sCurrentPkg = pkg;
        // 应用切换是状态最易失效的时机，强制重写。
        // 「需要校准」列表留空 → 全部校准；非空 → 只校准列表内的应用。
        if (shouldCalibrate(pkg)) RrApply.apply(true);
    }

    /**
     * 该应用是否需要校准。
     * needlist 留空 → 全部需要；非空 → 仅在列表内才需要。
     */
    private boolean shouldCalibrate(String pkg) {
        try {
            java.util.Set<String> need = getSharedPreferences("rr", MODE_PRIVATE)
                    .getStringSet("needlist", java.util.Collections.<String>emptySet());
            if (!need.isEmpty() && !need.contains(pkg)) return false;
        } catch (Throwable ignored) {
        }
        return true;
    }

    @Override
    public void onInterrupt() {
    }
}
