#!/system/bin/sh
MODDIR=${0%/*}
MODDIR="${MODDIR%/config}"
CONFIG_DIR="${MODDIR}/config"
[ -f "${CONFIG_DIR}/settings.conf" ] || exit 0
. "${CONFIG_DIR}/settings.conf" 2>/dev/null || exit 0
[ "${LOG_MODE:-0}" = "0" ] && exit 0
WAITED=0
while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
    sleep 1
    WAITED=$((WAITED+1))
    [ "${WAITED}" -ge 600 ] && exit 0   # 最多等10分钟
done
sleep 5
mkdir -p "${LOG_DIR}" 2>/dev/null
chmod 755 "${LOG_DIR}" 2>/dev/null
find "${LOG_DIR}" -maxdepth 1 -name "*.log" -type f -mtime "+${LOG_RETENTION_DAYS:-1}" -delete 2>/dev/null
logcat -c -b crash 2>/dev/null
update_desc "$(get_time) | 日志: 记录中"
LOG_FILE="${LOG_DIR}/$(get_time).log"
timeout "${LOG_TIMEOUT:-60}" logcat -b crash >> "${LOG_FILE}" 2>&1
update_desc "$(get_time) | 日志: 已停止"
[ -s "${LOG_FILE}" ] || rm -f "${LOG_FILE}" 2>/dev/null
exit 0
