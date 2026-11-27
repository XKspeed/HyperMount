# 文案表：安装提示跟着系统语言走（此时还没有 settings.conf 的 UI_LANG）
. "${MODPATH}/config/i18n.sh"

SKIPMOUNT=false
PROPFILE=true
POSTFSDATA=false
LATESTARTSERVICE=true

# 覆盖安装时保留用户原有配置：模块配置里存着日志目录、运行模式、待测 apk 路径，
# 直接换成新包里的默认值等于把用户设过的东西全清了。
# 做法是以新配置为底，把旧配置里的 KEY=VALUE 逐条覆盖上去，新版本新增的键仍然生效。
# 从 module.prop 取一个键的值
# 以前是 grep | head | cut | tr 四个进程，换成一次 awk：安装时能省的 fork 就省
prop_val() {
    awk -v k="$2" 'index($0, k "=") == 1 {
        v = substr($0, length(k) + 2)
        gsub(/[ \t\r]/, "", v)
        print v
        exit
    }' "$1" 2>/dev/null
}

MODID=$(prop_val "${MODPATH}/module.prop" id)

# ------------------------------------------------------------
#  必须自带界面才能装
#
#  仓库里 system/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk 是个 0 字节占位，
#  用户要把自己的界面覆盖上去。装的时候还是 0 字节，就说明没放——挂载空目录上去
#  会直接把 SystemUI 顶掉，手机黑屏且进不去，所以宁可装不上。
#
#  覆盖安装时新包里的占位会把旧界面抹成 0 字节，先从已安装的模块里把真包捞回来，
#  否则每升一次级就得重新放一遍界面。
# ------------------------------------------------------------
UI_APK="${MODPATH}/system/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk"
OLD_UI_APK="/data/adb/modules/${MODID}/system/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk"
mkdir -p "${MODPATH}/system/system_ext/priv-app/MiuiSystemUI" 2>/dev/null
UI_SZ=$(stat -c%s "${UI_APK}" 2>/dev/null || echo 0)
KEPT_UI=0
if [ "${UI_SZ}" -eq 0 ] && [ -n "${MODID}" ] && [ -s "${OLD_UI_APK}" ]; then
    cp -f "${OLD_UI_APK}" "${UI_APK}" 2>/dev/null
    UI_SZ=$(stat -c%s "${UI_APK}" 2>/dev/null || echo 0)
    [ "${UI_SZ}" -gt 0 ] && KEPT_UI=1
fi

# 模块根目录这份是救砖包：界面崩了/黑屏时拿它把系统界面换回去。
# 它不在仓库里（用户自己放的），新包自然也不带，覆盖安装会把旧的那份一起丢掉，
# 所以和上面一样从已安装的模块里捞回来，否则救砖功能升级一次就废一次。
RESCUE_APK="${MODPATH}/MiuiSystemUI.apk"
OLD_RESCUE_APK="/data/adb/modules/${MODID}/MiuiSystemUI.apk"
KEPT_RESCUE=0
if [ -n "${MODID}" ] && [ -s "${OLD_RESCUE_APK}" ] && [ ! -s "${RESCUE_APK}" ]; then
    cp -f "${OLD_RESCUE_APK}" "${RESCUE_APK}" 2>/dev/null
    [ -s "${RESCUE_APK}" ] && KEPT_RESCUE=1
fi

# system.prop 是用户自己写的一份属性覆盖（改机型 / 指纹 / DPI 之类），
# 包里不带它，也不建 0 字节占位——没有这个文件时 Magisk 什么都不做。
# 覆盖安装会把模块换到新目录，旧目录那份跟着一起没，所以从已安装的模块里捞回来。
# 包里将来要是自己带了 system.prop，就以包里的为准，不覆盖。
SYS_PROP="${MODPATH}/system.prop"
OLD_SYS_PROP="/data/adb/modules/${MODID}/system.prop"
KEPT_SYS_PROP=0
if [ -n "${MODID}" ] && [ -s "${OLD_SYS_PROP}" ] && [ ! -e "${SYS_PROP}" ]; then
    cp -f "${OLD_SYS_PROP}" "${SYS_PROP}" 2>/dev/null
    if [ -s "${SYS_PROP}" ]; then
        chmod 644 "${SYS_PROP}" 2>/dev/null
        KEPT_SYS_PROP=1
    else
        rm -f "${SYS_PROP}" 2>/dev/null
    fi
fi

if [ "${UI_SZ}" -eq 0 ]; then
    ui_print ""
    ui_print "${LINE:-========================================}"
    ui_print "$(t inst_need_apk)"
    ui_print "${LINE:-========================================}"
    ui_print "$(t inst_need_apk_hint)"
    ui_print "  system/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk"
    ui_print ""
    abort "$(t inst_abort_need_apk)"
fi

OLD_CONF="/data/adb/modules/${MODID}/config/settings.conf"
NEW_CONF="${MODPATH}/config/settings.conf"
KEPT_CONF=0
if [ -n "${MODID}" ] && [ -f "${OLD_CONF}" ] && [ -f "${NEW_CONF}" ] && [ "${OLD_CONF}" != "${NEW_CONF}" ]; then
    awk -v oldfmt='TIME_FORMAT="+%Y年%m月%d日%H:%M:%S"' \
        -v oldfmt2='TIME_FORMAT="+%Y/%m/%d|%H/%M/%S"' '
        NR==FNR {
            if ($0 ~ /^[A-Z][A-Z0-9_]*=/) { split($0, a, "="); old[a[1]] = $0; order[++n] = a[1] }
            next
        }
        {
            if ($0 ~ /^[A-Z][A-Z0-9_]*=/) {
                split($0, a, "=")
                if (a[1] in old) {
                    # 时间格式的旧默认值（「年月日」版、斜杠版）统一换成新的横线冒号格式；
                    # 用户手改过的其他值照旧保留
                    if (a[1] == "TIME_FORMAT" && (old["TIME_FORMAT"] == oldfmt || old["TIME_FORMAT"] == oldfmt2)) {
                        print; used[a[1]] = 1; next
                    }
                    print old[a[1]]; used[a[1]] = 1; next
                }
            }
            print
        }
        END { for (i = 1; i <= n; i++) if (!(order[i] in used)) print old[order[i]] }
    ' "${OLD_CONF}" "${NEW_CONF}" > "${NEW_CONF}.merged" 2>/dev/null \
        && mv -f "${NEW_CONF}.merged" "${NEW_CONF}"
    KEPT_CONF=1
fi

[ -f "${MODPATH}/config/settings.conf" ] && . "${MODPATH}/config/settings.conf"
ui_print ""
ui_print "${LINE}"
ui_print "$(t inst_title)"
ui_print "${LINE}"
ui_print ""
ui_print "$(t inst_conf)"
[ "${KEPT_CONF}" = "1" ] && ui_print "$(t inst_kept_conf)"
[ "${KEPT_UI}" = "1" ] && ui_print "$(t inst_kept_ui)"
[ "${KEPT_RESCUE}" = "1" ] && ui_print "$(t inst_kept_rescue)"
[ "${KEPT_SYS_PROP}" = "1" ] && ui_print "$(t inst_kept_prop)"
ui_print ""
LM="${LOG_MODE:-0}"
case "${LM}" in
    0) ui_print "$(t inst_log_off)" ;;
    1) ui_print "$(t inst_log_only)" ;;
    2) ui_print "$(t inst_log_manual)" ;;
    3) ui_print "$(t inst_log_auto)" ;;
    *) ui_print "$(printf "$(t inst_log_adv)" "${LM}")" ;;
esac
case "${LM}" in
    [3-9]|[1-9][0-9]*) ui_print "$(printf "$(t inst_auto_on)" "${FATAL_THRESHOLD:-3}")" ;;
esac
ui_print "${LINE}"
ui_print ""
strip_md() {
    sed -e 's/^###[[:space:]]*\(.*\)$/【\1】/' \
        -e 's/^##[[:space:]]*//' \
        -e 's/^#\{1,6\}[[:space:]]*//' \
        -e 's/\*\*\([^*]*\)\*\*/\1/g' \
        -e 's/\*\([^*]*\)\*/\1/g' \
        -e 's/`\([^`]*\)`/\1/g' \
        -e 's/^-[[:space:]]/• /' \
        -e 's/^  -[[:space:]]/    ◦ /' \
        -e '/^[[:space:]]*$/d'
}
# 只取本版那一段。包里带着从第一版开始的全部历史（9KB 起步），以前找不到对应版本
# 就把整份打出来，装一次刷好几屏，用户根本看不到这次改了什么。
# 内测包版本是 x.y.z-debug.N，改动还在 ## latest 里，没有归档段。
md_section() {
    awk -v want="$1" '
        /^##[[:space:]]/ {
            if (on) exit
            t = $0
            sub(/^##[[:space:]]*/, "", t)
            split(t, a, /[[:space:]]+/)
            if (a[1] == "latest") on = (want == "latest")
            else on = (a[1] == "v" want || a[1] == want)
            next
        }
        on { print }
    ' "${MODPATH}/update.md"
}
if [ -f "${MODPATH}/update.md" ]; then
    CUR_VER=$(prop_val "${MODPATH}/module.prop" version)
    CUR_NUM="${CUR_VER#v}"
    ui_print "$(printf "$(t inst_changelog)" "${CUR_NUM}")"
    case "${CUR_NUM}" in
        *-debug*) SECTION="latest" ;;
        *)        SECTION="${CUR_NUM}" ;;
    esac
    BODY=$(md_section "${SECTION}" | grep -v '^[[:space:]]*$')
    # 只有分组标题（### 修复）没有条目的段也算空段
    if [ -n "${BODY}" ] && [ -n "$(printf '%s\n' "${BODY}" | grep -v '^[[:space:]]*#')" ]; then
        printf '%s\n' "${BODY}" | strip_md | while IFS= read -r line; do
            ui_print "  $line"
        done
    else
        ui_print "$(t inst_changelog_none)"
    fi
    ui_print "----------------------------------------"
    ui_print ""
    rm -f "${MODPATH}/update.md"
fi
# 模块根不能有 webroot：KernelSU / APatch 把它当 WebUI 目录，跟进去没有 index.html，
# 管理器里就多一个点开白屏的入口。早期为了兼容只会找 webroot 的旧 App 建过这个软链，
# 这里只删软链（真实目录不动），也不再建新的。
for _wr in "${MODPATH}/webroot" "/data/adb/modules/${MODID}/webroot"; do
    [ -L "${_wr}" ] && rm -f "${_wr}" 2>/dev/null
done

# 原生二进制：zip 里的权限万一没保住（某些解压工具会丢），这里补一次。
# watchd 是守护的唯一实现（没有 sh 兜底），执行位丢了这个功能就起不来；
# actiond / ctl 起不来还能回落到各自的 shell 实现。
if [ -d "${MODPATH}/bin" ]; then
    chmod 755 "${MODPATH}"/bin/* 2>/dev/null
    # 换过二进制就要重新探活：webctl.sh 靠这个标记跳过每次调用的 --version
    rm -f "${MODPATH}/scripts/.work/ctl.ok" "/data/adb/modules/${MODID}/scripts/.work/ctl.ok" 2>/dev/null
fi
[ -x "${MODPATH}/bin/watchd" ] || ui_print "$(t inst_no_watchd)"
[ -x "${MODPATH}/bin/actiond" ] || ui_print "$(t inst_no_actiond)"
[ -x "${MODPATH}/bin/ctl" ] || ui_print "$(t inst_no_ctl)"

# ------------------------------------------------------------
#  不重启就地生效（只换脚本，不动界面）
#
#  从管理器安装时 BOOTMODE=1，文件写进 modules_update，Magisk 要等下次开机
#  才合并到 modules；而 App 调的是 modules/<id>/scripts/webctl.sh。
#  结果就是模块显示已更新、功能还是旧的。
#
#  这里把能立刻用的部分（scripts / bin / config / module.prop / 根脚本）同步
#  到已安装目录，App 下一次调用就是新版本，一次 shell 都不用多跑。
#
#  界面 APK 和 system/ 一个字节都不碰：magic mount 是开机时建的，
#  运行期换文件会让挂载点和 inode 对不上，换界面仍然要整机重启。
#
#  两个必须守住的点：
#   - bin/ctl 可能正被 App 调用（1.5 秒一次），直接 cp 覆盖会 EBUSY，
#     所以一律先写临时文件再 mv 替换目录项。
#   - 不能从 modules_update 删任何东西：Magisk 开机合并是整目录替换，
#     删掉的文件重启后就真没了。
# ------------------------------------------------------------
LIVE="/data/adb/modules/${MODID}"
HOTSYNC=0
if [ -n "${MODID}" ] && [ -d "${LIVE}" ] && [ "${MODPATH}" != "${LIVE}" ]; then
    # 单文件替换：临时文件 + rename，避开「目标正在执行」
    _put() {
        [ -f "$1" ] || return 1
        _tmp="$2.new.$$"
        cp -f "$1" "$_tmp" 2>/dev/null || { rm -f "$_tmp"; return 1; }
        _m=$(stat -c%a "$1" 2>/dev/null)
        [ -n "${_m}" ] && chmod "${_m}" "$_tmp" 2>/dev/null
        mv -f "$_tmp" "$2" 2>/dev/null || { rm -f "$_tmp"; return 1; }
        return 0
    }
    _sync_dir() {
        [ -d "${MODPATH}/$1" ] || return 0
        ( cd "${MODPATH}/$1" && find . -type f 2>/dev/null ) | while IFS= read -r _r; do
            _r="${_r#./}"
            [ -n "${_r}" ] || continue
            mkdir -p "${LIVE}/$1/$(dirname "${_r}")" 2>/dev/null
            _put "${MODPATH}/$1/${_r}" "${LIVE}/$1/${_r}"
        done
    }
    _sync_dir scripts
    _sync_dir bin
    _sync_dir config
    for _f in module.prop action.sh action-shell.sh service.sh service-shell.sh uninstall.sh; do
        _put "${MODPATH}/${_f}" "${LIVE}/${_f}"
    done
    # 同步完再确认一次执行位：zip 解压工具偶尔会丢
    [ -d "${LIVE}/bin" ] && chmod 755 "${LIVE}"/bin/* 2>/dev/null
    HOTSYNC=1
fi

ui_print "$(t inst_done)"
if [ "${HOTSYNC}" = "1" ]; then
    ui_print ""
    ui_print "$(t inst_done_live)"
    ui_print "$(t inst_done_ui)"
fi
ui_print ""
