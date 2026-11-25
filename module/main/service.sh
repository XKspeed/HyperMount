#!/system/bin/sh
# ============================================================
#  service.sh —— 开机入口，转交壳
#
#  真正的逻辑在 bin/ctl boot-start：
#    起开机日志、按需拉起全局救砖的等待器，然后立刻返回
#    （Magisk 会等 service.sh 结束，所以不能在壳里 sleep）
#
#  为什么探活而不是只看 -x 位：
#    架构不符、被 SELinux 拒绝执行时 -x 位照样是 1
#    真跑一次 --version 才说明它能起来
#
#  bin/ctl 起不来时落到 service-shell.sh，功能不整块消失
# ============================================================

MODDIR=${0%/*}
CTL="${MODDIR}/bin/ctl"

if [ -x "$CTL" ] && "$CTL" --version >/dev/null 2>&1; then
    exec "$CTL" --dir "$MODDIR" boot-start
fi

exec sh "${MODDIR}/service-shell.sh"
