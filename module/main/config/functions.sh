#!/system/bin/sh
# ========================================
# 模块函数库（不是配置）
#
# 这里的函数与 settings.conf 里的用户设置分开存放：
# 升级模块时整个文件被新包替换，用户那份 settings.conf 原样保留，
# 所以改函数不用再顺带碰用户的配置。反过来，任何一项可以调的值
# 都不该写在这里，要写进 settings.conf。
#
# 调用方：先 source settings.conf，再 source 本文件。顺序不能反——
# 旧版 settings.conf 里残留着同名旧函数，后加载的这份才会覆盖掉它。
# ========================================

get_time() {
    date "${TIME_FORMAT}"
}

update_desc() {
    [ -f "${MODDIR}/module.prop" ] || return 0
    sed -i "s/^description=.*/description=$1/" "${MODDIR}/module.prop" 2>/dev/null
}

# 描述唯一出口：时间 | 状态: xxx | 日志: NKB（$2 日志KB、$3 附加段可省）
set_desc() {
    _sd="$(get_time) | 状态: $1"
    [ -n "${3:-}" ] && _sd="${_sd} | $3"
    [ -n "${2:-}" ] && _sd="${_sd} | 日志: ${2}KB"
    update_desc "${_sd}"
}
