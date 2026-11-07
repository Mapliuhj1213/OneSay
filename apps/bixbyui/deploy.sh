#!/usr/bin/env bash
# ============================================================================
# OneSay 部署与权限全授
#
#   用法：  bash deploy.sh                    # 编译产物装机 + 授权 + 校验
#           bash deploy.sh path/to/x.apk      # 指定 APK 装机 + 授权 + 校验
#           bash deploy.sh install [x.apk]    # 同上（显式写法）
#           APK=path/to/x.apk bash deploy.sh  # 也可用环境变量指定
#           bash deploy.sh grant-all          # 只对已安装的包重新授权（不重新安装）
#           bash deploy.sh verify             # 只打印当前授权状态
#
#   APK 默认取值顺序 —— 参数 > $APK > out-linux/bixbyui.apk（Linux 构建，存在时）> out/bixbyui.apk（Windows 构建）。
#     构建目录里有 BUILD_VARIANT 时会打印 release / debug。debug 包才含 TestRunReceiver（adb 需 su 发广播）。
#
# 权限策略（用户拍板）：这是开发板，**体验优先级最高**，
#   凡 Manifest 里声明了的运行时权限一律预授权，特殊权限用 appops 直接放行。
#
# 背景：62 个能力里 46 个 shell + 16 个 "intent"（实为 `am start`）都走 root
#   守护执行，本来不吃 App 自身的运行时权限；下面这组是给 App 进程内必须持权的
#   路径（联系人查询 / 相机 / 存储 / 定位）以及后续能力扩展用的。
#
# 踩坑记录：不要写成 A="$ADB -s $SERIAL" 再用 "$A" 调用 ——
#   Windows 下 adb 路径含空格与反斜杠，加引号后整串被当成**一个命令名**，
#   报 "No such file or directory" 且 install 与 grant 会**静默全失败**。
#   一律走下面的 adb_run 函数。
# ============================================================================
set -u

PKG="${BIXBY_PKG:-com.onesay.assistant}"   # debug 变体：BIXBY_PKG=com.onesay.assistant.debug
HERE="$(cd "$(dirname "$0")" && pwd)"

# ---- 解析命令与 APK 路径 ---------------------------------------------------
CMD="all"
ARG_APK=""
case "${1:-}" in
  grant-all|verify) CMD="$1" ;;
  install|all)      CMD="all"; ARG_APK="${2:-}" ;;
  "")               ;;
  *)                ARG_APK="$1" ;;
esac
if [ -n "$ARG_APK" ]; then
  APK="$ARG_APK"
elif [ -n "${APK:-}" ]; then
  :
elif [ -f "$HERE/out-linux/bixbyui.apk" ]; then
  APK="$HERE/out-linux/bixbyui.apk"
else
  APK="$HERE/out/bixbyui.apk"
fi

ADB="${ADB:-}"
if [ -z "$ADB" ]; then
  for c in "/c/Users/Administrator/AppData/Local/Android/Sdk/platform-tools/adb.exe" \
           "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" \
           "$(command -v adb 2>/dev/null)"; do
    [ -n "$c" ] && [ -x "$c" ] && ADB="$c" && break
  done
fi
[ -z "$ADB" ] && { echo "!! 找不到 adb，请用 ADB=/path/to/adb 指定"; exit 2; }

# 串号可覆盖；缺省取第一台在线设备
SERIAL="${SERIAL:-}"
if [ -z "$SERIAL" ]; then
  SERIAL="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
fi
[ -z "$SERIAL" ] && { echo "!! 没有在线设备"; exit 2; }
echo "== 设备: $SERIAL"

# 唯一的 adb 入口
adb_run() { "$ADB" -s "$SERIAL" "$@"; }

# ---- 需要 pm grant 的运行时（dangerous）权限 -------------------------------
RUNTIME_PERMS=(
  android.permission.READ_CONTACTS
  android.permission.WRITE_CONTACTS
  android.permission.GET_ACCOUNTS
  android.permission.READ_CALL_LOG
  android.permission.WRITE_CALL_LOG
  android.permission.PROCESS_OUTGOING_CALLS
  android.permission.CALL_PHONE
  android.permission.ANSWER_PHONE_CALLS
  android.permission.READ_PHONE_STATE
  android.permission.READ_PHONE_NUMBERS
  android.permission.SEND_SMS
  android.permission.RECEIVE_SMS
  android.permission.READ_SMS
  android.permission.RECEIVE_MMS
  android.permission.ACCESS_COARSE_LOCATION
  android.permission.ACCESS_FINE_LOCATION
  android.permission.CAMERA
  android.permission.READ_EXTERNAL_STORAGE
  android.permission.WRITE_EXTERNAL_STORAGE
  android.permission.RECORD_AUDIO
)

# ---- 需要 appops 放行的特殊权限 --------------------------------------------
# 注意：不要往里塞 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS —— 它不是合法的
# appops 名（会报 "Unknown operation string"）。电池优化走下面 do_grant 里的
# `dumpsys deviceidle whitelist +包名`，而该权限本身是 normal 级，装机即授予。
APP_OPS=(
  WRITE_SETTINGS
  GET_USAGE_STATS
  SYSTEM_ALERT_WINDOW
)

do_install() {
  [ -f "$APK" ] || { echo "!! 找不到 $APK，先跑 bash build_linux.sh（或 Windows 上 bash build.sh），或指定 APK 路径"; exit 2; }
  VARIANT_FILE="$(dirname "$APK")/BUILD_VARIANT"
  VARIANT="$( [ -f "$VARIANT_FILE" ] && cat "$VARIANT_FILE" || echo "未知" )"
  echo "== 安装 $APK ($(stat -c %s "$APK") bytes, 变体: $VARIANT)"
  adb_run install -r "$APK" 2>&1 | tail -3
}

do_grant() {
  echo "== 授予运行时权限"
  for p in "${RUNTIME_PERMS[@]}"; do
    out="$(adb_run shell pm grant "$PKG" "$p" 2>&1 | tr -d '\r')"
    if [ -z "$out" ]; then printf "   OK    %s\n" "${p##*.}"
    else printf "   SKIP  %s (%s)\n" "${p##*.}" "$out"; fi
  done

  echo "== 放行特殊权限 (appops)"
  for op in "${APP_OPS[@]}"; do
    out="$(adb_run shell appops set "$PKG" "$op" allow 2>&1 | tr -d '\r')"
    if [ -z "$out" ]; then printf "   OK    %s\n" "$op"
    else printf "   SKIP  %s (%s)\n" "$op" "$out"; fi
  done

  echo "== 电池优化白名单"
  adb_run shell "dumpsys deviceidle whitelist +$PKG" 2>&1 | tr -d '\r' | sed 's/^/   /'
}

do_verify() {
  echo "== 授权状态"
  echo "-- runtime permissions (granted) --"
  adb_run shell "dumpsys package $PKG" 2>/dev/null \
    | grep -E "permission\.[A-Z_]+: granted=true" | sed 's/^ */   /' | head -30
  echo "-- appops --"
  for op in "${APP_OPS[@]}"; do
    printf "   %-38s %s\n" "$op" "$(adb_run shell appops get "$PKG" "$op" 2>/dev/null | tr -d '\r')"
  done
}

case "$CMD" in
  grant-all) do_grant; do_verify ;;
  verify)    do_verify ;;
  *)         do_install; do_grant; do_verify ;;
esac

echo "== 完成"
