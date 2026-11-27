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

# 文案表（t / tl）：函数库依赖它，先装好
. "${MODDIR}/config/i18n.sh"

get_time() {
    date "${TIME_FORMAT}"
}

update_desc() {
    [ -f "${MODDIR}/module.prop" ] || return 0
    # 分隔符用 #：描述里的时间格式带 -（2026-11-27），拿 - 当分隔符会直接坏掉
    # 值里的 # 与 & 先转义，免得 sed 认成语法
    _ud_v=$(printf '%s' "$1" | sed 's/[#&]/\\&/g')
    sed -i "s#^description=.*#description=${_ud_v}#" "${MODDIR}/module.prop" 2>/dev/null
}

# 描述唯一出口：时间 | 状态: xxx | 日志: NKB
#   $1 文案 key（不是中文原文，见 language/*.json）
#   $2 日志 KB，可省
#   $3 附加段（已翻好的串），可省
set_desc() {
    _sd_ts=$(get_time)
    _sd_st=$(t "$1")
    if [ -n "${3:-}" ]; then
        if [ -n "${2:-}" ]; then
            _sd=$(printf "$(t desc_ts_state_extra_kb)" "${_sd_ts}" "${_sd_st}" "$3" "$2")
        else
            _sd=$(printf "$(t desc_ts_state_extra)" "${_sd_ts}" "${_sd_st}" "$3")
        fi
    elif [ -n "${2:-}" ]; then
        _sd=$(printf "$(t desc_ts_state_kb)" "${_sd_ts}" "${_sd_st}" "$2")
    else
        _sd=$(printf "$(t desc_ts_state)" "${_sd_ts}" "${_sd_st}")
    fi
    update_desc "${_sd}"
}
