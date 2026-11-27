#!/system/bin/sh


# 健壮的脚本目录解析（兼容 sh action.sh 和绝对路径调用）
case "$0" in
    */*) MODDIR="${0%/*}" ;;
    *)   MODDIR="." ;;
esac
MODDIR=$(cd "$MODDIR" && pwd)
CONFIG_DIR="${MODDIR}/config"

[ -f "${CONFIG_DIR}/settings.conf" ] && . "${CONFIG_DIR}/settings.conf"
# 函数单独一份：后加载，覆盖旧版 settings.conf 里残留的旧定义
[ -f "${CONFIG_DIR}/functions.sh" ] && . "${CONFIG_DIR}/functions.sh"

# ---------- 模块自身引起的重启 ----------
# 测试模式覆盖、守护救砖替换 都会让界面自己重启一次。那次不是崩溃，
# 不能算进 watchd 的窗口计数（点一次测试就把阈值顶掉 会误触发救砖）。
# 覆盖/替换完成后写一个时间戳，watchd 读到紧随其后的第一条 systemui 启动
# 跳过并把它删掉。
WEB_DIR="${MODDIR}/scripts"
WATCH_PID="${WEB_DIR}/.work/watch.pid"
SELF_MARK="${CONFIG_DIR}/.self_restart"

watch_running() {
    [ -f "${WATCH_PID}" ] || return 1
    _wr=$(cat "${WATCH_PID}" 2>/dev/null | tr -d ' \r\n')
    [ -n "${_wr}" ] && kill -0 "${_wr}" 2>/dev/null
}

mark_self_restart() {
    date +%s > "${SELF_MARK}" 2>/dev/null
}

MISSING=""
for v in TIME_FORMAT LOG_DIR LOG_RETENTION_DAYS KEY_TIMEOUT LINE LOG_MODE VOLUME_CHECK_ASSESS PROTECT_COUNT FATAL_THRESHOLD; do
    eval "[ -z \"\${$v+x}\" ]" && MISSING="${MISSING} ${v}"
done
MISSING_FN=""
for f in get_time update_desc set_desc; do
    command -v "$f" >/dev/null 2>&1 || MISSING_FN="${MISSING_FN} ${f}()"
done

if [ -n "${MISSING}" ] || [ -n "${MISSING_FN}" ]; then
    tl err_incomplete
    sleep 1 # 部分 root 管理器不延迟看不到输出
    [ -n "${MISSING}" ] && {
        printf "$(t err_incomplete_items)\n" "${MISSING}"
        printf "$(t err_incomplete_hint)\n" "${CONFIG_DIR}/settings.conf"
    }
    [ -n "${MISSING_FN}" ] && {
        printf "$(t err_incomplete_fn)\n" "${MISSING_FN}"
        printf "$(t err_incomplete_fn_hint)\n" "${CONFIG_DIR}/functions.sh"
    }
    exit 1
fi

for v in KEY_TIMEOUT LOG_RETENTION_DAYS LOG_MODE PROTECT_COUNT FATAL_THRESHOLD; do
    eval "_val=\${$v}"
    case "$_val" in
        ''|*[!0-9]*)
            printf "$(t cfg_bad_int)\n" "${v}" "${_val}"
            exit 1
            ;;
    esac
done

# ---------- 参数解析 ----------
# --test <apk>  测试模式：记录基线后自动把该 APK 换进挂载点
# --now         全局常驻救砖触发的一轮：界面已经重启过了，跳过「替换 + 等 PID」，
#               直接从抓日志开始判定。由 bin/watchd fork 出来调用
TEST_APK=""
NOW_MODE=0
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
        --now)
            NOW_MODE=1
            shift
            ;;
        *)
            shift
            ;;
    esac
done

if [ -n "$TEST_APK" ] && [ ! -f "$TEST_APK" ]; then
    printf "$(t err_test_missing)\n" "$TEST_APK"
    exit 1
fi

# ---------- 防止救砖进程重复 ----------
# 同一时间只跑一个实例：按 PID 文件把旧实例杀掉，再写入自己的 PID
# 只杀本模块记录的进程，不误伤别的脚本
# --now 用独立文件：这一轮由守护脚本发起，既不能抢用户手点的测试，
# 也不能反过来把守护自己顶掉
if [ "${NOW_MODE}" = "1" ]; then
    ACTION_PID_FILE="${CONFIG_DIR}/action-now.pid"
else
    ACTION_PID_FILE="${CONFIG_DIR}/action.pid"
fi
if [ -f "${ACTION_PID_FILE}" ] && [ "${NOW_MODE}" != "1" ]; then
    _old_pid=$(cat "${ACTION_PID_FILE}" 2>/dev/null | tr -d ' \r\n')
    if [ -n "$_old_pid" ] && [ "$_old_pid" != "$$" ]; then
        if kill -0 "$_old_pid" 2>/dev/null; then
            printf "$(t kill_old)\n" "$_old_pid"
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
    rm -f "${ACTION_PID_FILE}" 2>/dev/null
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
#  App 走 scripts/check.sh 是首选，这里保留完整校验作为兜底
#  两者方式完全一致：gpio-keys / qpnp_pon + timeout getevent
# ============================================================
if [ "${VOLUME_CHECK_ASSESS}" != "1" ]; then
    echo "${LINE}"
    tl vol_first_run
    echo "${LINE}"

    parse_input_devs

    verify_fail=0
    printf "$(t vol_up_dev)\n" "${UP_DEV:-$(t common_not_found)}"
    printf "$(t vol_down_dev)\n" "${DOWN_DEV:-$(t common_not_found)}"
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
                        printf "$(t vol_wrong_key)\n" "${_vk_name}"
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
        tl vol_press_up
        if _verify_key "$(t key_vol_up_name)" "KEY_VOLUMEUP" "$UP_DEV" "KEY_VOLUMEDOWN"; then
            tl vol_up_ok
        else
            tl vol_up_miss; verify_fail=1
        fi
    else
        tl vol_up_nodev; verify_fail=1
    fi

    if [ -n "$DOWN_DEV" ]; then
        tl vol_press_down
        if _verify_key "$(t key_vol_down_name)" "KEY_VOLUMEDOWN" "$DOWN_DEV" "KEY_VOLUMEUP"; then
            tl vol_down_ok
        else
            tl vol_down_miss; verify_fail=1
        fi
    else
        tl vol_down_nodev; verify_fail=1
    fi

    if [ "$verify_fail" -eq 1 ]; then
        echo "${LINE}"
        tl vol_degrade
        echo "${LINE}"
        LOG_MODE=1
    else
        set_conf "VOLUME_CHECK_ASSESS" "1" "${CONFIG_DIR}/settings.conf"
        tl vol_done
        sleep 1
    fi
fi

# 过程行直接走 stdout：App 会把脚本输出整段抄进「应用日志」
# 模块自己不再单独留 debug.log 一套
debug(){ printf '%s %s\n' "$(date +%H:%M:%S)" "$1"; }

# 测试模式：把待测界面覆盖进挂载点（就地基底覆盖，保住 inode 才有挂载效果）
# 守护在跑时也要用同一套，所以抽出来 两条路共用
cover_test_apk() {
    tl replace_begin
    _cv_tmp="/data/adb/tmp/_test_$$.apk"
    mkdir -p /data/adb/tmp 2>/dev/null
    _cv_aim="${MODDIR}/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk"
    _cv_mnt="/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk"
    mkdir -p "$(dirname "$_cv_aim")" 2>/dev/null

    # 挂载点是单文件 bind，绑的是 inode。源文件一旦被 rm/mv 换成新 inode，
    # 挂载点还指着旧的那份，之后怎么改都进不去 —— 这就是「换什么包都不重启」的根因。
    # 开测前先比对，不一致就重新 bind，把它修回来。
    _cv_src=$(readlink -f "$_cv_aim" 2>/dev/null)
    [ -n "$_cv_src" ] || _cv_src="$_cv_aim"
    if [ -f "$_cv_mnt" ] && [ -f "$_cv_src" ]; then
        _cv_mi=$(stat -c %i "$_cv_mnt" 2>/dev/null)
        _cv_si=$(stat -c %i "$_cv_src" 2>/dev/null)
        if [ -n "$_cv_mi" ] && [ -n "$_cv_si" ] && [ "$_cv_mi" != "$_cv_si" ]; then
            printf "$(t replace_rebind)\n" "${_cv_mi}" "${_cv_si}"
            umount "$_cv_mnt" 2>/dev/null
            if mount --bind "$_cv_src" "$_cv_mnt" 2>/dev/null; then
                tl replace_rebind_done
            else
                tl replace_rebind_fail
                return 1
            fi
        fi
    fi

    if ! cp -f "$TEST_APK" "$_cv_tmp" 2>/dev/null; then
        tl replace_copy_fail
        return 1
    fi

    _cv_new=$(stat -c%s "$_cv_tmp" 2>/dev/null || echo 0)
    _cv_old=$([ -f "$_cv_aim" ] && stat -c%s "$_cv_aim" 2>/dev/null || echo 0)
    printf "$(t replace_size)\n" "${_cv_old}" "${_cv_new}"

    if [ "$_cv_new" = "$_cv_old" ] && [ "$_cv_new" -gt 0 ]; then
        dd if="$_cv_tmp" of="$_cv_aim" bs=1M conv=notrunc 2>/dev/null
        tl replace_same_size
    else
        # 不能用 cp -f / mv / rm+cp：都可能换 inode，bind 挂载点会跟着失联
        cat "$_cv_tmp" > "$_cv_aim" 2>/dev/null
        tl replace_size_changed
    fi

    _cv_actual=$(stat -c%s "$_cv_aim" 2>/dev/null || echo 0)
    rm -f "$_cv_tmp" 2>/dev/null

    if [ "$_cv_actual" != "$_cv_new" ]; then
        printf "$(t replace_size_mismatch)\n" "${_cv_new}" "${_cv_actual}"
        return 1
    fi
    chmod 644 "$_cv_aim" 2>/dev/null
    tl replace_done_wait
    return 0
}

if [ "${LOG_MODE}" -eq 0 ]; then
    tl log_disabled; sleep 1; exit 0
fi

# 整套模块都围绕日志转：崩溃计数、日志疯长、超阈救砖全读 LOG_FILE
# 没有目录就没有判据 直接拒绝 别让人以为流程跑起来了
if [ -z "${LOG_DIR}" ]; then
    echo "${LINE}"
    tl err_no_logdir
    tl err_no_logdir_hint
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
finish() { set_desc "$1" "${K}"; exit ${2:-0}; }

echo "${LINE}"; tl start_banner; echo "${LINE}"; sleep 1

# 修改 2：LOG_MODE=3 文案
case "${LOG_MODE}" in
    1) tl mode_log_only ;;
    2) tl mode_log_rescue ;;
    *) [ "${LOG_MODE}" -ge 3 ] && tl mode_log_auto ;;
esac
echo "${LINE}"; debug "$(t start_banner)"

[ ! -f "${APK}" ] && { tl err_no_apk; tl err_no_apk_hint; debug "$(t dbg_apk_missing)"; exit 1; }
debug "$(t dbg_apk_ok)"

SAVE=${PROTECT_COUNT}
printf "$(t protect_count)\n" "${SAVE}"; printf "$(t dbg_protect)\n" "${SAVE}"
# 修改 4：CLEANER 单次遍历
CNT=$(find "${LOG_DIR}" -maxdepth 1 -name "*.log" -type f -mtime +${LOG_RETENTION_DAYS} -delete -print 2>/dev/null | wc -l | tr -d ' ')
[ "${CNT}" -gt 0 ] && printf "$(t clean_logs)\n" "${CNT}" "${LOG_RETENTION_DAYS}"

# 记录当前 PID 作为基线（能手动运行本脚本，SystemUI 必然在跑）
OLD=$(pidof com.android.systemui 2>/dev/null | awk '{print $1}')
if [ -n "${OLD}" ]; then printf "$(t dbg_base_pid)\n" "${OLD}"; else tl dbg_base_pid_none; fi

if [ "${NOW_MODE}" = "1" ]; then
    # 全局常驻救砖这一轮：界面已经重启过了（watchd 就是靠 PID 变化叫醒的），
    # 不替换任何文件，也不等 PID，直接进抓日志判定
    echo "${LINE}"
    tl watch_restart_detect
    echo "${LINE}"
else

# 守护在跑：这一轮只负责把界面换进去
# 抓日志、崩溃计数、救砖都在 watchd 里，这边再跑一套只会两边抢着抓日志、抢着替换
if [ -n "$TEST_APK" ] && watch_running; then
    echo "${LINE}"
    tl watch_on_replace_only
    echo "${LINE}"
    if cover_test_apk; then
        mark_self_restart
        echo "${LINE}"
        tl watch_done_handoff
        # 交接标记，见 actiond.c 同处
        printf '##HANDOFF\n'
        tl watch_done_hint
        debug "$(t dbg_test_replace_only)"
        exit 0
    fi
    tl watch_replace_fail
    debug "$(t dbg_test_replace_fail)"
    exit 1
fi

echo "${LINE}"
if [ -n "$TEST_APK" ]; then
    tl test_mode_replace
else
    printf "$(t wait_replace)\n" "${KEY_TIMEOUT}"
fi
echo "${LINE}"

# 测试模式：立刻替换界面，主线程继续往下等 PID 变化
if [ -n "$TEST_APK" ]; then
    cover_test_apk &
fi

# 等待界面重启（PID 变化），超时则退出
_co_t0=$(date +"%m-%d %H:%M:%S")
_cv_anr=0
W=0
while [ "${W}" -lt "${KEY_TIMEOUT}" ]; do
    sleep 1; W=$((W+1))
    NEW=$(pidof com.android.systemui 2>/dev/null | awk '{print $1}')
    [ "${NEW}" != "${OLD}" ] && [ -n "${NEW}" ] && { printf "$(t dbg_pid_change)\n" "${OLD:-0}" "${NEW}" "${W}"; break; }
    # 卡死时进程还在，pidof 看不出变化：ANR 一出现就说明这轮界面已经废了
    if logcat -b events -d -s am_anr -T "${_co_t0}.000" 2>/dev/null | grep -q "com.android.systemui"; then
        printf "$(t anr_detected)\n" "${W}"
        _cv_anr=1
        break
    fi
done
[ "${W}" -ge "${KEY_TIMEOUT}" ] && {
    printf "$(t dbg_wait_timeout)\n" "${KEY_TIMEOUT}"
    printf "$(t wait_timeout)\n" "${KEY_TIMEOUT}"
    tl wait_timeout_fail
    exit 1
}

fi   # NOW_MODE 分支结束

# ===== 启动日志抓取 =====
touch "${LOG_FILE}"
# 正常测试：清空 crash 缓冲，避免上次运行的旧日志干扰本次判定
# --now：崩溃已经发生过了，缓冲里就是这次的证据，绝不能清
# ANR 那一轮同理：crash 缓冲里存着判死依据，清了就没得看
if [ "${NOW_MODE}" = "1" ] || [ "${_cv_anr}" = "1" ]; then
    debug "$(t dbg_keep_crash)"
else
    logcat -c -b crash
fi
# 用 setsid 让 logcat 脱离进程组，防止父进程退出时被清理
if command -v setsid >/dev/null 2>&1; then
    setsid logcat -b crash > "${LOG_FILE}" 2>&1 < /dev/null &
else
    logcat -b crash > "${LOG_FILE}" 2>&1 < /dev/null &
fi
LPID=$!
printf "$(t dbg_logcat_pid)\n" "${LPID}"

if [ "${LOG_MODE}" -eq 1 ]; then
    # 仅日志模式：固定抓取 KEY_TIMEOUT 秒
    sleep "${KEY_TIMEOUT}"
    stop_log
    debug "$(t dbg_log_only_exit)"; finish state_log_only
fi

# ===== 等待按键 / 自动救砖 =====
T=-1; key=""
tmp_up="/data/local/tmp/vol_up.$$"; tmp_down="/data/local/tmp/vol_down.$$"
fatal_ts="/data/local/tmp/fatal_ts.$$"; > "${fatal_ts}"

# 常驻模式跟手点测试用的是同一套判定：崩溃次数就是设置里那个 FATAL_THRESHOLD，
# 不在这里改阈值。真崩的时候界面会被反复拉起又反复崩，几次 fatal 很快就齐；
# 反倒是用户误杀进程那种「只崩一次」的情况不该被当成死循环，等窗口过去放行就好。
WATCH_DEADLINE=0
if [ "${NOW_MODE}" = "1" ]; then
    WATCH_DEADLINE=$(($(date +%s) + KEY_TIMEOUT))
    printf "$(t dbg_watch_window)\n" "${KEY_TIMEOUT}" "${FATAL_THRESHOLD}"
fi

# fatal 监控（setsid 独立进程组，便于整组杀）
# --now 要从文件头读：崩溃日志是 logcat 启动时一次性灌进文件开头的，
# 只 tail 新行会把它全漏掉
if [ "${NOW_MODE}" = "1" ]; then TAIL_FROM="-n +1"; else TAIL_FROM="-n 0"; fi
_FA_LOG="${LOG_FILE}"; _FA_TS="${fatal_ts}"; export _FA_LOG _FA_TS TAIL_FROM
if command -v setsid >/dev/null 2>&1; then
    setsid sh -c 'tail $TAIL_FROM -F "$_FA_LOG" 2>/dev/null | while IFS= read -r _l; do
        case "$_l" in
            *"Process: com.android.systemui"*|*"Fatal signal"*"systemui"*|*"FATAL SIGNAL"*"systemui"*|*"WATCHDOG KILLING"*|*"ANR in com.android.systemui"*|*"Process com.android.systemui has died"*)
                echo x >> "$_FA_TS" ;;
        esac
    done' >/dev/null 2>&1 &
    FATAL_PID=$!; FATAL_PGRP=1
else
    tail $TAIL_FROM -F "${LOG_FILE}" 2>/dev/null | while IFS= read -r _l; do
        case "$_l" in
            *"Process: com.android.systemui"*|*"Fatal signal"*"systemui"*|*"FATAL SIGNAL"*"systemui"*|*"WATCHDOG KILLING"*|*"ANR in com.android.systemui"*|*"Process com.android.systemui has died"*)
                echo x >> "${fatal_ts}" ;;
        esac
    done &
    FATAL_PID=$!; FATAL_PGRP=0
fi

parse_input_devs
printf "$(t dbg_vol_up_dev)\n" "${UP_DEV:-$(t common_not_found)}"; printf "$(t dbg_vol_down_dev)\n" "${DOWN_DEV:-$(t common_not_found)}"

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
    printf "$(t dbg_vol_up_listen)\n" "$_d (pid $_p)"
done
_di=0
for _d in $DOWN_DEV; do
    _di=$((_di + 1))
    _f="${tmp_down}.${_di}"
    _p=$(_start_ge "$_d" "$_f")
    DOWN_PIDS="$DOWN_PIDS $_p"
    DOWN_FILES="$DOWN_FILES $_f"
    printf "$(t dbg_vol_down_listen)\n" "$_d (pid $_p)"
done

LAST_CNT=0
LAST_SIZE=0
FLOOD_HITS=0
while :; do
    # 常驻这一轮只看 KEY_TIMEOUT 秒：窗口内没等到 fatal 就放行 回去继续数重启
    # 不退出的话守护会被卡在这里 后面再崩也数不到
    if [ "${WATCH_DEADLINE}" -gt 0 ] && [ "$(date +%s)" -ge "${WATCH_DEADLINE}" ]; then
        printf "$(t dbg_watch_end)\n" "${KEY_TIMEOUT}"
        kill_fatal_monitor
        stop_log
        finish state_watch_end
    fi

    # 自动救砖：累计 FATAL 达到 FATAL_THRESHOLD 就触发
    if [ "${LOG_MODE}" -ge 3 ] && [ -s "$fatal_ts" ]; then
        cnt=$(wc -l < "$fatal_ts" 2>/dev/null | tr -d ' '); [ -z "$cnt" ] && cnt=0
        if [ "$cnt" != "$LAST_CNT" ]; then
            printf "$(t dbg_crash_count)\n" "${cnt}" "${FATAL_THRESHOLD}"
            LAST_CNT="$cnt"
        fi
        [ "$cnt" -ge "$FATAL_THRESHOLD" ] && { key="AUTO_CRASH"; printf "$(t dbg_auto_rescue)\n" "${cnt}"; break; }
    fi

    # ===== 黑屏卡死检测（PID 不变但日志疯长）=====
    if [ "${LOG_MODE}" -ge 3 ]; then
        NOW_SIZE=$(stat -c%s "${LOG_FILE}" 2>/dev/null || echo 0)
        GROWTH=$((NOW_SIZE - LAST_SIZE))

        # 单轮增长超过 LOG_FLOOD_BYTES 视为异常（默认 2MB）
        if [ "${GROWTH}" -gt "${LOG_FLOOD_BYTES:-2097152}" ]; then
            FLOOD_HITS=$((FLOOD_HITS + 1))
            printf "$(t dbg_flood)\n" "${FLOOD_HITS}" "${GROWTH}"
            if [ "${FLOOD_HITS}" -ge 2 ]; then
                key="LOG_FLOOD"
                debug "$(t dbg_flood_rescue)"
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
                printf "$(t dbg_stack_rescue)\n" "${_stack}" "${_total}"
                break
            fi
        fi

        # 日志体积上限：超过就重启 logcat，防止写爆存储
        if [ "${NOW_SIZE}" -gt "${LOG_MAX_BYTES:-52428800}" ]; then
            printf "$(t dbg_log_rotate)\n" "${LOG_MAX_BYTES}"
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
    LOG_FLOOD)      tl hit_flood; debug "$(t dbg_flood_do)"; T=1; sleep 0.3 ;;
    STACK_OVERFLOW) tl hit_stack; debug "$(t dbg_stack_do)"; T=1; sleep 0.3 ;;
    KEY_VOLUMEDOWN) tl state_cancelled; debug "$(t dbg_vol_down_key)"; T=0; sleep 0.3 ;;
    KEY_VOLUMEUP)   debug "$(t dbg_vol_up_key)"; T=1; sleep 0.3 ;;
esac

kill_fatal_monitor
stop_log

if [ "${T}" -eq 0 ]; then
    debug "$(t dbg_cancel_exit)"; finish state_cancelled
fi

debug "$(t dbg_do_replace)"
[ ! -f "${APK}" ] && {
    debug "$(t state_file_lost)"
    finish state_file_lost 1
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
    tl state_ok; debug "$(t dbg_replace_done)"
    # 这次界面重启是救砖自己拉起来的 不算崩溃 watchd 那边跳过这一条
    watch_running && mark_self_restart
    NEW=$((SAVE+1))
    set_conf "PROTECT_COUNT" "${NEW}" "${CONFIG_DIR}/settings.conf"
    printf "$(t protect_count)\n" "${NEW}"; printf "$(t dbg_rescue_times)\n" "${NEW}"
    set_desc state_rescued "${K}" "$(printf "$(t desc_extra_count)\n" "${NEW}")"
else
    # 回滚：把备份写回去，避免目标文件是半截损坏状态
    if [ -f "${BAK_APK}" ]; then
        cp -f "${BAK_APK}" "${AIM_APK}" 2>/dev/null
        chmod 644 "${AIM_APK}" 2>/dev/null
        debug "$(t dbg_rollback)"
    fi
    rm -f "${BAK_APK}" 2>/dev/null
    tl state_fail; debug "$(t state_fail)"
    set_desc state_fail "${K}"
fi

debug "$(t dbg_end)"
exit 0