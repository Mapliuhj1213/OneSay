#!/system/bin/sh
# OneSay「按键唤醒」可选精确路径：root（或 Shizuku 以 shell 身份）读 getevent 原始按键事件，
# 每次按下目标键就往 App 私有目录写一行触发记录；App（按键唤醒触发方式 = 原始按键事件）用 FileObserver 接收，
# 自己判定双击并按设置的动作唤起。不拦截按键（电源键照常亮 / 灭屏），不改任何系统设置。
#
# 用法（root）：  sh key_daemon.sh               # 默认 KEY_POWER、release 包
#   KD_KEY=KEY_VOLUMEUP sh key_daemon.sh          # 换目标键（getevent -l 里的名字）
#   KD_PKG=com.onesay.assistant.debug sh key_daemon.sh
# 测试钩子：KD_SRC="cat <file>" 代替 getevent；KD_DIR=<dir> 代替 App files 目录；KD_CLOCK=<cmd> 代替毫秒时钟。
#
# 写入格式（与 App 的 KeyTrigger 一致）：按下 "press <seq> <t_ms>"，松开 "up <seq> <held_ms>"。

KEY=${KD_KEY:-KEY_POWER}
PKG=${KD_PKG:-com.onesay.assistant}
FILES=${KD_DIR:-/data/user/0/$PKG/files}
TRIGGER=$FILES/key.trigger
SRC=${KD_SRC:-getevent -lq}

now_ms() {
  if [ -n "${KD_CLOCK:-}" ]; then eval "$KD_CLOCK"; return; fi
  t=$(cat /proc/uptime 2>/dev/null | cut -d' ' -f1)
  s=${t%.*}; f=${t#*.}
  echo $(( s * 1000 + ${f:-0} * 10 ))
}

emit() {
  # 原子写：先写临时文件再 mv，App 侧 FileObserver 收 CLOSE_WRITE / MOVED_TO
  echo "$1" > "$TRIGGER.tmp" && mv -f "$TRIGGER.tmp" "$TRIGGER"
  if [ -z "${KD_DIR:-}" ]; then
    uid=$(stat -c %u "$FILES" 2>/dev/null) && chown "$uid:$uid" "$TRIGGER" 2>/dev/null
    chmod 600 "$TRIGGER" 2>/dev/null
  fi
}

[ -d "$FILES" ] || { echo "key_daemon: $FILES 不存在（App 未安装或未启动过）" >&2; exit 1; }
seq=0
down_at=0
eval "$SRC" | while read -r dev type code value rest; do
  # getevent -lq 行：/dev/input/eventN: EV_KEY KEY_POWER DOWN
  [ "$type" = "EV_KEY" ] || continue
  [ "$code" = "$KEY" ] || continue
  t=$(now_ms)
  case "$value" in
    DOWN|00000001)
      seq=$((seq + 1)); down_at=$t
      emit "press $seq $t" ;;
    UP|00000000)
      [ "$down_at" -gt 0 ] && emit "up $seq $((t - down_at))"
      down_at=0 ;;
  esac
done
