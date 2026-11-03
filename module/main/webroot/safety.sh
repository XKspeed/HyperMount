#!/system/bin/sh
case "$0" in
    */*) WEB_DIR="${0%/*}" ;;
    *)   WEB_DIR="." ;;
esac
WEB_DIR=$(cd "$WEB_DIR" && pwd)
MODDIR=$(cd "${WEB_DIR}/.." && pwd)
WORK_DIR="${WEB_DIR}/.work"
RESCUE_APK="${MODDIR}/MiuiSystemUI.apk"
AIM_APK="${MODDIR}/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk"
SAFE_PID="${WORK_DIR}/safety.pid"
SAFE_LOG="${WORK_DIR}/safety.out"
log() { echo "$(date +%H:%M:%S) $1" >> "$SAFE_LOG"; }
case "${1:-}" in
    arm)
        DELAY="${2:-30}"
        mkdir -p "$WORK_DIR" 2>/dev/null
        if [ -f "$SAFE_PID" ]; then
            _old=$(cat "$SAFE_PID" 2>/dev/null)
            [ -n "$_old" ] && kill "$_old" 2>/dev/null
            rm -f "$SAFE_PID"
        fi
        if [ ! -f "$RESCUE_APK" ]; then
            echo "FAIL 模块根目录没有 MiuiSystemUI.apk"
            exit 1
        fi
        : > "$SAFE_LOG"
        log "保底已启动，${DELAY} 秒后恢复"
        (
            sleep "$DELAY"
            log "时间到，开始恢复"
            _sz=$(stat -c%s "$RESCUE_APK" 2>/dev/null || echo 0)
            if [ "$_sz" -le 0 ]; then
                log "FAIL 救砖文件无效"
                exit 0
            fi
            mkdir -p "$(dirname "$AIM_APK")" 2>/dev/null
            _old=$([ -f "$AIM_APK" ] && stat -c%s "$AIM_APK" 2>/dev/null || echo 0)
            if [ "$_sz" = "$_old" ] && [ "$_sz" -gt 0 ]; then
                dd if="$RESCUE_APK" of="$AIM_APK" bs=1M conv=notrunc 2>/dev/null
            else
                cp -f "$RESCUE_APK" "$AIM_APK" 2>/dev/null
            fi
            _now=$(stat -c%s "$AIM_APK" 2>/dev/null || echo 0)
            chmod 644 "$AIM_APK" 2>/dev/null
            if [ "$_now" = "$_sz" ]; then
                log "恢复完成 (${_old} -> ${_now})"
            else
                log "FAIL 恢复异常（期望 ${_sz}，实际 ${_now}）"
            fi
            rm -f "$SAFE_PID" 2>/dev/null
        ) < /dev/null > /dev/null 2>&1 &
        echo $! > "$SAFE_PID"
        echo "OK 保底已启动 (${DELAY} 秒，PID $(cat "$SAFE_PID"))"
        ;;
    disarm)
        if [ -f "$SAFE_PID" ]; then
            _p=$(cat "$SAFE_PID" 2>/dev/null)
            [ -n "$_p" ] && kill "$_p" 2>/dev/null
            rm -f "$SAFE_PID"
            log "保底已取消"
            echo "OK 已取消保底"
        else
            echo "未启动"
        fi
        ;;
    status)
        if [ -f "$SAFE_PID" ]; then
            _p=$(cat "$SAFE_PID" 2>/dev/null)
            if [ -n "$_p" ] && kill -0 "$_p" 2>/dev/null; then
                echo "armed=$_p"
            else
                echo "armed="
            fi
        else
            echo "armed="
        fi
        if [ -f "$SAFE_LOG" ]; then
            echo "--- 日志 ---"
            cat "$SAFE_LOG"
        fi
        ;;
    *)
        echo "用法: sh safety.sh arm [秒数] | disarm | status"
        exit 1
        ;;
esac
