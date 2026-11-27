#!/system/bin/sh
MODDIR=${0%/*}
MODDIR="${MODDIR%/config}"
CONFIG_DIR="${MODDIR}/config"
[ -f "${CONFIG_DIR}/settings.conf" ] || exit 0
. "${CONFIG_DIR}/settings.conf" 2>/dev/null || exit 0
[ -f "${CONFIG_DIR}/functions.sh" ] && . "${CONFIG_DIR}/functions.sh"
[ "${LOG_MODE:-0}" = "0" ] && exit 0
# 日志目录留空就当是不记日志 直接把描述改成「未设置」再退出
# 否则 service.sh 写下的「等待开机完成」会一直挂在模块卡片上
if [ -z "${LOG_DIR}" ]; then
    set_desc boot_no_logdir
    exit 0
fi
# 开机那几分钟没必要每秒问一次 getprop：5 秒一问 最多等 10 分钟
WAITED=0
while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
    sleep 5
    WAITED=$((WAITED+1))
    [ "${WAITED}" -ge 120 ] && exit 0
done
sleep 5
mkdir -p "${LOG_DIR}" 2>/dev/null
chmod 755 "${LOG_DIR}" 2>/dev/null
find "${LOG_DIR}" -maxdepth 1 -name "*.log" -type f -mtime "+${LOG_RETENTION_DAYS:-1}" -delete 2>/dev/null
logcat -c -b crash 2>/dev/null
set_desc boot_logging
LOG_FILE="${LOG_DIR}/$(get_time).log"
timeout "${LOG_TIMEOUT:-60}" logcat -b crash >> "${LOG_FILE}" 2>&1
[ -s "${LOG_FILE}" ] || rm -f "${LOG_FILE}" 2>/dev/null
# 开机自启的守护这会儿可能已经在跑了 别把模块卡片写成「日志已停止」
_wp=$(cat "${MODDIR}/scripts/.work/watch.pid" 2>/dev/null)
if [ -n "${_wp}" ] && kill -0 "${_wp}" 2>/dev/null; then
    set_desc boot_guarding
else
    set_desc boot_log_stopped
fi
exit 0
