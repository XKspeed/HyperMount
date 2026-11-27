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
#    但探活只做第一次：成功后落 scripts/.work/ctl.ok 标记，之后直接 exec
#    一次状态刷新要调这里好几回，每回都起一个 --version 纯属白耗
# ============================================================

# 定位模块根：$0 是绝对路径就直接切字符串，不用再为这层 cd 起子 shell
case "$0" in
    /*/*) SELF_DIR="${0%/*}"; MODDIR="${SELF_DIR%/*}" ;;
    # 相对路径只有手动调试才会用到，这里才花一个子 shell 归一化
    */*)  SELF_DIR=$(cd "${0%/*}" 2>/dev/null && pwd) || SELF_DIR="${0%/*}"
          MODDIR="${SELF_DIR%/*}" ;;
    *)    SELF_DIR=$(pwd); MODDIR="${SELF_DIR%/*}" ;;
esac

CTL="${MODDIR}/bin/ctl"
MARK="${MODDIR}/scripts/.work/ctl.ok"

if [ -x "$CTL" ]; then
    # 标记不比 ctl 旧 = 探活过、二进制也没被换掉，直接用
    if [ -f "$MARK" ] && [ ! "$CTL" -nt "$MARK" ]; then
        exec "$CTL" --dir "$MODDIR" "$@"
    fi
    if "$CTL" --version >/dev/null 2>&1; then
        mkdir -p "${MODDIR}/scripts/.work" 2>/dev/null
        : > "$MARK" 2>/dev/null
        exec "$CTL" --dir "$MODDIR" "$@"
    fi
fi

exec sh "${SELF_DIR}/webctl-shell.sh" "$@"
