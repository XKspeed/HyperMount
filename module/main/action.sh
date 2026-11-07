#!/system/bin/sh


# 健壮的脚本目录解析（兼容 sh action.sh 和绝对路径调用）
case "$0" in
    */*) MODDIR="${0%/*}" ;;
    *)   MODDIR="." ;;
esac
MODDIR=$(cd "$MODDIR" && pwd)
CONFIG_DIR="${MODDIR}/config"

[ -f "${CONFIG_DIR}/settings.conf" ] && . "${CONFIG_DIR}/settings.conf"

MISSING=""
for v in TIME_FORMAT LOG_DIR LOG_RETENTION_DAYS KEY_TIMEOUT LINE LOG_MODE VOLUME_CHECK_ASSESS PROTECT_COUNT FATAL_THRESHOLD; do
    eval "[ -z \"\${$v+x}\" ]" && MISSING="${MISSING} ${v}"
done
for f in get_time update_desc; do
    command -v "$f" >/dev/null 2>&1 || MISSING="${MISSING} ${f}()"
done

if [ -n "${MISSING}" ]; then
    echo "错误：配置文件缺失或未定义以下项："
    sleep 1 # 部分 root 管理器不延迟看不到输出
    echo "${MISSING}"
    echo "请补全 ${CONFIG_DIR}/settings.conf 后重新运行"
    exit 1
fi

for v in KEY_TIMEOUT LOG_RETENTION_DAYS LOG_MODE PROTECT_COUNT FATAL_THRESHOLD; do
    eval "_val=\${$v}"
    case "$_val" in
        ''|*[!0-9]*)
            echo "错误：${v} 必须为非负整数（当前: '${_val}'）"
            exit 1
            ;;
    esac
done

# ---------- 参数解析 ----------
# --test <apk>  测试模式：记录基线后自动把该 APK 换进挂载点
TEST_APK=""
while [ $# -gt 0 ]; do
    case "$1" in
        --test)
            TEST_APK="${2:-}"
            [ -n "$TEST_APK" ] && shift 2 || shift
            ;;
        --test=*)
            TEST_APK="${1#--test=}"
            shift
            ;;
        *)
            shift
            ;;
    esac
done

if [ -n "$TEST_APK" ] && [ ! -f "$TEST_APK" ]; then
    echo "错误：待测文件不存在: $TEST_APK"
    exit 1
fi

# ---------- 防止救砖进程重复 ----------
# 同一时间只跑一个实例：按 PID 文件把旧实例杀掉，再写入自己的 PID
# 只杀本模块记录的进程，不误伤别的脚本
ACTION_PID_FILE="${CONFIG_DIR}/action.pid"
if [ -f "${ACTION_PID_FILE}" ]; then
    _old_pid=$(cat "${ACTION_PID_FILE}" 2>/dev/null | tr -d ' \r\n')
    if [ -n "$_old_pid" ] && [ "$_old_pid" != "$$" ]; then
        if kill -0 "$_old_pid" 2>/dev/null; then
            echo "检测到已有救砖进程 (PID $_old_pid)，正在终止..."
            kill -TERM "$_old_pid" 2>/dev/null
            sleep 0.5
            kill -KILL "$_old_pid" 2>/dev/null
            sleep 0.2
        fi
    fi
    rm -f "${ACTION_PID_FILE}" 2>/dev/null
fi
echo "$$" > "${ACTION_PID_FILE}"

sleep 0.1 2>/dev/null && POLL_SLEEP=0.1 || POLL_SLEEP=1

LPID=""; FATAL_PID=""; FATAL_PGRP=0; pid_up=""; pid_down=""; tmp_up=""; tmp_down=""; fatal_ts=""
UP_PIDS=""; DOWN_PIDS=""; UP_FILES=""; DOWN_FILES=""

kill_fatal_monitor() {
    [ -z "${FATAL_PID}" ] && return 0
    if [ "${FATAL_PGRP}" = "1" ]; then
        kill -TERM -- -"${FATAL_PID}" 2>/dev/null; sleep 0.3; kill -KILL -- -"${FATAL_PID}" 2>/dev/null
    else
        kill -TERM "${FATAL_PID}" 2>/dev/null; sleep 0.3; kill -KILL "${FATAL_PID}" 2>/dev/null
    fi
    FATAL_PID=""; FATAL_PGRP=0
}

cleanup() {
    [ -n "${LPID}" ] && kill "${LPID}" 2>/dev/null
    kill_fatal_monitor
    for _p in ${UP_PIDS} ${DOWN_PIDS}; do kill "${_p}" 2>/dev/null; done
    for _f in ${UP_FILES} ${DOWN_FILES}; do rm -f "${_f}" 2>/dev/null; done
    [ -n "${tmp_up}" ] && rm -f "${tmp_up}" "${tmp_up}.done"
    [ -n "${tmp_down}" ] && rm -f "${tmp_down}" "${tmp_down}.done"
    [ -n "${fatal_ts}" ] && rm -f "${fatal_ts}" "${fatal_ts}.tmp"
    rm -f "${CONFIG_DIR}/action.pid" 2>/dev/null
}
trap cleanup EXIT INT TERM

set_conf() {
    [ -f "$3" ] || return 0
    _v=$(printf '%s' "$2" | sed 's/[&#\\]/\\&/g')
    if grep -q "^$1=" "$3" 2>/dev/null; then
        sed -i "s#^$1=.*#$1=${_v}#" "$3"
    else
        echo "$1=$2" >> "$3"
    fi
}

# ============================================================
#  音量键设备识别（按内核能力位，不依赖设备名）
#  原因：有的设备用 uinput 虚拟设备同时声明上下键，
#        按名字猜会取错；按 bit115/bit114 才准确
# ============================================================
_hasbit_w() {
    _bm="$1"; _bit="$2"; _wl="$3"
    if [ "$_wl" = "16" ]; then _need=12; else _need=24; fi
    _body=""; _cnt=0
    for _w in $_bm; do
        while [ ${#_w} -lt "$_wl" ]; do _w="0$_w"; done
        _body="$_body$_w"
        _cnt=$((_cnt + 1))
    done
    _z=""
    _i=0
    while [ "$_i" -lt "$_wl" ]; do _z="0$_z"; _i=$((_i + 1)); done
    while [ "$_cnt" -lt "$_need" ]; do
        _body="$_z$_body"
        _cnt=$((_cnt + 1))
    done
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

_hasbit() {
    _maxlen=0
    for _w in $1; do
        [ "${#_w}" -gt "$_maxlen" ] && _maxlen=${#_w}
    done
    if [ "$_maxlen" -le 8 ]; then
        _hasbit_w "$1" "$2" 8
    else
        _hasbit_w "$1" "$2" 16
    fi
}

parse_input_devs() {
    UP_DEV=""; DOWN_DEV=""
    # 优先用 volkey.sh 实测并写入配置的设备
    if [ -n "${VOL_UP_DEV}" ] || [ -n "${VOL_DOWN_DEV}" ]; then
        UP_DEV="${VOL_UP_DEV}"
        DOWN_DEV="${VOL_DOWN_DEV}"
        return 0
    fi
    _ku=""; _kd=""
    # 方式1：内核 sysfs 能力位
    for _d in /sys/class/input/event*; do
        [ -e "$_d" ] || continue
        _kb=$(cat "$_d/device/capabilities/key" 2>/dev/null)
        [ -z "$_kb" ] && _kb=$(cat "$_d/capabilities/key" 2>/dev/null)
        [ -z "$_kb" ] && continue
        _ev="/dev/input/${_d##*/}"
        _hasbit "$_kb" 115 && _ku="$_ku $_ev"
        _hasbit "$_kb" 114 && _kd="$_kd $_ev"
    done
    # 方式2：/proc/bus/input/devices
    _pu=""; _pd=""
    if [ -z "$_ku$_kd" ] && [ -r /proc/bus/input/devices ]; then
        _hd=""; _kb=""
        _pflush() {
            [ -z "$_kb" ] && return
            _ev=""
            for _t in $_hd; do
                case "$_t" in event*) _ev="/dev/input/$_t" ;; esac
            done
            [ -z "$_ev" ] && return
            _hasbit "$_kb" 115 && _pu="$_pu $_ev"
            _hasbit "$_kb" 114 && _pd="$_pd $_ev"
        }
        while IFS= read -r _l; do
            case "$_l" in
                "I: "*) _pflush; _hd=""; _kb="" ;;
                "H: Handlers="*) _hd=${_l#H: Handlers=} ;;
                "B: KEY="*) _kb=${_l#B: KEY=} ;;
            esac
        done < /proc/bus/input/devices
        _pflush
    fi
    # 方式3：getevent -pl
    _gu=""; _gd=""
    if [ -z "$_ku$_kd$_pu$_pd" ] && command -v getevent >/dev/null 2>&1; then
        _tmp="/data/local/tmp/.aid_pl.$$"
        getevent -pl 2>/dev/null > "$_tmp"
        if [ -s "$_tmp" ]; then
            _cur=""; _u=0; _v=0
            _gflush() {
                [ -z "$_cur" ] && return
                [ "$_u" = "1" ] && _gu="$_gu $_cur"
                [ "$_v" = "1" ] && _gd="$_gd $_cur"
            }
            while IFS= read -r _l; do
                case "$_l" in
                    "add device "*) _gflush; _cur=${_l##*: }; _u=0; _v=0 ;;
                    *KEY_VOLUMEUP*) _u=1 ;;
                    *KEY_VOLUMEDOWN*) _v=1 ;;
                esac
            done < "$_tmp"
            _gflush
        fi
        rm -f "$_tmp" 2>/dev/null
    fi
    # 去重
    for _x in $_ku $_pu $_gu; do
        case " $UP_DEV " in *" $_x "*) ;; *) UP_DEV="$UP_DEV $_x" ;; esac
    done
    for _x in $_kd $_pd $_gd; do
        case " $DOWN_DEV " in *" $_x "*) ;; *) DOWN_DEV="$DOWN_DEV $_x" ;; esac
    done
    UP_DEV=${UP_DEV# }
    DOWN_DEV=${DOWN_DEV# }
}

# ============================================================
#  音量键校验（保底方案）
#  WebUI 的 check.sh 是首选，这里保留完整校验作为兜底
#  两者方式完全一致：gpio-keys / qpnp_pon + timeout getevent
# ============================================================
if [ "${VOLUME_CHECK_ASSESS}" != "1" ]; then
    echo "${LINE}"
    echo "首次运行，校验音量键设备"
    echo "${LINE}"

    parse_input_devs

    verify_fail=0
    echo "音量+ 识别设备: ${UP_DEV:-未找到}"
    echo "音量- 识别设备: ${DOWN_DEV:-未找到}"
    echo "${LINE}"

    # 单键校验：只认指定键，按错会提示；成功时输出捕获到的事件
    _verify_key() {
        _vk_name="$1"
        _vk_key="$2"
        _vk_devs="$3"
        _vk_other="$4"
        _vk_dir="/data/local/tmp/.avk.$$"
        rm -rf "$_vk_dir" 2>/dev/null
        mkdir -p "$_vk_dir" 2>/dev/null
        _vk_files=""
        _vk_pids=""
        _vk_i=0
        for _vk_d in $_vk_devs; do
            _vk_i=$((_vk_i + 1))
            _vk_f="$_vk_dir/$_vk_i"
            : > "$_vk_f" 2>/dev/null
            _vk_files="$_vk_files $_vk_f"
            getevent -l "$_vk_d" > "$_vk_f" 2>/dev/null &
            _vk_pids="$_vk_pids $!"
        done
        _vk_n=0
        _vk_found=0
        _vk_warned=0
        while [ "$_vk_n" -lt 100 ]; do
            _vk_snap=$(cat $_vk_files 2>/dev/null)
            case "$_vk_snap" in
                *"$_vk_key"*) _vk_found=1; break ;;
            esac
            if [ "$_vk_warned" = "0" ] && [ -n "$_vk_other" ]; then
                case "$_vk_snap" in
                    *"$_vk_other"*)
                        _vk_warned=1
                        echo "  按到的是另一个键，请按【${_vk_name}】"
                        ;;
                esac
            fi
            _vk_n=$((_vk_n + 1))
            sleep 0.1
        done
        for _vk_p in $_vk_pids; do kill "$_vk_p" 2>/dev/null; done
        sleep 0.2
        for _vk_p in $_vk_pids; do kill -9 "$_vk_p" 2>/dev/null; done
        if [ "$_vk_found" = "1" ]; then
            for _vk_f in $_vk_files; do
                grep "$_vk_key" "$_vk_f" 2>/dev/null | sed "s/^/    /"
            done
        fi
        rm -rf "$_vk_dir" 2>/dev/null
        [ "$_vk_found" = "1" ] && return 0
        return 1
    }

    if [ -n "$UP_DEV" ]; then
        echo "请按一次【音量上】"
        if _verify_key "音量上" "KEY_VOLUMEUP" "$UP_DEV" "KEY_VOLUMEDOWN"; then
            echo "音量+ 校验成功"
        else
            echo "音量+ 未检测到"; verify_fail=1
        fi
    else
        echo "未识别到音量+设备"; verify_fail=1
    fi

    if [ -n "$DOWN_DEV" ]; then
        echo "请按一次【音量下】"
        if _verify_key "音量下" "KEY_VOLUMEDOWN" "$DOWN_DEV" "KEY_VOLUMEUP"; then
            echo "音量- 校验成功"
        else
            echo "音量- 未检测到"; verify_fail=1
        fi
    else
        echo "未识别到音量-设备"; verify_fail=1
    fi

    if [ "$verify_fail" -eq 1 ]; then
        echo "${LINE}"
        echo "音量键设备不可用，降级为仅日志模式"
        echo "${LINE}"
        LOG_MODE=1
    else
        set_conf "VOLUME_CHECK_ASSESS" "1" "${CONFIG_DIR}/settings.conf"
        echo "音量键校验完成"
        sleep 1
    fi
fi

# 过程行直接走 stdout：App 会把脚本输出整段抄进「应用日志」
# 模块自己不再单独留 debug.log 一套
debug(){ printf '%s %s\n' "$(date +%H:%M:%S)" "$1"; }

[ "${LOG_MODE}" -eq 0 ] && { echo "日志已禁用"; sleep 1; exit 0; }

# 整套模块都围绕日志转：崩溃计数、日志疯长、超阈救砖全读 LOG_FILE
# 没有目录就没有判据 直接拒绝 别让人以为流程跑起来了
if [ -z "${LOG_DIR}" ]; then
    echo "${LINE}"
    echo "未设置日志目录，无法运行"
    echo "请先在 App 的「设置 → 日志目录」里选一个目录，再重新运行"
    echo "${LINE}"
    sleep 3
    exit 1
fi

mkdir -p "${LOG_DIR}" 2>/dev/null

LOG_FILE="${LOG_DIR}/$(get_time).log"
APK="${MODDIR}/MiuiSystemUI.apk"
AIM_APK="${MODDIR}/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk"
get_log_size() { stat -c%s "${LOG_FILE}" 2>/dev/null || echo 0; }
stop_log() { kill "${LPID}" 2>/dev/null; sleep 1; kill -9 "${LPID}" 2>/dev/null; K=$(($(get_log_size)/1024)); }
finish() { update_desc "$(get_time) | 状态: $1 | 日志: ${K}KB"; exit ${2:-0}; }

echo "${LINE}"; echo "救砖模块启动"; echo "${LINE}"; sleep 1

# 修改 2：LOG_MODE=3 文案
case "${LOG_MODE}" in
    1) echo "模式: 仅记录日志" ;;
    2) echo "模式: 日志 + 救砖" ;;
    *) [ "${LOG_MODE}" -ge 3 ] && echo "模式: 日志 + 自动救砖" ;;
esac
echo "${LINE}"; debug "救砖模块启动"

[ ! -f "${APK}" ] && { echo "未找到 MiuiSystemUI.apk"; echo "请将救砖文件放入模块根目录"; debug "救砖文件不存在"; exit 1; }
debug "救砖文件存在"

SAVE=${PROTECT_COUNT}
echo "累计救砖: ${SAVE}次"; debug "救砖: ${SAVE}"
# 修改 4：CLEANER 单次遍历
CNT=$(find "${LOG_DIR}" -maxdepth 1 -name "*.log" -type f -mtime +${LOG_RETENTION_DAYS} -delete -print 2>/dev/null | wc -l | tr -d ' ')
[ "${CNT}" -gt 0 ] && echo "清理 ${CNT} 个 ${LOG_RETENTION_DAYS} 天前的日志"

# 记录当前 PID 作为基线（能手动运行本脚本，SystemUI 必然在跑）
# 记录当前 PID 作为基线（能手动运行本脚本，SystemUI 必然在跑）
OLD=$(pidof com.android.systemui 2>/dev/null | awk '{print $1}')
debug "基线 PID: ${OLD:-未运行}"

echo "${LINE}"
if [ -n "$TEST_APK" ]; then
    echo "测试模式：立即替换界面..."
else
    echo "等待替换界面（${KEY_TIMEOUT}秒超时）..."
fi
echo "${LINE}"

# 测试模式：立刻替换界面，主线程继续往下等 PID 变化
if [ -n "$TEST_APK" ]; then
    (
        echo "开始替换界面…"
        TMP_APK="/data/adb/tmp/_test_$$.apk"
        mkdir -p /data/adb/tmp 2>/dev/null
        AIM="${MODDIR}/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk"
        mkdir -p "$(dirname "$AIM")" 2>/dev/null

        if ! cp -f "$TEST_APK" "$TMP_APK" 2>/dev/null; then
            echo "错误：复制到临时目录失败"
            exit 0
        fi

        NEW_SZ=$(stat -c%s "$TMP_APK" 2>/dev/null || echo 0)
        OLD_SZ=$([ -f "$AIM" ] && stat -c%s "$AIM" 2>/dev/null || echo 0)
        echo "大小: ${OLD_SZ} -> ${NEW_SZ}"

        if [ "$NEW_SZ" = "$OLD_SZ" ] && [ "$NEW_SZ" -gt 0 ]; then
            dd if="$TMP_APK" of="$AIM" bs=1M conv=notrunc 2>/dev/null
            echo "已覆盖（大小相同，inode 不变）"
        else
            cp -f "$TMP_APK" "$AIM" 2>/dev/null
            echo "已覆盖（大小变化）"
        fi

        ACTUAL=$(stat -c%s "$AIM" 2>/dev/null || echo 0)
        rm -f "$TMP_APK" 2>/dev/null

        if [ "$ACTUAL" != "$NEW_SZ" ]; then
            echo "错误：覆盖后大小不符（期望 ${NEW_SZ}，实际 ${ACTUAL}）"
        else
            chmod 644 "$AIM" 2>/dev/null
            echo "替换完成，等待界面重启"
        fi
    ) &
fi

# 等待界面重启（PID 变化），超时则退出
W=0
while [ "${W}" -lt "${KEY_TIMEOUT}" ]; do
    sleep 1; W=$((W+1))
    NEW=$(pidof com.android.systemui 2>/dev/null | awk '{print $1}')
    [ "${NEW}" != "${OLD}" ] && [ -n "${NEW}" ] && { debug "PID变化: ${OLD:-无} -> ${NEW} | ${W}秒"; break; }
done
[ "${W}" -ge "${KEY_TIMEOUT}" ] && {
    debug "等待替换超时（${KEY_TIMEOUT}秒），退出"
    echo "等待替换界面超时（${KEY_TIMEOUT}秒），未检测到 SystemUI 重启"
    exit 0
}

# ===== 启动日志抓取 =====
touch "${LOG_FILE}"
# 修改 5：清空 crash 缓冲，避免上次运行的旧日志干扰本次判定
logcat -c -b crash
# 用 setsid 让 logcat 脱离进程组，防止父进程退出时被清理
if command -v setsid >/dev/null 2>&1; then
    setsid logcat -b crash > "${LOG_FILE}" 2>&1 < /dev/null &
else
    logcat -b crash > "${LOG_FILE}" 2>&1 < /dev/null &
fi
LPID=$!
debug "logcat启动 PID: ${LPID}"

if [ "${LOG_MODE}" -eq 1 ]; then
    # 仅日志模式：固定抓取 KEY_TIMEOUT 秒
    sleep "${KEY_TIMEOUT}"
    stop_log
    debug "仅日志退出"; finish "仅日志"
fi

# ===== 等待按键 / 自动救砖 =====
T=-1; key=""
tmp_up="/data/local/tmp/vol_up.$$"; tmp_down="/data/local/tmp/vol_down.$$"
fatal_ts="/data/local/tmp/fatal_ts.$$"; > "${fatal_ts}"

# fatal 监控（setsid 独立进程组，便于整组杀）
_FA_LOG="${LOG_FILE}"; _FA_TS="${fatal_ts}"; export _FA_LOG _FA_TS
if command -v setsid >/dev/null 2>&1; then
    setsid sh -c 'tail -n 0 -F "$_FA_LOG" 2>/dev/null | while IFS= read -r _l; do
        case "$_l" in
            *"Process: com.android.systemui"*|*"Fatal signal"*"systemui"*|*"FATAL SIGNAL"*"systemui"*|*"WATCHDOG KILLING"*|*"ANR in com.android.systemui"*|*"Process com.android.systemui has died"*)
                echo x >> "$_FA_TS" ;;
        esac
    done' >/dev/null 2>&1 &
    FATAL_PID=$!; FATAL_PGRP=1
else
    tail -n 0 -F "${LOG_FILE}" 2>/dev/null | while IFS= read -r _l; do
        case "$_l" in
            *"Process: com.android.systemui"*|*"Fatal signal"*"systemui"*|*"FATAL SIGNAL"*"systemui"*|*"WATCHDOG KILLING"*|*"ANR in com.android.systemui"*|*"Process com.android.systemui has died"*)
                echo x >> "${fatal_ts}" ;;
        esac
    done &
    FATAL_PID=$!; FATAL_PGRP=0
fi

parse_input_devs
debug "音量+设备: ${UP_DEV:-未找到}"; debug "音量-设备: ${DOWN_DEV:-未找到}"

# getevent 只接受一个 device 参数，多设备必须各起一个进程
# 每个进程写独立文件，避免事件串台，保证上下键严格区分
UP_PIDS=""; DOWN_PIDS=""
UP_FILES=""; DOWN_FILES=""

_start_ge() {
    _sg_dev="$1"
    _sg_file="$2"
    : > "$_sg_file" 2>/dev/null
    if command -v setsid >/dev/null 2>&1; then
        setsid getevent -l "$_sg_dev" >"$_sg_file" 2>/dev/null < /dev/null &
    else
        getevent -l "$_sg_dev" >"$_sg_file" 2>/dev/null &
    fi
    echo "$!"
}

_ui=0
for _d in $UP_DEV; do
    _ui=$((_ui + 1))
    _f="${tmp_up}.${_ui}"
    _p=$(_start_ge "$_d" "$_f")
    UP_PIDS="$UP_PIDS $_p"
    UP_FILES="$UP_FILES $_f"
    debug "音量+监听: $_d (pid $_p)"
done
_di=0
for _d in $DOWN_DEV; do
    _di=$((_di + 1))
    _f="${tmp_down}.${_di}"
    _p=$(_start_ge "$_d" "$_f")
    DOWN_PIDS="$DOWN_PIDS $_p"
    DOWN_FILES="$DOWN_FILES $_f"
    debug "音量-监听: $_d (pid $_p)"
done

LAST_CNT=0
LAST_SIZE=0
FLOOD_HITS=0
while :; do
    # 自动救砖：累计 FATAL 达到 FATAL_THRESHOLD 就触发
    if [ "${LOG_MODE}" -ge 3 ] && [ -s "$fatal_ts" ]; then
        cnt=$(wc -l < "$fatal_ts" 2>/dev/null | tr -d ' '); [ -z "$cnt" ] && cnt=0
        if [ "$cnt" != "$LAST_CNT" ]; then
            debug "崩溃计数: ${cnt}/${FATAL_THRESHOLD}"
            LAST_CNT="$cnt"
        fi
        [ "$cnt" -ge "$FATAL_THRESHOLD" ] && { key="AUTO_CRASH"; debug "累计 fatal ${cnt} 次，自动触发救砖"; break; }
    fi

    # ===== 黑屏卡死检测（PID 不变但日志疯长）=====
    if [ "${LOG_MODE}" -ge 3 ]; then
        NOW_SIZE=$(stat -c%s "${LOG_FILE}" 2>/dev/null || echo 0)
        GROWTH=$((NOW_SIZE - LAST_SIZE))

        # 单轮增长超过 LOG_FLOOD_BYTES 视为异常（默认 2MB）
        if [ "${GROWTH}" -gt "${LOG_FLOOD_BYTES:-2097152}" ]; then
            FLOOD_HITS=$((FLOOD_HITS + 1))
            debug "日志疯长第 ${FLOOD_HITS} 次: +${GROWTH} 字节"
            if [ "${FLOOD_HITS}" -ge 2 ]; then
                key="LOG_FLOOD"
                debug "判定黑屏卡死（日志疯长），触发救砖"
                break
            fi
        else
            FLOOD_HITS=0
        fi
        LAST_SIZE="$NOW_SIZE"

        # 堆栈占比检测（StackOverflow 特征：堆栈行占 80% 以上）
        if [ "${NOW_SIZE}" -gt 1048576 ]; then
            _total=$(wc -l < "${LOG_FILE}" 2>/dev/null || echo 0)
            _stack=$(grep -c 'AndroidRuntime: 	at ' "${LOG_FILE}" 2>/dev/null)
            [ -z "${_stack}" ] && _stack=0
            if [ "${_stack}" -gt 5000 ] && [ "${_total}" -gt 0 ] \
               && [ $((_stack * 100 / _total)) -gt 80 ]; then
                key="STACK_OVERFLOW"
                debug "判定 StackOverflow（堆栈 ${_stack}/${_total} 行），触发救砖"
                break
            fi
        fi

        # 日志体积上限：超过就重启 logcat，防止写爆存储
        if [ "${NOW_SIZE}" -gt "${LOG_MAX_BYTES:-52428800}" ]; then
            debug "日志超过上限 ${LOG_MAX_BYTES}，重启 logcat"
            kill "${LPID}" 2>/dev/null; sleep 0.2; kill -9 "${LPID}" 2>/dev/null
            : > "${LOG_FILE}"
            if command -v setsid >/dev/null 2>&1; then
                setsid logcat -b crash > "${LOG_FILE}" 2>&1 < /dev/null &
            else
                logcat -b crash > "${LOG_FILE}" 2>&1 < /dev/null &
            fi
            LPID=$!
            LAST_SIZE=0
            FLOOD_HITS=0
        fi
    fi

    # 严格区分：音量+ 只看 UP 的文件，音量- 只看 DOWN 的文件
    if [ -n "$UP_FILES" ]; then
        for _f in $UP_FILES; do
            grep -q "KEY_VOLUMEUP" "$_f" 2>/dev/null && { key="KEY_VOLUMEUP"; break 2; }
        done
    fi
    if [ -n "$DOWN_FILES" ]; then
        for _f in $DOWN_FILES; do
            grep -q "KEY_VOLUMEDOWN" "$_f" 2>/dev/null && { key="KEY_VOLUMEDOWN"; break 2; }
        done
    fi

    sleep "$POLL_SLEEP"
done

for _p in $UP_PIDS $DOWN_PIDS; do kill "$_p" 2>/dev/null; done
sleep 0.2
for _p in $UP_PIDS $DOWN_PIDS; do kill -9 "$_p" 2>/dev/null; done
rm -f $UP_FILES $DOWN_FILES 2>/dev/null
UP_PIDS=""; DOWN_PIDS=""; UP_FILES=""; DOWN_FILES=""
rm -f "$tmp_up" "$tmp_down" "${tmp_up}.done" "${tmp_down}.done"

# key 必然有值（AUTO_CRASH / LOG_FLOOD / STACK_OVERFLOW / KEY_VOLUMEUP / KEY_VOLUMEDOWN）
case "$key" in
    AUTO_CRASH)     debug "auto crash"; T=1; sleep 0.3 ;;
    LOG_FLOOD)      echo "检测到界面卡死（日志疯长）"; debug "日志疯长救砖"; T=1; sleep 0.3 ;;
    STACK_OVERFLOW) echo "检测到堆栈溢出（界面卡死）"; debug "堆栈溢出救砖"; T=1; sleep 0.3 ;;
    KEY_VOLUMEDOWN) echo "已取消"; debug "音量-"; T=0; sleep 0.3 ;;
    KEY_VOLUMEUP)   debug "音量+"; T=1; sleep 0.3 ;;
esac

kill_fatal_monitor
stop_log

if [ "${T}" -eq 0 ]; then
    debug "取消退出"; finish "已取消"
fi

debug "执行替换"
[ ! -f "${APK}" ] && {
    debug "文件丢失"
    finish "文件丢失" 1
}

mkdir -p "$(dirname "${AIM_APK}")" 2>/dev/null

# 就地覆盖（cp 保持 inode，模块挂载才会生效；mv 换 inode 会导致挂载读到旧文件）
# 先备份原文件，失败可回滚，避免 cp 中途中断留下半截文件
BAK_APK="${AIM_APK}.xk_bak"
rm -f "${BAK_APK}" 2>/dev/null
if [ -f "${AIM_APK}" ]; then
    cp -f "${AIM_APK}" "${BAK_APK}" 2>/dev/null
fi

if cp -f "${APK}" "${AIM_APK}" 2>/dev/null; then
    chmod 644 "${AIM_APK}" 2>/dev/null
    rm -f "${BAK_APK}" 2>/dev/null
    echo "替换成功"; debug "替换完成"
    NEW=$((SAVE+1))
    set_conf "PROTECT_COUNT" "${NEW}" "${CONFIG_DIR}/settings.conf"
    echo "累计救砖: ${NEW}次"; debug "救砖次数: ${NEW}"
    update_desc "$(get_time) | 状态: 已救砖 | 次数: ${NEW} | 日志: ${K}KB"
else
    # 回滚：把备份写回去，避免目标文件是半截损坏状态
    if [ -f "${BAK_APK}" ]; then
        cp -f "${BAK_APK}" "${AIM_APK}" 2>/dev/null
        chmod 644 "${AIM_APK}" 2>/dev/null
        debug "已回滚备份"
    fi
    rm -f "${BAK_APK}" 2>/dev/null
    echo "替换失败"; debug "替换失败"
    update_desc "$(get_time) | 状态: 替换失败 | 日志: ${K}KB"
fi

debug "脚本结束"
exit 0