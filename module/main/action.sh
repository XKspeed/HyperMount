#!/system/bin/sh
# 模块管理器的「操作」按钮（KernelSU / Magisk）只认模块根的 action.sh，
# 真正干活的是 bin/actiond（C 版）。这里只做转交，不含业务逻辑。
#
# 先跑一次 --version 探活：架构不符、没执行位、被 SELinux 挡住这三种情况
# 光看 -x 是判不出来的，真 exec 失败又是直接退出，兜不回来。
# 探通了才 exec（那个进程就换成 actiond，不留多余的 sh）。
# 探不通退回 action-shell.sh —— 原来的完整 shell 实现，功能一致只是慢一些。

case "$0" in
    */*) MODDIR="${0%/*}" ;;
    *)   MODDIR="." ;;
esac
MODDIR=$(cd "$MODDIR" && pwd)

ACTIOND="${MODDIR}/bin/actiond"
if [ -x "$ACTIOND" ] && "$ACTIOND" --version >/dev/null 2>&1; then
    exec "$ACTIOND" --dir "$MODDIR" "$@"
fi

SYS_SH=/system/bin/sh
[ -x "$SYS_SH" ] || SYS_SH=sh
exec "$SYS_SH" "${MODDIR}/action-shell.sh" "$@"
