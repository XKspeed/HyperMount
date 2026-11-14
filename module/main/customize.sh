SKIPMOUNT=false
PROPFILE=true
POSTFSDATA=false
LATESTARTSERVICE=true

# 覆盖安装时保留用户原有配置：模块配置里存着日志目录、运行模式、待测 apk 路径，
# 直接换成新包里的默认值等于把用户设过的东西全清了。
# 做法是以新配置为底，把旧配置里的 KEY=VALUE 逐条覆盖上去，新版本新增的键仍然生效。
MODID=$(grep '^id=' "${MODPATH}/module.prop" 2>/dev/null | head -1 | cut -d= -f2 | tr -d ' \r')

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
    ui_print "  ✗ 压缩包里还没有界面"
    ui_print "${LINE:-========================================}"
    ui_print "  把 MiuiSystemUI.apk 换掉这个 0 字节文件后重刷："
    ui_print "  system/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk"
    ui_print ""
    abort "  没放界面不能安装：空目录挂上去会直接黑屏"
fi

OLD_CONF="/data/adb/modules/${MODID}/config/settings.conf"
NEW_CONF="${MODPATH}/config/settings.conf"
KEPT_CONF=0
if [ -n "${MODID}" ] && [ -f "${OLD_CONF}" ] && [ -f "${NEW_CONF}" ] && [ "${OLD_CONF}" != "${NEW_CONF}" ]; then
    awk '
        NR==FNR {
            if ($0 ~ /^[A-Z][A-Z0-9_]*=/) { split($0, a, "="); old[a[1]] = $0; order[++n] = a[1] }
            next
        }
        {
            if ($0 ~ /^[A-Z][A-Z0-9_]*=/) {
                split($0, a, "=")
                if (a[1] in old) { print old[a[1]]; used[a[1]] = 1; next }
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
ui_print "        救砖模块 安装信息"
ui_print "${LINE}"
ui_print ""
ui_print "  📁 配置文件: config/settings.conf"
[ "${KEPT_CONF}" = "1" ] && ui_print "  ♻️ 检测到已安装 沿用原有配置"
[ "${KEPT_UI}" = "1" ] && ui_print "  ♻️ 沿用已安装的界面"
[ "${KEPT_RESCUE}" = "1" ] && ui_print "  ♻️ 沿用已安装的救砖包"
[ "${KEPT_SYS_PROP}" = "1" ] && ui_print "  ♻️ 沿用已安装的 system.prop"
ui_print ""
LM="${LOG_MODE:-0}"
case "${LM}" in
    0) ui_print "  🔇 日志状态: 完全禁用" ;;
    1) ui_print "  📝 日志状态: 仅记录日志" ;;
    2) ui_print "  ✅ 日志状态: 日志 + 救砖（手动按键）" ;;
    3) ui_print "  🤖 日志状态: 日志 + 自动救砖" ;;
    *) ui_print "  🤖 日志状态: 高级模式 (${LM})" ;;
esac
case "${LM}" in
    [3-9]|[1-9][0-9]*) ui_print "  ⚡ 自动救砖已启用（阈值 ${FATAL_THRESHOLD:-3} 次）" ;;
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
    CUR_VER=$(grep '^version=' "${MODPATH}/module.prop" 2>/dev/null | head -1 | cut -d= -f2 | tr -d ' \r')
    CUR_NUM="${CUR_VER#v}"
    ui_print "--------------- 更新说明 v${CUR_NUM} ---------------"
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
        ui_print "  本版没有单独的更新说明"
    fi
    ui_print "----------------------------------------"
    ui_print ""
    rm -f "${MODPATH}/update.md"
fi
# 旧版 App 找的是 webroot/webctl.sh，新版是 scripts。这里留一个相对软链接，
# 让「先刷模块、后更新 App」这段窗口期里旧 App 也能正常跑；新 App 优先认 scripts，
# 不受影响。等确认没有旧版 App 在用了，这四行可以删。
if [ -d "${MODPATH}/scripts" ]; then
    rm -rf "${MODPATH}/webroot" 2>/dev/null
    ln -s scripts "${MODPATH}/webroot" 2>/dev/null
fi

# 原生二进制：zip 里的权限万一没保住（某些解压工具会丢），这里补一次。
# watchd 是守护的唯一实现（没有 sh 兜底），执行位丢了这个功能就起不来；
# actiond 起不来还能回落 action.sh。
if [ -d "${MODPATH}/bin" ]; then
    chmod 755 "${MODPATH}"/bin/* 2>/dev/null
fi
[ -x "${MODPATH}/bin/watchd" ] || ui_print "⚠️ 包里没有可执行的 bin/watchd，全局守护将不可用"
[ -x "${MODPATH}/bin/actiond" ] || ui_print "⚠️ 包里没有 bin/actiond，救砖将使用 shell 版"

ui_print "✅ 安装完成！"
ui_print ""
