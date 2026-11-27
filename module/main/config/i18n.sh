#!/system/bin/sh
# ========================================
# 模块文案表
#
# 文案放在 language/zh-cn.json / en-us.json，键值都是扁平的
# "key": "value"，不嵌套、不数组 —— 这里和 C 端（src/i18n.h）
# 各有一份极简读取器，形状越简单越不容易读歪。
#
# 为什么不用 sed 一条一条查：action.sh 的循环里每输出一行就 fork
# 一个 sed，光文案读取就比业务本身还贵。这里在启动时用 awk 一次性
# 把整张表导成 I18N_<key> 变量，之后 t 只做变量取值，零 fork。
#
# 语言判定与 C 端一致：
#   1. config/settings.conf 的 UI_LANG（0 跟随系统 / 1 中文 / 2 English）
#   2. 没有这个键就按系统语言，zh 开头算中文
#
# 用法：. config/i18n.sh 之后 t <key> 取文案（不换行）。
# ========================================

# 模块根目录：运行期是 MODDIR，安装期是 MODPATH
_i18n_root() {
    if [ -n "${I18N_ROOT:-}" ]; then
        printf '%s' "${I18N_ROOT}"
    elif [ -n "${MODDIR:-}" ]; then
        printf '%s' "${MODDIR}"
    elif [ -n "${MODPATH:-}" ]; then
        printf '%s' "${MODPATH}"
    else
        printf '%s' "."
    fi
}

# settings.conf 里的 UI_LANG：空表示没写
_i18n_ui_lang() {
    _iu_f="$1/config/settings.conf"
    [ -f "${_iu_f}" ] || return 0
    awk '
        /^[ \t]*UI_LANG[ \t]*=/ {
            v = $0
            sub(/^[^=]*=/, "", v)
            gsub(/[" \t\r]/, "", v)
            print v
            exit
        }
    ' "${_iu_f}" 2>/dev/null
}

# 把 JSON 拍成 shell 赋值语句：pfx + key = "value"
_i18n_dump() {
    awk -v pfx="$2" '
        /^[ \t]*"[a-zA-Z0-9_]+"[ \t]*:/ {
            k = $0
            sub(/^[ \t]*"/, "", k)
            sub(/".*$/, "", k)

            v = $0
            sub(/^[^:]*:[ \t]*"/, "", v)
            sub(/",?[ \t\r]*$/, "", v)

            # 只用双引号包住值，所以把这几个字符转义掉；
            # 反斜杠保持原样（\n 要留给 printf 去解释）
            gsub(/"/, "\\\"", v)
            gsub(/\$/, "\\$", v)
            gsub(/`/, "\\`", v)

            printf "%s%s=\"%s\"\n", pfx, k, v
        }
    ' "$1"
}

_i18n_load() {
    [ -n "${_I18N_DONE:-}" ] && return 0
    _I18N_DONE=1

    _il_root=$(_i18n_root)
    _il_dir="${_il_root}/language"
    [ -d "${_il_dir}" ] || return 0
    [ -f "${_il_dir}/zh-cn.json" ] || return 0

    # 中文表永远先装上，当兜底：英文表漏了某个 key 就回中文，
    # 总比界面上蹦出个 cfg_bad_int 强
    eval "$(_i18n_dump "${_il_dir}/zh-cn.json" I18N_ZH_)"

    _il_ui=$(_i18n_ui_lang "${_il_root}")
    case "${_il_ui}" in
        1) _il_lang=zh-cn ;;
        2) _il_lang=en-us ;;
        *)
            _il_loc=$(getprop persist.sys.locale 2>/dev/null)
            [ -z "${_il_loc}" ] && _il_loc=$(getprop ro.product.locale 2>/dev/null)
            case "${_il_loc}" in
                zh*) _il_lang=zh-cn ;;
                "")  _il_lang=zh-cn ;;   # 读不到就按中文，别满屏英文
                *)   _il_lang=en-us ;;
            esac
            ;;
    esac

    # 中文时不用再装第二份，t 会自动回落到 I18N_ZH_
    if [ "${_il_lang}" != "zh-cn" ] && [ -f "${_il_dir}/${_il_lang}.json" ]; then
        eval "$(_i18n_dump "${_il_dir}/${_il_lang}.json" I18N_)"
    fi
}

# t <key>：取文案，不换行。缺 key 回中文，再缺回 key 本身
t() {
    [ -n "${_I18N_DONE:-}" ] || _i18n_load
    eval "_it=\"\${I18N_$1:-}\""
    if [ -z "${_it:-}" ]; then
        eval "_it=\"\${I18N_ZH_$1:-}\""
    fi
    # %b：把表里的 \n 解释成真换行。表里只用到这一种转义
    printf '%b' "${_it:-$1}"
    unset _it 2>/dev/null
}

# tl <key>：取一行文案并换行。等价于绝大多数 echo 场景，且不起子进程
tl() {
    t "$1"
    printf '\n'
}

_i18n_load
