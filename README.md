# Refresh Rate Switch

[![release](https://img.shields.io/github/v/release/Lkxuii/refresh-rate-switch)](https://github.com/Lkxuii/refresh-rate-switch/releases)
[![license](https://img.shields.io/github/license/Lkxuii/refresh-rate-switch)](https://github.com/Lkxuii/refresh-rate-switch/blob/main/LICENSE)

Android 平板刷新率手动切换工具，无需 root。通过 [Shizuku](https://shizuku.rikka.app/) 获取 shell 权限修改系统设置，把「活动刷新率」和「闲时刷新率」拆开独立控制。

在 **一加平板 2 Pro（OPD2413 / ColorOS 15 / Android 15）** 上开发并验证，其他 ColorOS / OxygenOS 机型可参考。

## 功能

| 项目 | 可选值 | 作用 |
|---|---|---|
| 活动刷新率 | 30 / 48 / 50 / 60 / 90 / 120 / 144 | 滑动、动画、视频播放时的上限 |
| 闲时刷新率 | 50 / 60 | 静止无操作时的下限 |

另外提供「强制重算」（熄屏再亮屏，让 SurfaceFlinger 重新选模式）和「恢复默认」。

## 它解决什么问题

ColorOS 的 TouchIdle 策略会在 **3 秒无触摸**后把刷新率降到 50Hz。50Hz 与 120/144Hz 不成整数倍关系，滚动和动画会有轻微抖动。把下限抬到 60Hz 后，静止时不再掉到 50。

## 实现原理

### 活动刷新率

写入 `Settings.System.peak_refresh_rate`，全档位真实有效：

```
设 60  → 滑动时锁 60Hz
设 144 → 滑动时可跑到 144Hz（取决于当前应用是否请求高帧）
```

### 闲时刷新率

写入 OPPO 私有的 `Settings.Global.force_pad_floor_fps`。**注意它是开关语义，不是数值语义**：

```
填 0    → 50Hz（ColorOS 默认）
填 30   → 60Hz
填 60   → 60Hz
填 90   → 60Hz
填 120  → 60Hz
```

所以 App 只提供 50 / 60 两档，映射为 `50 → floor=0`、`60 → floor=60`。

### 为什么必须用 Shizuku

ColorOS 把 `min_refresh_rate` / `peak_refresh_rate` 标记为受限 key，普通应用写入会被服务端拒绝：

```
java.lang.IllegalArgumentException: You cannot keep your settings in the secure settings.
```

以下通道均验证无效：

| 尝试 | 结果 |
|---|---|
| `Settings.Secure` 命名空间 | 能写入，但系统不读 |
| 直调 `SettingsProvider` 的 `PUT_system` | 同样被拒 |
| `adb appops set WRITE_SETTINGS allow` | 被 OPPO 权限服务接管，查询恒为 `default` |

只有以 shell 身份执行 `settings put` 才能成功，因此依赖 Shizuku。

## 环境要求

- Android 8.0+（minSdk 26），targetSdk 35
- [Shizuku](https://shizuku.rikka.app/) 正在运行
- 构建需要 JDK 11+、curl、unzip

## 构建

```bash
git clone git@github.com:Lkxuii/refresh-rate-switch.git
cd refresh-rate-switch
bash build.sh
# 产物：out/rrswitch.apk
```

脚本首次运行会自动从腾讯云镜像下载 Android build-tools 35 / platform 35，并从 Maven Central 拉取 Shizuku API 与 AIDL，无需安装 Android Studio 或 Gradle。

手工构建链路：`aapt2 compile → aapt2 link → javac → d8 → zipalign → apksigner`。

## 自动测试

```bash
ADB=/path/to/adb bash test.sh
```

前置条件：设备已连接、App 已安装并已授权 Shizuku。覆盖 9 组用例：

| 用例 | 验证内容 |
|---|---|
| 1 | 活动 60 / 闲时 60 写入正确 |
| 2 | 活动 144 / 闲时 50 写入正确 |
| 3 | **回归**：重复应用相同值仍生效（写相同值不触发回调的坑） |
| 4 | 闲时 50 的实际刷新率确实为 50Hz |
| 5 | 闲时 60 的实际刷新率稳定在 60Hz |
| 6 | 活动 60 时滑动被限制在 60Hz |
| 7 | **回归**：杀进程后档位记忆保持 |
| 8 | 恢复默认（peak=144、floor=0） |
| 9 | 无崩溃记录 |

测试结束会自动恢复到「活动 144 / 闲时 60」。

> 脚本用 `uiautomator dump` 动态取控件坐标，不依赖硬编码分辨率；但档位行按 y 坐标区间区分，若布局大改需要调整 `tap_rate` 里的阈值。

## 使用

1. 安装 APK
2. 打开 App，点右下角「授权 Shizuku」并在弹窗中允许
3. 右上角变绿「Shizuku 已连接」后，选择档位并点「应用设置」

> **重装 App 后 Shizuku 授权会失效**（uid 变化），需要重新点一次「授权 Shizuku」。

## 为什么需要「保持锁定」

`force_pad_floor_fps` 的生效状态**不是永久的**。实测以下情况会让 idle 掉回 50Hz：

- 从视频应用退出后（视频会请求 144Hz，退出时状态被覆盖）
- 切换应用、系统策略重算
- 设备重启

即便设置值 `floor=60`、服务内部 `mLockRate=1` 都还在，实际刷新率仍可能是 50Hz——这就是
「设置看着对但就是不生效」的原因。

所以 App 提供**保持静止 60Hz**：开启后启动前台服务，反复重写该设置强制刷新状态。
代价是通知栏常驻一条通知。不需要时可以随时关掉。

锁定采用**双通道**：

| 通道 | 触发时机 | 恢复速度 |
|---|---|---|
| 事件驱动 | 亮屏、解锁 | 实测 1 秒内 |
| 定时兜底 | 每 10 秒一次 | 最多 10 秒 |

应用切换没有轻量的监听手段（`UsageStatsManager` 需要用户手动开特殊权限，
`AccessibilityService` 又太重），所以靠定时兜底覆盖。

## 已知限制

- **闲时最高只能 60Hz**。ColorOS 的 TouchIdle 阈值写死在 `/my_product/etc/oplus_vrr_config.json`（`"hw_lowest_rate": 50`），该文件在只读分区、`root:root 644`，shell 权限无法修改。已尝试并通过实测排除的路径：`min_refresh_rate`、`cmd display set-user-preferred-display-mode`、`oplus_refreshrate_override`、`oplus_customize_screen_refresh_rate`、`dumpsys dynamicvsync`、`oplus_vrr_service` 命令入口。
- 系统重启后设置可能被厂商策略重写，打开 App 重新点一次「应用设置」即可。

## 技术备注

接入 Shizuku 时踩到的坑，源码中已全部处理：

1. `Shizuku.newProcess()` 在 API 13.x 被降级为 `private`，需反射调用
2. 必须合并 `dev.rikka.shizuku:aidl` 与 `:shared` 的 `classes.jar`，否则运行时 `NoClassDefFoundError: moe/shizuku/server/IShizukuApplication$Stub`
3. 需要 `<queries><package android:name="moe.shizuku.privileged.api" /></queries>`（Android 11+ 包可见性）
4. `moe.shizuku.manager.permission.API_V23` 必须实际授予，仅声明无效
5. provider 不能声明 `android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"`，否则 App 内调用自己的 provider 会被拦截

## 目录结构

```
app/
  AndroidManifest.xml
  res/            布局、颜色、drawable
  src/            MainActivity.java（单 Activity）
build.sh          手工构建脚本（自动拉依赖）
```

## License

MIT
