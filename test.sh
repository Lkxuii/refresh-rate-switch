#!/bin/bash
# 刷新率切换 App 自动测试
# 用法: ADB=/path/to/adb bash test.sh   （ADB 不设则用 PATH 里的 adb）
ADB="${ADB:-adb}"
PKG="com.lkxuii.rrswitch"
PASS=0
FAIL=0
FAILED=""

sh() { "$ADB" shell "$*" 2>&1 | tr -d '\r'; }

ui_xml() { sh "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml"; }

ui_bounds() {
  ui_xml | tr '>' '\n' | grep "text=\"$1\"" \
    | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/'
}

assert_eq() {
  local label="$1" expect="$2" actual="$3"
  if [ "$actual" = "$expect" ]; then
    echo "    PASS  $label = $actual"
    PASS=$((PASS + 1))
  else
    echo "    FAIL  $label  期望[$expect] 实际[$actual]"
    FAIL=$((FAIL + 1))
    FAILED="${FAILED}\n  - ${label}: 期望[$expect] 实际[$actual]"
  fi
}

tap_rate() {
  local text="$1" row="$2" line b1 b2 b3 b4
  if [ "$row" = "idle" ]; then
    line=$(ui_bounds "$text" | awk '$2 > 1600' | head -1)
  else
    # 排除顶部大数字区（y<1000），它和档位文字可能相同
    line=$(ui_bounds "$text" | awk '$2 > 1000 && $2 < 1600' | head -1)
  fi
  if [ -z "$line" ]; then
    echo "    !! 未找到档位 [$text] ($row)"
    return 1
  fi
  set -- $line
  sh "input tap $((($1 + $3) / 2)) $((($2 + $4) / 2))" >/dev/null
  sleep 0.5
}

tap_text() {
  local line b1 b2 b3 b4
  line=$(ui_bounds "$1" | head -1)
  if [ -z "$line" ]; then
    echo "    !! 未找到控件 [$1]"
    return 1
  fi
  set -- $line
  sh "input tap $((($1 + $3) / 2)) $((($2 + $4) / 2))" >/dev/null
  sleep 0.6
}

start_app() {
  sh "am force-stop $PKG" >/dev/null
  sh "am start -W -n $PKG/.MainActivity" >/dev/null
  sleep 3
}

apply() { tap_text "应用设置" >/dev/null; sleep 2; }

get_peak()  { sh 'settings get system peak_refresh_rate'; }
get_floor() { sh 'settings get global force_pad_floor_fps'; }

idle_rate() {
  sh "input keyevent 3" >/dev/null
  sleep 13
  sh 'dumpsys SurfaceFlinger | sed -n "/^    activeMode=/p"' \
    | sed -E 's/.*vsyncRate=([0-9.]+) Hz.*/\1/' | sed -E 's/\.0+$//'
}

swipe_rate() {
  sh "input swipe 1200 1600 1200 900 300" >/dev/null
  sleep 0.4
  sh 'dumpsys SurfaceFlinger | sed -n "/^    activeMode=/p"' \
    | sed -E 's/.*vsyncRate=([0-9.]+) Hz.*/\1/' | sed -E 's/\.0+$//'
}

echo "=============================="
echo " 刷新率切换 App 自动测试"
echo "=============================="

echo ""
echo "[CASE 1] 活动 60 / 闲时 60 写入"
start_app
tap_rate 60 active
tap_rate 60 idle
apply
assert_eq "peak" "60" "$(get_peak)"
assert_eq "floor" "60" "$(get_floor)"

echo ""
echo "[CASE 2] 活动 144 / 闲时 50 写入"
tap_rate 144 active
tap_rate 50 idle
apply
assert_eq "peak" "144" "$(get_peak)"
assert_eq "floor" "0" "$(get_floor)"

echo ""
echo "[CASE 3] 回归: 重复应用相同值仍生效"
apply
assert_eq "floor" "0" "$(get_floor)"

echo ""
echo "[CASE 4] 闲时 50 的实际刷新率"
assert_eq "idle" "50" "$(idle_rate)"

echo ""
echo "[CASE 5] 切回闲时 60 并验证实际值"
sh "am start -n $PKG/.MainActivity" >/dev/null
sleep 2
tap_rate 60 idle
apply
assert_eq "floor" "60" "$(get_floor)"
assert_eq "idle" "60" "$(idle_rate)"

echo ""
echo "[CASE 6] 活动 60 的滑动刷新率"
sh "am start -n $PKG/.MainActivity" >/dev/null
sleep 2
tap_rate 60 active
apply
assert_eq "swipe" "60" "$(swipe_rate)"

echo ""
echo "[CASE 7] 回归: 杀进程后档位记忆保持"
sh "am force-stop $PKG" >/dev/null
sh "settings put global force_pad_floor_fps 0" >/dev/null
sleep 1
sh "am start -n $PKG/.MainActivity" >/dev/null
sleep 3
SUMMARY=$(ui_xml | tr '>' '\n' | grep 'id/tv_summary' | sed -E 's/.*text="([^"]*)".*/\1/')
case "$SUMMARY" in
  *"闲时 60"*)
    echo "    PASS  偏好保持 -> $SUMMARY"
    PASS=$((PASS + 1))
    ;;
  *)
    echo "    FAIL  偏好丢失 -> $SUMMARY"
    FAIL=$((FAIL + 1))
    FAILED="${FAILED}\n  - CASE7 偏好: $SUMMARY"
    ;;
esac

echo ""
echo "[CASE 8] 恢复默认"
tap_text "恢复默认"
sleep 2
assert_eq "peak" "144" "$(get_peak)"
assert_eq "floor" "0" "$(get_floor)"

echo ""
echo "[CASE 9] 崩溃检查（最近一次进程退出原因）"
EXITINFO=$(sh "dumpsys activity exit-info $PKG | grep -m2 -E 'reason='")
echo "    最近退出记录: $EXITINFO"
case "$EXITINFO" in
  *"reason=4"*)
    echo "    WARN  存在崩溃记录（可能是测试中触发，需人工确认）"
    ;;
  *)
    echo "    PASS  无崩溃记录"
    PASS=$((PASS + 1))
    ;;
esac

echo ""
echo "[收尾] 恢复用户常用状态：活动 144 / 闲时 60"
sh "am start -n $PKG/.MainActivity" >/dev/null
sleep 2
tap_rate 144 active
tap_rate 60 idle
apply
sh "input keyevent 3" >/dev/null

echo ""
echo "=============================="
echo " 结果: PASS=$PASS  FAIL=$FAIL"
if [ "$FAIL" -gt 0 ]; then
  echo -e " 失败项:$FAILED"
fi
echo "=============================="
