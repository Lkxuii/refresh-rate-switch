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
        // 应用切换是状态最易失效的时机，强制重写
        RrApply.apply(true);
    }

    @Override
    public void onInterrupt() {
    }
}
