#!/system/bin/sh
case "$0" in
    */*) WEB_DIR="${0%/*}" ;;
    *)   WEB_DIR="." ;;
esac
WEB_DIR=$(cd "$WEB_DIR" && pwd)
MODDIR=$(cd "${WEB_DIR}/.." && pwd)
CONFIG_DIR="${MODDIR}/config"
CONF="${CONFIG_DIR}/settings.conf"
WORK_DIR="${WEB_DIR}/.work"
[ -f "$CONF" ] && . "$CONF" 2>/dev/null
ACTION_PID="${WORK_DIR}/action.pid"
ACTION_LOG="${WORK_DIR}/action.out"
WORKER_PID="${WORK_DIR}/worker.pid"
WORKER_LOG="${WORK_DIR}/worker.out"
RESCUE_APK="${MODDIR}/MiuiSystemUI.apk"
AIM_APK="${MODDIR}/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk"
ensure_work() {
    [ -d "$WORK_DIR" ] || mkdir -p "$WORK_DIR" 2>/dev/null
}
case "${1:-}" in
    ls)
        DIR="${2:-/storage/emulated/0}"
        [ -d "$DIR" ] || { echo "FAIL 目录不存在"; exit 1; }
        ls -1 "$DIR" 2>/dev/null | while IFS= read -r n; do
            [ -z "$n" ] && continue
            fp="$DIR/$n"
            if [ -d "$fp" ]; then
                echo "$n|d|0"
            else
                case "$n" in
                    *.apk|*.APK)
                        sz=$(stat -c%s "$fp" 2>/dev/null || echo 0)
                        echo "$n|a|$sz"
                        ;;
                esac
            fi
        done
        ;;
    prepare)
        SRC="${2:-}"
        [ -z "$SRC" ] && { echo "FAIL 缺少 APK 路径"; exit 1; }
        [ ! -f "$SRC" ] && { echo "FAIL 文件不存在: $SRC"; exit 1; }
        case "$SRC" in
            *.apk|*.APK) ;;
            *) echo "FAIL 不是 APK: $SRC"; exit 1 ;;
        esac
        if ! unzip -l "$SRC" >/dev/null 2>&1; then
            echo "FAIL APK 已损坏"
            exit 1
        fi
        SZ=$(stat -c%s "$SRC" 2>/dev/null || echo 0)
        cp -f "$SRC" "$RESCUE_APK" || { echo "FAIL 复制失败"; exit 1; }
        chmod 644 "$RESCUE_APK" 2>/dev/null
        echo "OK"
        echo "救砖文件已就绪: $(basename "$SRC") ($SZ 字节)"
        ;;
    test-run)
        RESCUE="${2:-}"
        TARGET="${3:-}"
        [ -z "$RESCUE" ] && { echo "FAIL 缺少救砖文件"; exit 1; }
        [ -z "$TARGET" ] && { echo "FAIL 缺少待测文件"; exit 1; }
        for f in "$RESCUE" "$TARGET"; do
            [ ! -f "$f" ] && { echo "FAIL 文件不存在: $f"; exit 1; }
            case "$f" in
                *.apk|*.APK) ;;
                *) echo "FAIL 不是 APK: $f"; exit 1 ;;
            esac
        done
        ensure_work
        _ap=$(cat "$ACTION_PID" 2>/dev/null)
        if [ -n "$_ap" ] && kill -0 "$_ap" 2>/dev/null; then
            echo "FAIL 已有救砖守护在运行 (PID $_ap)"
            echo "如需重来请先执行 stop"
            exit 1
        fi
        rm -f "$ACTION_PID"
        if [ ! -f "$RESCUE_APK" ]; then
            cp -f "$RESCUE" "$RESCUE_APK" 2>/dev/null || {
                echo "FAIL 救砖文件未就绪且复制失败"
                exit 1
            }
            chmod 644 "$RESCUE_APK" 2>/dev/null
            echo "STEP 1/2 救砖文件已复制"
        else
            echo "STEP 1/2 救砖文件已就绪"
        fi
        SYS_SH=/system/bin/sh
        [ -x "$SYS_SH" ] || SYS_SH=sh
        : > "$ACTION_LOG"
        nohup "$SYS_SH" "${MODDIR}/action.sh" --test "$TARGET" \
            > "$ACTION_LOG" 2>&1 < /dev/null &
        APID=$!
        echo "$APID" > "$ACTION_PID"
        sleep 1
        if ! kill -0 "$APID" 2>/dev/null; then
            echo "FAIL action.sh 启动失败"
            tail -10 "$ACTION_LOG" 2>/dev/null
            rm -f "$ACTION_PID"
            exit 1
        fi
        echo "STEP 2/2 action 已启动 (PID $APID)"
        echo "OK 测试已启动"
        ;;
    status)
        if [ -f "$ACTION_PID" ]; then
            _p=$(cat "$ACTION_PID" 2>/dev/null)
            if [ -n "$_p" ] && kill -0 "$_p" 2>/dev/null; then
                echo "running=$_p"
            else
                echo "running="
                rm -f "$ACTION_PID"
            fi
        else
            echo "running="
        fi
        _tp=$(grep '^TARGET_APK_PATH=' "$CONF" 2>/dev/null | cut -d= -f2-)
        _files="$RESCUE_APK $AIM_APK"
        [ -n "$_tp" ] && [ -f "$_tp" ] && _files="$_files $_tp"
        _sizes=$(stat -c '%n %s' $_files 2>/dev/null)
        _sz() {
            printf '%s\n' "$_sizes" | awk -v f="$1" '$1 == f { print $2; exit }'
        }
        echo "rescue=$(_sz "$RESCUE_APK" || echo 0)"
        echo "target=$(_sz "$AIM_APK" || echo 0)"
        if [ -n "$_tp" ] && [ -f "$_tp" ]; then
            echo "targetok=1"
            echo "targetsize=$(_sz "$_tp" || echo 0)"
            echo "targettime=$(stat -c%y "$_tp" 2>/dev/null | cut -d. -f1 || echo '')"
        else
            echo "targetok=0"
            echo "targetsize=0"
            echo "targettime="
        fi
        ;;
    output)
        if [ -f "$ACTION_LOG" ]; then
            tail -50 "$ACTION_LOG" 2>/dev/null
        fi
        ;;
    stop)
        ST=0
        if [ -f "$ACTION_PID" ]; then
            _ap=$(cat "$ACTION_PID" 2>/dev/null)
            if [ -n "$_ap" ] && kill -0 "$_ap" 2>/dev/null; then
                kill "$_ap" 2>/dev/null
                sleep 0.3
                kill -9 "$_ap" 2>/dev/null
                ST=1
            fi
            rm -f "$ACTION_PID"
        fi
        pkill -f "logcat -b crash" 2>/dev/null
        rm -f "${WORK_DIR}/action.ready" 2>/dev/null
        [ "$ST" = "1" ] && echo "已停止" || echo "未在运行"
        ;;
    rescue)
        ACTION="${2:-get}"
        case "$ACTION" in
            get)
                V=$(dumpsys activity settings 2>/dev/null | grep -oE 'process_crash_count_limit=[0-9]+' | cut -d= -f2 | head -1)
                echo "limit=${V:-unknown}"
                if [ "${V:-0}" -gt 100 ] 2>/dev/null; then
                    echo "state=off"
                else
                    echo "state=on"
                fi
                ;;
            off)
                settings put global activity_manager_constants \
                    "process_crash_count_limit=999999,min_crash_interval=120000,process_crash_count_reset_interval=43200000" 2>/dev/null
                echo "OK 已屏蔽系统救援"
                ;;
            on)
                settings put global activity_manager_constants \
                    "process_crash_count_limit=12,min_crash_interval=120000,process_crash_count_reset_interval=43200000" 2>/dev/null
                echo "OK 已恢复系统救援"
                ;;
            *)
                echo "FAIL 未知操作: $ACTION"
                exit 1
                ;;
        esac
        ;;
    boot)
        echo "##VERSION"
        echo "version=$(grep '^version=' "${MODDIR}/module.prop" 2>/dev/null | cut -d= -f2)"
        echo "##PID"
        echo "pid=$(pidof com.android.systemui 2>/dev/null | awk '{print $1}')"
        echo "##STATUS"
        if [ -f "$ACTION_PID" ]; then
            _p=$(cat "$ACTION_PID" 2>/dev/null)
            if [ -n "$_p" ] && kill -0 "$_p" 2>/dev/null; then
                echo "running=$_p"
            else
                echo "running="
                rm -f "$ACTION_PID" 2>/dev/null
            fi
        else
            echo "running="
        fi
        _tp=$(grep '^TARGET_APK_PATH=' "$CONF" 2>/dev/null | cut -d= -f2-)
        _sizes=$(stat -c '%n %s' "$RESCUE_APK" "$AIM_APK" $_tp 2>/dev/null)
        _sz() {
            printf '%s\n' "$_sizes" | awk -v f="$1" '$1 == f { print $2; exit }'
        }
        echo "rescue=$(_sz "$RESCUE_APK")"
        echo "target=$(_sz "$AIM_APK")"
        if [ -n "$_tp" ] && [ -f "$_tp" ]; then
            echo "targetok=1"
            echo "targetsize=$(_sz "$_tp")"
            echo "targettime=$(stat -c%y "$_tp" 2>/dev/null | cut -d. -f1)"
        else
            echo "targetok=0"
            echo "targetsize=0"
            echo "targettime="
        fi
        echo "##RESCUE"
        _v=$(dumpsys activity settings 2>/dev/null | grep -oE 'process_crash_count_limit=[0-9]+' | cut -d= -f2 | head -1)
        echo "limit=${_v:-unknown}"
        if [ "${_v:-0}" -gt 100 ] 2>/dev/null; then
            echo "state=off"
        else
            echo "state=on"
        fi
        echo "##ENV"
        echo "android_ver=$(getprop ro.build.version.release 2>/dev/null)"
        echo "android_sdk=$(getprop ro.build.version.sdk 2>/dev/null)"
        echo "os_name=$(getprop ro.mi.os.version.name 2>/dev/null || getprop ro.miui.ui.version.name 2>/dev/null)"
        echo "device=$(getprop ro.product.device 2>/dev/null)"
        echo "model=$(getprop ro.product.model 2>/dev/null)"
        echo "build=$(getprop ro.build.version.incremental 2>/dev/null)"
        echo "saved_android=${CHECK_ANDROID_VER:-}"
        echo "saved_os=${CHECK_OS_NAME:-}"
        echo "saved_assess=${VOLUME_CHECK_ASSESS:-0}"
        echo "saved_time=${CHECK_TIME:-}"
        echo "##KEYS"
        if [ -n "${VOL_UP_DEV}" ] || [ -n "${VOL_DOWN_DEV}" ]; then
            echo "up=${VOL_UP_DEV}"
            echo "down=${VOL_DOWN_DEV}"
        else
            _ku=""; _kd=""
            _hasb() {
                _bm="$1"; _bit="$2"
                _maxd=0; _n=0
                for _w in $_bm; do
                    _n=$((_n + 1))
                    [ ${#_w} -gt "$_maxd" ] && _maxd=${#_w}
                done
                [ "$_n" -eq 0 ] && return 1
                if [ "$_maxd" -gt 8 ]; then _wl=16; _need=12; else _wl=8; _need=24; fi
                _body=""; _cnt=0
                for _w in $_bm; do
                    while [ ${#_w} -lt "$_wl" ]; do _w="0$_w"; done
                    _body="$_body$_w"
                    _cnt=$((_cnt + 1))
                done
                _z=""
                _i=0
                while [ "$_i" -lt "$_wl" ]; do _z="0$_z"; _i=$((_i + 1)); done
                while [ "$_cnt" -lt "$_need" ]; do _body="$_z$_body"; _cnt=$((_cnt + 1)); done
                _len=${#_body}
                _idx=$((_len - 1 - _bit / 4))
                [ "$_idx" -lt 0 ] && return 1
                _rest="$_body"; _i=0
                while [ "$_i" -lt "$_idx" ]; do _rest="${_rest#?}"; _i=$((_i + 1)); done
                _d="${_rest%${_rest#?}}"
                _b=$((_bit % 4))
                case "$_b:$_d" in
                    0:[13579bdfBDF]) return 0 ;;
                    1:[2367abefABEF]) return 0 ;;
                    2:[4567cdefCDEF]) return 0 ;;
                    3:[89abcdefABCDEF]) return 0 ;;
                esac
                return 1
            }
            for _d in /sys/class/input/event*; do
                [ -e "$_d" ] || continue
                _kb=$(cat "$_d/device/capabilities/key" 2>/dev/null)
                [ -z "$_kb" ] && continue
                _ev="/dev/input/${_d##*/}"
                _hasb "$_kb" 115 && _ku="$_ku $_ev"
                _hasb "$_kb" 114 && _kd="$_kd $_ev"
            done
            echo "up=${_ku# }"
            echo "down=${_kd# }"
        fi
        ;;
    *)
        echo "用法: sh webctl.sh ls|keys|test|check|prepare|test-run|status|output|stop|rescue|boot"
        exit 1
        ;;
esac
