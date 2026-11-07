#!/system/bin/sh
# 守护 v8 → v7 一键回滚（R10，2026-10-11）。用法：su -c 'sh /data/adb/modules/bixbykey_hijack/rollback_v7.sh'
# 想回到 v6：把下面 v7.bak 换成 v6.bak。
M=/data/adb/modules/bixbykey_hijack
[ -f $M/hijack_daemon.sh.v7.bak ] || { echo "没有 v7 备份"; exit 1; }
cp $M/hijack_daemon.sh.v7.bak $M/hijack_daemon.sh && chmod 755 $M/hijack_daemon.sh && sh $M/restart_hd.sh
