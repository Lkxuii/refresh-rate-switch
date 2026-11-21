package com.lkxuii.rrswitch;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.Display;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.Locale;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    private static final float[] RATES = {30f, 48f, 50f, 60f, 90f, 120f, 144f};
    /** 闲时只有这两档是系统真正支持的，其余值会被 OPPO 钳制到 60 */
    private static final float[] IDLE_RATES = {50f, 60f};
    private static final int REQ_SHIZUKU = 2001;

    private static final String K_PEAK = "peak_refresh_rate";
    private static final String K_MIN = "min_refresh_rate";
    private static final String K_FLOOR = "force_pad_floor_fps";

    private float activeRate = 144f;
    private float idleRate = 60f;

    private TextView tvLive, tvSummary, tvPerm;
    private LinearLayout groupActive, groupIdle;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean polling = false;
    private volatile boolean busy = false;
    private String lastError = "";
    private boolean lockRunning = false;

    /** Shizuku.newProcess 在 13.x 被降级为 private，用反射调用 */
    private static Method sNewProcess;

    /**
     * 手动建立 Shizuku binder 连接。
     * 正常情况下 ShizukuProvider.onCreate() 会自动完成，但部分 ROM 上该回调不触发，
     * 这里直接 call 自己的 provider 取 binder 再交给 Shizuku。
     */
    private String shzDiag = "";

    private void ensureShizukuBinder() {
        try {
            if (Shizuku.pingBinder()) { shzDiag = "ping1"; return; }
            shzDiag = "nobinder";

            try {
                rikka.shizuku.ShizukuProvider.requestBinderForNonProviderProcess(this);
                shzDiag += "|req";
            } catch (Throwable t) {
                shzDiag += "|req:" + t.getClass().getSimpleName();
            }
            if (Shizuku.pingBinder()) { shzDiag += "|ping2"; return; }

            try {
                android.os.Bundle b = getContentResolver().call(
                        android.net.Uri.parse("content://" + getPackageName() + ".shizuku"),
                        "getBinder", null, null);
                if (b == null) { shzDiag += "|b=null"; return; }
                shzDiag += "|b=ok";
                android.os.IBinder binder = b.getBinder("moe.shizuku.privileged.api.intent.extra.BINDER");
                if (binder == null) { shzDiag += "|bd=null"; return; }
                shzDiag += "|bd=ok";
                Shizuku.onBinderReceived(binder, getPackageName());
                shzDiag += "|att=" + Shizuku.pingBinder();
            } catch (Throwable t) {
                shzDiag += "|call:" + t.getClass().getSimpleName();
            }
        } catch (Throwable t) {
            shzDiag += "|fatal:" + t.getClass().getSimpleName();
        }
    }

    static {
        try {
            Method m = Shizuku.class.getDeclaredMethod("newProcess",
                    String[].class, String[].class, String.class);
            m.setAccessible(true);
            sNewProcess = m;
        } catch (Throwable ignored) {
        }
    }

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            pollAsync();
            ui.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvLive = (TextView) findViewById(R.id.tv_live);
        tvSummary = (TextView) findViewById(R.id.tv_summary);
        tvPerm = (TextView) findViewById(R.id.tv_perm);
        groupActive = (LinearLayout) findViewById(R.id.group_active);
        groupIdle = (LinearLayout) findViewById(R.id.group_idle);

        findViewById(R.id.btn_apply).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applyAll(); }
        });
        findViewById(R.id.btn_recalc).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { forceRecalc(); }
        });
        findViewById(R.id.btn_default).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { restoreDefault(); }
        });
        findViewById(R.id.btn_lock).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleLock(); }
        });
        findViewById(R.id.btn_perm).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onPermClick(); }
        });

        Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
            @Override
            public void onRequestPermissionResult(int requestCode, int grantResult) {
                if (requestCode == REQ_SHIZUKU) {
                    updatePermUi();
                    toast(grantResult == PackageManager.PERMISSION_GRANTED
                            ? "Shizuku 已授权" : "授权被拒绝");
                }
            }
        });

        Shizuku.addBinderReceivedListenerSticky(new Shizuku.OnBinderReceivedListener() {
            @Override public void onBinderReceived() {
                ui.post(new Runnable() { @Override public void run() { updatePermUi(); } });
            }
        });

        Shizuku.addBinderDeadListener(new Shizuku.OnBinderDeadListener() {
            @Override public void onBinderDead() {
                ui.post(new Runnable() { @Override public void run() { updatePermUi(); } });
            }
        });

        ensureShizukuBinder();
        rebuildChips();
        updateSummary();
        updateLockUi();
        updatePermUi();
    }

    @Override
    protected void onDestroy() {
        polling = false;
        ui.removeCallbacks(pollTask);
        super.onDestroy();
    }

    // ---------------- 权限通道 ----------------

    /** 直写通道：adb 授权 WRITE_SECURE_SETTINGS */
    private boolean hasSecureWrite() {
        try {
            return checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean shizukuReady() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean hasAnyChannel() {
        return hasSecureWrite() || shizukuReady();
    }

    private String exec(String cmd) {
        if (sNewProcess == null) return "";
        try {
            Object obj = sNewProcess.invoke(null,
                    new String[]{"/system/bin/sh", "-c", cmd}, null, null);
            if (!(obj instanceof Process)) return "";
            Process p = (Process) obj;
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            p.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    // ---------------- 读写 ----------------

    private float readPeak() {
        if (hasSecureWrite()) {
            try {
                return Settings.System.getFloat(getContentResolver(), K_PEAK, 0f);
            } catch (Throwable ignored) { }
        }
        return parseFloat(exec("settings get system " + K_PEAK).trim(), 0f);
    }

    private float readMin() {
        if (hasSecureWrite()) {
            try {
                return Settings.System.getFloat(getContentResolver(), K_MIN, 0f);
            } catch (Throwable ignored) { }
        }
        return parseFloat(exec("settings get system " + K_MIN).trim(), 0f);
    }

    private int readFloor() {
        if (hasSecureWrite()) {
            try {
                return Settings.Global.getInt(getContentResolver(), K_FLOOR, 0);
            } catch (Throwable ignored) { }
        }
        return (int) parseFloat(exec("settings get global " + K_FLOOR).trim(), 0f);
    }

    private boolean writeAll(float active, float idle) {
        final int idleI = (int) idle;
        final int activeI = (int) active;

        // 优先走 Shizuku shell：settings 命令以 shell 身份执行，不受 AppOps 限制
        if (shizukuReady()) {
            // floor 是开关语义：0 → 50Hz，非 0 → 固定 60Hz，不能直接填 idle 值
            final int floorVal = idleI <= 50 ? 0 : 60;
            // 关键：先写 0 再写目标值，强制产生一次值变化。
            // OPPO 服务靠 Settings 变化回调工作，写相同值不会触发，
            // 会导致"设置值没变但状态丢了"时点了应用没反应。
            String out = exec("settings put system " + K_PEAK + " " + activeI
                    + "; settings put system " + K_MIN + " " + idleI
                    + "; settings put global " + K_FLOOR + " 0"
                    + "; sleep 0.2"
                    + "; settings put global " + K_FLOOR + " " + floorVal
                    + "; echo __DONE__");
            return out.contains("__DONE__");
        }

        // 回退：直写（System 需要 WRITE_SETTINGS，Global 需要 WRITE_SECURE_SETTINGS）
        if (hasSecureWrite()) {
            boolean p1 = false, p2 = false, g = false;

            // 标准 API 优先，OPPO 上会因受限 key 抛异常，必须单独兜住，不能中断后续通道
            try { p1 = Settings.System.putString(getContentResolver(), K_PEAK, String.valueOf(activeI)); }
            catch (Throwable ignored) { }
            try { p2 = Settings.System.putString(getContentResolver(), K_MIN, String.valueOf(idleI)); }
            catch (Throwable ignored) { }

            // 标准通道失败则直接走 SettingsProvider 的 PUT_system
            if (!p1) p1 = putSystemRaw(K_PEAK, String.valueOf(activeI));
            if (!p2) p2 = putSystemRaw(K_MIN, String.valueOf(idleI));

            try {
                int floorVal = idleI <= 50 ? 0 : 60;
                // 同 shell 路径：先写 0 强制制造变化，再写目标值
                Settings.Global.putInt(getContentResolver(), K_FLOOR, 0);
                Settings.Global.putInt(getContentResolver(), K_FLOOR, floorVal);
                g = true;
            } catch (Throwable t) {
                lastError = "Global:" + t.getClass().getSimpleName();
            }

            if (!p1 || !p2) {
                lastError = "System 写入被拒(peak=" + p1 + ",min=" + p2 + ") " + lastError;
                return false;
            }
            return g;
        }
        return false;
    }

    /** 直接调 SettingsProvider 的 PUT_system 通道，绕过 Settings.System 的受限 key 检查 */
    private boolean putSystemRaw(String key, String value) {
        try {
            android.os.Bundle ex = new android.os.Bundle();
            ex.putString("value", value);
            ex.putInt("_user", 0);
            getContentResolver().call(
                    android.net.Uri.parse("content://settings/system"),
                    "PUT_system", key, ex);
            return true;
        } catch (Throwable t) {
            lastError = "raw:" + t.getClass().getSimpleName();
            return false;
        }
    }

    private float queryLive() {
        if (shizukuReady()) {
            String out = exec("dumpsys SurfaceFlinger | grep -m1 '^    activeMode='");
            int i = out.indexOf("vsyncRate=");
            int j = out.indexOf(" Hz", i);
            if (i > 0 && j > i) {
                return parseFloat(out.substring(i + 10, j).trim(), -1f);
            }
        }
        try {
            Display d = getWindowManager().getDefaultDisplay();
            if (d != null) return d.getRefreshRate();
        } catch (Throwable ignored) { }
        return -1f;
    }

    // ---------------- 状态刷新 ----------------

    private void pollAsync() {
        if (busy) return;
        if (shizukuReady()) {
            busy = true;
            new Thread(new Runnable() {
                @Override public void run() {
                    final float live = queryLive();
                    final float peak = readPeak();
                    final float min = readMin();
                    final int floor = readFloor();
                    busy = false;
                    ui.post(new Runnable() {
                        @Override public void run() { render(live, peak, min, floor); }
                    });
                }
            }, "rr-poll").start();
        } else {
            render(queryLive(), readPeak(), readMin(), readFloor());
        }
    }

    private void render(float live, float peak, float min, int floor) {
        tvLive.setText(live > 0 ? String.valueOf(Math.round(live)) : "--");
        // 小字固定显示用户设定的目标值，与实际值不一致时正是"需要重新应用"的信号
        tvSummary.setText("设定 活动 " + (int) activeRate
                + " / 闲时 " + (int) idleRate + "  ·  floor " + floor);
    }

    private void loadSettings() {
        // 优先用用户上次保存的意图，而不是当前系统值。
        // 否则系统状态被外部改写后重开 App，会"跟随"系统，用户点应用也恢复不了。
        final android.content.SharedPreferences sp = getSharedPreferences("rr", MODE_PRIVATE);
        final float sa = sp.getFloat("active", -1f);
        final float si = sp.getFloat("idle", -1f);
        if (sa > 0 && si > 0) {
            activeRate = nearest(sa);
            idleRate = si <= 50 ? 50f : 60f;
            rebuildChips();
            updateSummary();
            checkAndReapply();
            return;
        }

        new Thread(new Runnable() {
            @Override public void run() {
                final float p = readPeak();
                final int fl = readFloor();
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (p > 0) activeRate = nearest(p);
                        // 闲时状态以 floor 为准：0 → 50Hz，非 0 → 60Hz
                        idleRate = fl == 0 ? 50f : 60f;
                        rebuildChips();
                        updateSummary();
                        pollAsync();
                    }
                });
            }
        }, "rr-load").start();
    }

    /**
     * 启动时比对系统实际值与用户设定，不一致就自动重新应用。
     * 典型场景：设备重启后 ColorOS 会把 peak/floor 重置，但 App 的偏好还在。
     */
    private void checkAndReapply() {
        if (!hasAnyChannel()) {
            pollAsync();
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                float peak = readPeak();
                int floor = readFloor();
                int wantFloor = idleRate <= 50 ? 0 : 60;
                boolean mismatch = Math.abs(peak - activeRate) > 1 || floor != wantFloor;
                boolean fixed = mismatch && writeAll(activeRate, idleRate);
                final boolean notify = fixed;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (notify) toast("检测到系统设置被重置，已自动恢复");
                        pollAsync();
                    }
                });
            }
        }, "rr-reapply").start();
    }

    // ---------------- 操作 ----------------

    private void applyAll() {
        if (!hasAnyChannel()) {
            toast("需要授权：Shizuku 或 adb WRITE_SECURE_SETTINGS");
            updatePermUi();
            return;
        }
        final float a = activeRate;
        final float i = idleRate;
        if (writeAll(a, i)) {
            getSharedPreferences("rr", MODE_PRIVATE).edit()
                    .putFloat("active", a).putFloat("idle", i).apply();
            toast("已应用：活动 " + (int) a + "Hz / 闲时 " + (int) i + "Hz");
            ui.postDelayed(new Runnable() {
                @Override public void run() { pollAsync(); }
            }, 400);
        } else {
            toast("写入失败：" + lastError);
        }
    }

    private void restoreDefault() {
        if (!hasAnyChannel()) { toast("需要先授权"); return; }

        if (shizukuReady()) {
            exec("settings delete system " + K_MIN
                    + "; settings put system " + K_PEAK + " 144"
                    + "; settings put global " + K_FLOOR + " 0");
        } else if (hasSecureWrite()) {
            try {
                Settings.System.putFloat(getContentResolver(), K_PEAK, 144f);
                Settings.System.putString(getContentResolver(), K_MIN, null);
                Settings.Global.putInt(getContentResolver(), K_FLOOR, 0);
            } catch (Throwable t) {
                toast("恢复失败：" + t.getMessage());
                return;
            }
        }
        getSharedPreferences("rr", MODE_PRIVATE).edit().clear().apply();
        activeRate = 144f;
        idleRate = 50f;
        rebuildChips();
        updateSummary();
        toast("已恢复默认（闲时回到系统 50Hz）");
        ui.postDelayed(new Runnable() {
            @Override public void run() { pollAsync(); }
        }, 400);
    }

    private void forceRecalc() {
        if (!shizukuReady()) {
            toast("仅 Shizuku 模式支持自动重算，可手动熄屏再亮屏");
            return;
        }
        toast("熄屏重算中…");
        new Thread(new Runnable() {
            @Override public void run() {
                exec("input keyevent 26");
                try { Thread.sleep(500); } catch (InterruptedException ignored) { }
                exec("input keyevent 26");
                try { Thread.sleep(800); } catch (InterruptedException ignored) { }
                ui.post(new Runnable() {
                    @Override public void run() {
                        toast("已重新计算显示模式");
                        pollAsync();
                    }
                });
            }
        }, "rr-recalc").start();
    }

    /** 按当前状态决定按钮动作：Shizuku 已连接但没授权 → 请求授权；否则去开系统设置权限 */
    private void onPermClick() {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(REQ_SHIZUKU);
                    return;
                }
                toast("Shizuku 已授权");
                updatePermUi();
                return;
            }
        } catch (Throwable ignored) {
        }
        openWriteSettingsPage();
    }

    /** 开关锁定服务。force_pad_floor_fps 的生效状态会被系统覆盖，需要定期重写。 */
    private void toggleLock() {
        if (lockRunning) {
            stopService(new android.content.Intent(this, LockService.class));
            lockRunning = false;
            toast("已停止锁定，闲时可能回到 50Hz");
        } else {
            if (idleRate <= 50) {
                toast("闲时为 50Hz 时无需锁定");
                return;
            }
            if (!hasAnyChannel()) {
                toast("请先授权 Shizuku");
                updatePermUi();
                return;
            }
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 300);
            }
            startForegroundService(new android.content.Intent(this, LockService.class));
            lockRunning = true;
            toast("已开启锁定（常驻通知，每 20 秒校准）");
        }
        updateLockUi();
    }

    private void updateLockUi() {
        TextView btn = (TextView) findViewById(R.id.btn_lock);
        if (btn == null) return;
        btn.setText(lockRunning ? "保持锁定：开（点此关闭）" : "保持锁定：关");
        btn.setTextColor(lockRunning ? 0xFF4CD07D : 0xFFB0B0BA);
    }

    /** 跳转系统的"允许修改系统设置"开关页（AppOps 权限，adb 改不动，只能走 UI） */
    private void openWriteSettingsPage() {
        try {
            android.content.Intent i = new android.content.Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
            toast("请打开「允许修改系统设置」开关后返回");
        } catch (Throwable t) {
            try {
                startActivity(new android.content.Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS));
            } catch (Throwable t2) {
                toast("无法打开设置页：" + t2);
            }
        }
    }

    private void requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                toast("Shizuku 未连接。可用 adb 授权直写模式：\n"
                        + "pm grant com.lkxuii.rrswitch android.permission.WRITE_SECURE_SETTINGS");
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                toast("已授权");
                updatePermUi();
                return;
            }
            Shizuku.requestPermission(REQ_SHIZUKU);
        } catch (Throwable t) {
            toast("请求失败：" + t);
        }
    }

    // ---------------- UI ----------------

    private void rebuildChips() {
        buildChips(groupActive, true);
        buildChips(groupIdle, false);
    }

    private void buildChips(final LinearLayout parent, final boolean isActive) {
        parent.removeAllViews();
        final float current = isActive ? activeRate : idleRate;
        final float[] rates = isActive ? RATES : IDLE_RATES;

        for (int idx = 0; idx < rates.length; idx++) {
            final float r = rates[idx];
            boolean sel = Math.abs(r - current) < 0.5f;

            TextView chip = new TextView(this);
            chip.setText(String.valueOf((int) r));
            chip.setGravity(Gravity.CENTER);
            chip.setTextSize(16f);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setTextColor(sel ? 0xFF0B0B0D : 0xFFB0B0BA);
            chip.setBackgroundResource(sel ? R.drawable.bg_chip_sel : R.drawable.bg_chip);
            chip.setPadding(0, dp(16), 0, dp(16));

            LinearLayout.LayoutParams lp;
            if (rates.length <= 3) {
                // 档位少时用固定宽度靠左排，避免两个按钮被拉成板砖
                lp = new LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT);
            } else {
                lp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            }
            if (idx > 0) lp.setMarginStart(dp(10));
            chip.setLayoutParams(lp);

            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (isActive) {
                        activeRate = r;
                        if (idleRate > activeRate) idleRate = activeRate;
                    } else {
                        idleRate = r;
                        if (activeRate < idleRate) activeRate = idleRate;
                    }
                    rebuildChips();
                    updateSummary();
                }
            });
            parent.addView(chip);
        }
    }

    private void updateSummary() {
        tvSummary.setText(String.format(Locale.US,
                "设定 活动 %d / 闲时 %d  ·  点“应用设置”生效", (int) activeRate, (int) idleRate));
    }

    private void updatePermUi() {
        ensureShizukuBinder();
        boolean secure = hasSecureWrite();
        boolean shz = shizukuReady();
        boolean bound = false;
        try { bound = Shizuku.pingBinder(); } catch (Throwable ignored) { }
        boolean canWrite = false;
        try { canWrite = Settings.System.canWrite(this); } catch (Throwable ignored) { }
        View btn = findViewById(R.id.btn_perm);

        if (shz) {
            tvPerm.setText("Shizuku 已连接");
            tvPerm.setTextColor(0xFF4CD07D);
            btn.setVisibility(View.GONE);
        } else if (bound) {
            tvPerm.setText("Shizuku 待授权");
            tvPerm.setTextColor(0xFFFFB020);
            ((TextView) btn).setText("授权 Shizuku");
            btn.setVisibility(View.VISIBLE);
        } else if (secure && canWrite) {
            tvPerm.setText("已授权 · 直写模式");
            tvPerm.setTextColor(0xFF4CD07D);
            btn.setVisibility(View.GONE);
        } else if (secure) {
            tvPerm.setText("缺少「修改系统设置」权限");
            tvPerm.setTextColor(0xFFFFB020);
            btn.setVisibility(View.VISIBLE);
        } else {
            boolean running = false;
            try { running = Shizuku.pingBinder(); } catch (Throwable ignored) { }
            tvPerm.setText(running ? "Shizuku 待授权" : "未授权");
            tvPerm.setTextColor(0xFFFF7A45);
            btn.setVisibility(View.VISIBLE);
            tvSummary.setText("诊断: " + shzDiag);
        }

        if ((secure && canWrite) || shz) {
            if (!polling) {
                polling = true;
                loadSettings();
                ui.postDelayed(pollTask, 800);
            }
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private float parseFloat(String s, float def) {
        try { return Float.parseFloat(s.trim()); } catch (Exception e) { return def; }
    }

    private float nearest(float v) {
        float best = RATES[0];
        float bd = Float.MAX_VALUE;
        for (float r : RATES) {
            float d = Math.abs(r - v);
            if (d < bd) { bd = d; best = r; }
        }
        return best;
    }
}
