#!/system/bin/sh
MODDIR=${0%/*}
CONFIG_DIR="${MODDIR}/config"
if [ ! -f "${CONFIG_DIR}/settings.conf" ]; then
    exit 0
fi
. "${CONFIG_DIR}/settings.conf" 2>/dev/null || exit 0
if [ "${LOG_MODE:-0}" = "0" ]; then
    update_desc "开机时间: $(get_time) | 状态: 日志已禁用"
    exit 0
fi
update_desc "开机时间: $(get_time) | 状态: 等待开机完成"
chmod 755 "${CONFIG_DIR}/log.sh" "${MODDIR}/action.sh" 2>/dev/null
nohup sh "${CONFIG_DIR}/log.sh" >/dev/null 2>&1 &
exit 0
