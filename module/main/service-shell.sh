#!/system/bin/sh
MODDIR=${0%/*}
CONFIG_DIR="${MODDIR}/config"
if [ ! -f "${CONFIG_DIR}/settings.conf" ]; then
    exit 0
fi
. "${CONFIG_DIR}/settings.conf" 2>/dev/null || exit 0
[ -f "${CONFIG_DIR}/functions.sh" ] && . "${CONFIG_DIR}/functions.sh"

# 老配置里没有这两项：开机日志默认开（等同于以前「有日志就记」），开机自启默认关
BOOT_LOG=${BOOT_LOG:-1}
BOOT_WATCH=${BOOT_WATCH:-0}

# 开机日志：运行模式禁用或没选日志目录都不起（log.sh 里还会再判一次）
if [ "${BOOT_LOG}" = "1" ] && [ "${LOG_MODE:-0}" != "0" ] && [ -n "${LOG_DIR}" ]; then
    set_desc "等待开机完成"
    chmod 755 "${CONFIG_DIR}/log.sh" "${MODDIR}/action.sh" 2>/dev/null
    nohup sh "${CONFIG_DIR}/log.sh" >/dev/null 2>&1 &
else
    set_desc "未记录开机日志"
fi

# 开机启动全局救砖：等开机稳定再起，避开开机那几次正常重启
# 运行模式不到「日志 + 救砖」或没选日志目录时 actiond 起来也会直接退出，这里先拦一道
if [ "${BOOT_WATCH}" = "1" ] && [ "${LOG_MODE:-0}" -ge 2 ] 2>/dev/null && [ -n "${LOG_DIR}" ]; then
    (
        _w=0
        while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
            sleep 5
            _w=$((_w + 5))
            [ "${_w}" -ge 600 ] && exit 0
        done
        # 再等一会儿：开机后 SystemUI 自己会重启一两次，太早起会被算进崩溃窗口
        sleep 15
        sh "${MODDIR}/scripts/webctl.sh" watch start 0 >/dev/null 2>&1
    ) &
fi
exit 0
