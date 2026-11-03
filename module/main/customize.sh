SKIPMOUNT=false
PROPFILE=true
POSTFSDATA=false
LATESTARTSERVICE=true
[ -f "${MODPATH}/config/settings.conf" ] && . "${MODPATH}/config/settings.conf"
ui_print ""
ui_print "${LINE}"
ui_print "        救砖模块 安装信息"
ui_print "${LINE}"
ui_print ""
ui_print "  📁 配置文件: config/settings.conf"
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
