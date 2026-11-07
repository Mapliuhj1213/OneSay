#!/system/bin/sh
# ★ r2/sysux P2：把 OneSay 设为系统默认数字助理 / 记录 / 恢复原默认助理。在手机上以 root 跑：
#   su -c 'sh assist_default.sh backup|set|restore|status [包名]'
#   包名默认 com.onesay.assistant.debug（release 用 com.onesay.assistant）。
# backup  ：把当前 voice_interaction_service / voice_recognition_service / assistant 原值写进 $BK（已存在则不覆盖，保住「最初」的原值）
# set     ：先 backup，再把三项指向本 App 的 VIS / RecognitionService，并放开 ASSIST_STRUCTURE / ASSIST_SCREENSHOT appop
# restore ：按 $BK 逐项写回（原来是 null 的就 delete），成功后把备份改名为 .restored-时间
# status  ：打印三项当前值和备份内容
PKG=${2:-com.onesay.assistant.debug}
BK=${BK:-/data/local/tmp/bixby-assist-default.bak}
VIS=$PKG/com.onesay.assistant.v2.assist.OneSayVoiceInteractionService
RS=$PKG/com.onesay.assistant.v2.assist.OneSayRecognitionService
KEYS="voice_interaction_service voice_recognition_service assistant"

cur() { settings get secure "$1"; }

backup() {
  if [ -f "$BK" ]; then echo "备份已存在（不覆盖）：$BK"; cat "$BK"; return 0; fi
  : > "$BK" || { echo "写不了 $BK"; return 1; }
  for k in $KEYS; do echo "$k=$(cur $k)" >> "$BK"; done
  echo "# saved $(date '+%F %T')" >> "$BK"
  echo "已记录原默认助理 → $BK"; cat "$BK"
}

set_default() {
  pm path "$PKG" >/dev/null 2>&1 || { echo "未安装 $PKG"; return 1; }
  backup || return 1
  settings put secure voice_interaction_service "$VIS"
  settings put secure voice_recognition_service "$RS"
  settings put secure assistant "$VIS"
  appops set "$PKG" ASSIST_STRUCTURE allow 2>/dev/null
  appops set "$PKG" ASSIST_SCREENSHOT allow 2>/dev/null
  sleep 2
  status
  # 组件无效时系统会在重新绑定时清掉/改写设置（10-04 旧问题），这里复查一次
  [ "$(cur voice_interaction_service)" = "$VIS" ] || { echo "⚠ 系统没接受 voice_interaction_service（被清掉了）"; return 2; }
}

restore() {
  [ -f "$BK" ] || { echo "没有备份 $BK，不动"; return 1; }
  for k in $KEYS; do
    v=$(grep "^$k=" "$BK" | head -1 | cut -d= -f2-)
    # null = 原来没有这一项 → 删除；空串（三星出厂常见）→ 写回空串；其余原样写回
    if [ "$v" = "null" ]; then settings delete secure "$k" >/dev/null; else settings put secure "$k" "$v"; fi
  done
  ok=1
  for k in $KEYS; do
    v=$(grep "^$k=" "$BK" | head -1 | cut -d= -f2-)
    [ "$(cur $k)" = "$v" ] || { echo "⚠ $k 恢复后为 $(cur $k)，期望 $v"; ok=0; }
  done
  if [ $ok = 1 ]; then mv "$BK" "$BK.restored-$(date +%Y%m%d%H%M%S)"; echo "已恢复原默认助理"; fi
  status
  [ $ok = 1 ]
}

status() {
  for k in $KEYS; do echo "now $k=$(cur $k)"; done
  [ -f "$BK" ] && sed 's/^/bak /' "$BK"
  return 0
}

case "$1" in
  backup) backup;;
  set) set_default;;
  restore) restore;;
  status) status;;
  *) echo "usage: assist_default.sh backup|set|restore|status [pkg]"; exit 1;;
esac
