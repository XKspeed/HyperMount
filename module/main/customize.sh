SKIPMOUNT=false
PROPFILE=true
POSTFSDATA=false
LATESTARTSERVICE=true

# 覆盖安装时保留用户原有配置：模块配置里存着日志目录、运行模式、待测 apk 路径，
# 直接换成新包里的默认值等于把用户设过的东西全清了。
# 做法是以新配置为底，把旧配置里的 KEY=VALUE 逐条覆盖上去，新版本新增的键仍然生效。
MODID=$(grep '^id=' "${MODPATH}/module.prop" 2>/dev/null | head -1 | cut -d= -f2 | tr -d ' \r')
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
    sed -e 's/^###[[:space:]]*\{0,1\}\(.*\)/【\1】/' \
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
    ui_print "--------------- 更新说明 v${CUR_VER} ---------------"
    if [ -n "${CUR_VER}" ] && grep -qE "^#+[[:space:]]*v${CUR_VER}([[:space:]]|$)" "${MODPATH}/update.md" 2>/dev/null; then
        awk -v ver="v${CUR_VER}" '
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
ui_print "✅ 安装完成！"
ui_print ""
