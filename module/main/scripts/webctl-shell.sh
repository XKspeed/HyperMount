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
[ -f "${CONFIG_DIR}/functions.sh" ] && . "${CONFIG_DIR}/functions.sh"
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
        [ -d "$DIR" ] || { t fail_dir_missing; exit 1; }
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
        [ -z "$SRC" ] && { t fail_no_apk_path; exit 1; }
        [ ! -f "$SRC" ] && { printf "$(t fail_file_missing)\n" "$SRC"; exit 1; }
        case "$SRC" in
            *.apk|*.APK) ;;
            *) printf "$(t fail_not_apk)\n" "$SRC"; exit 1 ;;
        esac
        if ! unzip -l "$SRC" >/dev/null 2>&1; then
            t fail_apk_corrupt
            exit 1
        fi
        SZ=$(stat -c%s "$SRC" 2>/dev/null || echo 0)
        cp -f "$SRC" "$RESCUE_APK" || { t fail_copy; exit 1; }
        chmod 644 "$RESCUE_APK" 2>/dev/null
        echo "OK"
        printf "$(t prepare_ok)\n" "$(basename "$SRC")" "$SZ"
        ;;
    test-run)
        RESCUE="${2:-}"
        TARGET="${3:-}"
        [ -z "$RESCUE" ] && { t fail_no_rescue; exit 1; }
        [ -z "$TARGET" ] && { t fail_no_target; exit 1; }
        for f in "$RESCUE" "$TARGET"; do
            [ ! -f "$f" ] && { printf "$(t fail_file_missing)\n" "$f"; exit 1; }
            case "$f" in
                *.apk|*.APK) ;;
                *) printf "$(t fail_not_apk)\n" "$f"; exit 1 ;;
            esac
        done
        ensure_work
        _ap=$(cat "$ACTION_PID" 2>/dev/null)
        if [ -n "$_ap" ] && kill -0 "$_ap" 2>/dev/null; then
            printf "$(t fail_watch_running)\n" "$_ap"
            t fail_watch_running_hint
            exit 1
        fi
        rm -f "$ACTION_PID"
        if [ ! -f "$RESCUE_APK" ]; then
            cp -f "$RESCUE" "$RESCUE_APK" 2>/dev/null || {
                t fail_rescue_copy
                exit 1
            }
            chmod 644 "$RESCUE_APK" 2>/dev/null
            t step1_copied
        else
            t step1_ready
        fi
        SYS_SH=/system/bin/sh
        [ -x "$SYS_SH" ] || SYS_SH=sh
        : > "$ACTION_LOG"
        # 优先 C 版 actiond。它 exec 不了（架构不符 / 权限）时进程会立刻消失，
        # 那种情况才回落到 action.sh —— 光看 -x 判断不出来。
        _aw="${MODDIR}/bin/actiond"
        APID=0
        if [ -x "$_aw" ]; then
            nohup "$_aw" --dir "$MODDIR" --test "$TARGET" \
                > "$ACTION_LOG" 2>&1 < /dev/null &
            APID=$!
            echo "$APID" > "$ACTION_PID"
            sleep 1
            if ! kill -0 "$APID" 2>/dev/null \
               && ! grep -q "##HANDOFF" "$ACTION_LOG" 2>/dev/null; then
                APID=0
            fi
        fi
        if [ "$APID" = "0" ]; then
            t fallback_shell >> "$ACTION_LOG"
            nohup "$SYS_SH" "${MODDIR}/action-shell.sh" --test "$TARGET" \
                >> "$ACTION_LOG" 2>&1 < /dev/null &
            APID=$!
            echo "$APID" > "$ACTION_PID"
            sleep 1
        fi
        if ! kill -0 "$APID" 2>/dev/null; then
            # 守护在跑时只做替换就退出 这是正常收尾 不是启动失败
            if grep -q "##HANDOFF" "$ACTION_LOG" 2>/dev/null; then
                t step2_replaced_handoff
                t ok_test_started
                exit 0
            fi
            t fail_actiond_start
            tail -10 "$ACTION_LOG" 2>/dev/null
            rm -f "$ACTION_PID"
            exit 1
        fi
        printf "$(t step2_started)\n" "$APID"
        t ok_test_started
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
        # 只回救砖进程自己的 stdout：日志目录里的 *.log 是 logcat 抓的崩溃堆栈
        # 第二个参数是要多少行 不传按 50
        _n="${2:-50}"
        case "$_n" in ''|*[!0-9]*) _n=50 ;; esac
        [ "$_n" -gt 400 ] && _n=400
        if [ -f "$ACTION_LOG" ]; then
            tail -n "$_n" "$ACTION_LOG" 2>/dev/null
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
        [ "$ST" = "1" ] && tl state_stopped || tl state_not_running
        ;;
    rescue)
        ACTION="${2:-get}"
        # 上限同时存在两处：device_config 是 AMS 运行期真正读的，
        # global 里的 activity_manager_constants 只在开机时映射进去一次。
        # HyperOS 上改 global 常常不重读，只写 global 就是「拨上去又弹回」。
        _dc() {
            if command -v cmd >/dev/null 2>&1; then
                cmd device_config "$@" 2>/dev/null && return 0
            fi
            device_config "$@" 2>/dev/null
        }
        _limit() {
            _v=$(_dc get activity_manager process_crash_count_limit)
            case "$_v" in ''|null|*[!0-9]*) _v="" ;; esac
            if [ -z "$_v" ]; then
                _v=$(settings get global activity_manager_constants 2>/dev/null \
                    | tr ',' '\n' | sed -n 's/^ *process_crash_count_limit=\([0-9][0-9]*\).*/\1/p' | head -1)
            fi
            if [ -z "$_v" ]; then
                _v=$(dumpsys activity settings 2>/dev/null | grep -oE 'process_crash_count_limit=[0-9]+' | cut -d= -f2 | head -1)
            fi
            printf '%s' "$_v"
        }
        _apply() {
            settings put global activity_manager_constants \
                "process_crash_count_limit=$1,min_crash_interval=120000,process_crash_count_reset_interval=43200000" 2>/dev/null
            _dc put activity_manager process_crash_count_limit "$1" >/dev/null
            _dc put activity_manager min_crash_interval 120000 >/dev/null
            _dc put activity_manager process_crash_count_reset_interval 43200000 >/dev/null
            sleep 0.3
            _limit
        }
        case "$ACTION" in
            get)
                V=$(_limit)
                echo "limit=${V:-unknown}"
                if [ "${V:-0}" -gt 100 ] 2>/dev/null; then
                    echo "state=off"
                else
                    echo "state=on"
                fi
                ;;
            off)
                V=$(_apply 999999)
                if [ "${V:-0}" -gt 100 ] 2>/dev/null; then
                    printf "$(t ok_rescue_on)\n" "$V"
                else
                    printf "$(t fail_rescue_on_limit)\n" "${V:-0}"
                    exit 1
                fi
                ;;
            on)
                # 恢复值跟运行模式走：日志+救砖要留 30 才等得到人按音量键
                L="${3:-}"
                if [ -z "$L" ]; then
                    case "$(grep '^LOG_MODE=' "$CONF" 2>/dev/null | cut -d= -f2)" in
                        2|3) L=30 ;;
                        *)   L=12 ;;
                    esac
                fi
                V=$(_apply "$L")
                if [ -n "$V" ] && [ "$V" -le 100 ] 2>/dev/null; then
                    printf "$(t ok_rescue_off)\n" "$V"
                else
                    printf "$(t fail_rescue_off_limit)\n" "${V:-0}"
                    exit 1
                fi
                ;;
            *)
                printf "$(t fail_unknown_action)\n" "$ACTION"
                exit 1
                ;;
        esac
        ;;
    watch)
        # 全局常驻救砖：App 负责起停，脚本只在这儿落地
        # start <秒>  0 表示无限制；有 timeout 就让 timeout 到点收尾
        WATCH_PID="${WORK_DIR}/watch.pid"
        WATCH_GUARD="${WORK_DIR}/watch.guard"
        WATCH_STATE="${WORK_DIR}/watch.state"
        WATCH_OUT="${WORK_DIR}/watch.out"
        WATCH_LIMIT="${WORK_DIR}/watch.limit"
        WATCH_STOP="${WORK_DIR}/watch.stop"
        ensure_work
        WACTION="${2:-status}"
        # pid 文件读一次 内建
        read_pid() {
            _rp=""
            [ -f "$1" ] && read -r _rp < "$1" 2>/dev/null
            case "$_rp" in ''|*[!0-9]*) _rp="" ;; esac
            printf '%s' "$_rp"
        }
        case "$WACTION" in
            start)
                SEC="${3:-0}"
                case "$SEC" in ''|*[!0-9]*) SEC=0 ;; esac
                _wp=$(read_pid "$WATCH_PID")
                if [ -n "$_wp" ] && kill -0 "$_wp" 2>/dev/null; then
                    printf "$(t ok_watch_running)\n" "$_wp"
                    exit 0
                fi
                # 新一轮：清掉上一轮的停止标记与状态 已运行时长从 0 开始
                rm -f "$WATCH_PID" "$WATCH_GUARD" "$WATCH_STOP" "$WATCH_STATE"
                echo "$SEC" > "$WATCH_LIMIT"

                # 守护只有 C 一份实现：bin/watchd（arm64），没有 sh 兜底
                _wb="${MODDIR}/bin/watchd"
                _wp=""
                if [ -x "$_wb" ]; then
                    if command -v setsid >/dev/null 2>&1; then
                        setsid "$_wb" --dir "$MODDIR" >/dev/null 2>&1 &
                    else
                        "$_wb" --dir "$MODDIR" >/dev/null 2>&1 &
                    fi
                    # 等它把 pid 写出来 最多 3 秒
                    _i=0
                    while [ "$_i" -lt 30 ]; do
                        _wp=$(read_pid "$WATCH_PID")
                        [ -n "$_wp" ] && kill -0 "$_wp" 2>/dev/null && break
                        sleep 0.1
                        _i=$((_i + 1))
                    done
                else
                    t fail_watchd_missing >> "$WATCH_OUT"
                fi

                if [ -n "$_wp" ] && kill -0 "$_wp" 2>/dev/null; then
                    printf "$(t ok_watch_started)\n" "$_wp"
                else
                    t fail_watch_start
                    tail -n 5 "$WATCH_OUT" 2>/dev/null
                    exit 1
                fi
                ;;
            stop)
                # 先打停止标记：主进程和保护进程互相监护 不打标记会被拉回来
                : > "$WATCH_STOP"
                _gp=$(read_pid "$WATCH_GUARD")
                [ -n "$_gp" ] && kill "$_gp" 2>/dev/null
                _wp=$(read_pid "$WATCH_PID")
                if [ -n "$_wp" ] && kill -0 "$_wp" 2>/dev/null; then
                    kill "$_wp" 2>/dev/null
                    sleep 0.4
                    kill -9 "$_wp" 2>/dev/null
                fi
                rm -f "$WATCH_PID" "$WATCH_GUARD"
                pkill -f "${MODDIR}/bin/watchd" 2>/dev/null
                # 从旧版升级上来的机器 可能还留着 sh 版起的 logcat
                pkill -f "am_proc_start -T 1" 2>/dev/null
                echo "running=0" > "$WATCH_STATE"
                sleep 0.2
                t ok_stopped
                ;;
            status)
                _wp=$(read_pid "$WATCH_PID")
                _run=0
                if [ -n "$_wp" ] && kill -0 "$_wp" 2>/dev/null; then
                    _run=1
                else
                    rm -f "$WATCH_PID"
                fi
                # 状态文件一次读完 不逐个 grep
                _since=""
                _count=""
                if [ -f "$WATCH_STATE" ]; then
                    while IFS='=' read -r _k _v; do
                        case "$_k" in
                            since) _since="$_v" ;;
                            count) _count="$_v" ;;
                        esac
                    done < "$WATCH_STATE"
                fi
                _limit=$(read_pid "$WATCH_LIMIT")
                case "$_since" in ''|*[!0-9]*) _since=0 ;; esac
                case "$_count" in ''|*[!0-9]*) _count=0 ;; esac
                case "$_limit" in ''|*[!0-9]*) _limit=0 ;; esac
                [ "$_run" = "0" ] && _since=0
                # App 靠这一行判断模块带不带常驻守护 老模块没有这条命令
                echo "support=1"
                echo "running=$_run"
                echo "since=$_since"
                echo "limit=$_limit"
                echo "count=$_count"
                ;;
            *)
                printf "$(t fail_unknown_action)\n" "$WACTION"
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
        t webctl_usage
        exit 1
        ;;
esac
