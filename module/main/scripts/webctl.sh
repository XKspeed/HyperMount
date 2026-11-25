#!/system/bin/sh
# ============================================================
#  webctl.sh —— 转交壳
#
#  真正干活的是 bin/ctl（C 实现），这里只负责定位模块根目录
#  然后把参数原样 exec 过去：
#    - exec 会替换当前进程，stdout 管道和退出码原样继承
#      模块管理器 / App 里看到的输出一行不少，不是"转手就丢"
#
#  为什么还留这个文件：
#    应用端固定按 scripts/webctl.sh 这个路径调，文件名不能改
#    旧模块 + 新 App 的组合必然存在，那时 bin/ctl 可能不存在
#    落到 webctl-shell.sh 用 shell 版兜底，不至于整块功能消失
#
#  为什么探活而不是只看 -x 位：
#    架构不符、被 SELinux 拒绝执行时 -x 位照样是 1
#    真跑一次 --version 才说明它能起来
# ============================================================

case "$0" in
    */*) SELF_DIR="${0%/*}" ;;
    *)   SELF_DIR="." ;;
esac
SELF_DIR=$(cd "$SELF_DIR" 2>/dev/null && pwd) || SELF_DIR="."
MODDIR=$(cd "${SELF_DIR}/.." 2>/dev/null && pwd) || MODDIR="${SELF_DIR}/.."

CTL="${MODDIR}/bin/ctl"
if [ -x "$CTL" ] && "$CTL" --version >/dev/null 2>&1; then
    exec "$CTL" --dir "$MODDIR" "$@"
fi

exec sh "${SELF_DIR}/webctl-shell.sh" "$@"
