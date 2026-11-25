#!/system/bin/sh
# ============================================================
#  check.sh —— 转交壳
#
#  真正干活的是 bin/ctl check（C 实现），这里只负责定位模块根目录
#  然后把参数原样 exec 过去：stdout 管道和退出码原样继承
#
#  为什么还留这个文件：
#    应用端固定按 scripts/check.sh 这个路径调，文件名不能改
#    旧模块 + 新 App 的组合必然存在，那时 bin/ctl 可能不存在
#    落到 check-shell.sh 用 shell 版兜底
# ============================================================

case "$0" in
    */*) SELF_DIR="${0%/*}" ;;
    *)   SELF_DIR="." ;;
esac
SELF_DIR=$(cd "$SELF_DIR" 2>/dev/null && pwd) || SELF_DIR="."
MODDIR=$(cd "${SELF_DIR}/.." 2>/dev/null && pwd) || MODDIR="${SELF_DIR}/.."

CTL="${MODDIR}/bin/ctl"
if [ -x "$CTL" ] && "$CTL" --version >/dev/null 2>&1; then
    exec "$CTL" --dir "$MODDIR" check "$@"
fi

exec sh "${SELF_DIR}/check-shell.sh" "$@"
