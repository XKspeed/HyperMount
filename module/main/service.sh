#!/system/bin/sh
MODDIR=${0%/*}
CONFIG_DIR="${MODDIR}/config"
if [ ! -f "${CONFIG_DIR}/settings.conf" ]; then
    exit 0
fi
. "${CONFIG_DIR}/settings.conf" 2>/dev/null || exit 0
[ -f "${CONFIG_DIR}/functions.sh" ] && . "${CONFIG_DIR}/functions.sh"
if [ "${LOG_MODE:-0}" = "0" ]; then
    set_desc "日志已禁用"
    exit 0
fi
set_desc "等待开机完成"
chmod 755 "${CONFIG_DIR}/log.sh" "${MODDIR}/action.sh" 2>/dev/null
nohup sh "${CONFIG_DIR}/log.sh" >/dev/null 2>&1 &
exit 0
