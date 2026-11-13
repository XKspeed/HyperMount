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
if [ -f "${MODPATH}/update.md" ]; then
    CUR_VER=$(grep '^version=' "${MODPATH}/module.prop" 2>/dev/null | head -1 | cut -d= -f2 | tr -d ' \r')
    CUR_NUM="${CUR_VER#v}"
    ui_print "--------------- 更新说明 v${CUR_NUM} ---------------"
    if [ -n "${CUR_NUM}" ] && grep -qE "^#+[[:space:]]*v${CUR_NUM}([[:space:]]|$)" "${MODPATH}/update.md" 2>/dev/null; then
        awk -v ver="v${CUR_NUM}" '
            BEGIN { on=0 }
            /^#+[[:space:]]*v[0-9]+\.[0-9]+\.[0-9]+/ {
                if (on) exit
                if (index($0, ver) > 0) on=1
            }
            on { print }
        ' "${MODPATH}/update.md" | strip_md | while IFS= read -r line; do
            ui_print "  $line"
        done
    else
        strip_md < "${MODPATH}/update.md" | while IFS= read -r line; do
            ui_print "  $line"
        done
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

ui_print "✅ 安装完成！"
ui_print ""
