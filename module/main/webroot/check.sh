#!/system/bin/sh
case "$0" in
    */*) WEB_DIR="${0%/*}" ;;
    *)   WEB_DIR="." ;;
esac
WEB_DIR=$(cd "$WEB_DIR" && pwd)
MODDIR=$(cd "${WEB_DIR}/.." && pwd)
CONFIG_DIR="${MODDIR}/config"
CONF="${CONFIG_DIR}/settings.conf"
[ -f "$CONF" ] && . "$CONF" 2>/dev/null
WAIT_SEC=10
read_env() {
    ANDROID_VER=$(getprop ro.build.version.release 2>/dev/null)
    ANDROID_SDK=$(getprop ro.build.version.sdk 2>/dev/null)
    OS_NAME=""
    if [ -n "$(getprop ro.mi.os.version.name 2>/dev/null)" ]; then
        OS_NAME=$(getprop ro.mi.os.version.name)
    elif [ -n "$(getprop ro.miui.ui.version.name 2>/dev/null)" ]; then
        OS_NAME=$(getprop ro.miui.ui.version.name)
    fi
    DEVICE=$(getprop ro.product.device 2>/dev/null)
    MODEL=$(getprop ro.product.model 2>/dev/null)
    BUILD=$(getprop ro.build.version.incremental 2>/dev/null)
}
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
    _hasbit_w "$1" "$2" 16 && return 0
    _hasbit_w "$1" "$2" 8
}
_devname() {
    _dn=$(cat "/sys/class/input/${1##*/}/device/name" 2>/dev/null)
    echo "${_dn:-未知}"
}
scan_devices() {
    SCAN_UP=""
    SCAN_DOWN=""
    SCAN_ALL=""
    for _d in /sys/class/input/event*; do
        [ -e "$_d" ] || continue
        _ev="/dev/input/${_d##*/}"
        SCAN_ALL="$SCAN_ALL $_ev"
        _kb=$(cat "$_d/device/capabilities/key" 2>/dev/null)
        [ -z "$_kb" ] && _kb=$(cat "$_d/capabilities/key" 2>/dev/null)
        [ -z "$_kb" ] && continue
        _u=0; _v=0
        _hasbit "$_kb" 115 && _u=1
        _hasbit "$_kb" 114 && _v=1
        [ "$_u" = "1" ] && SCAN_UP="$SCAN_UP $_ev"
        [ "$_v" = "1" ] && SCAN_DOWN="$SCAN_DOWN $_ev"
    done
    SCAN_UP=${SCAN_UP# }
    SCAN_DOWN=${SCAN_DOWN# }
    SCAN_ALL=${SCAN_ALL# }
}
detect_keys() {
    if [ -n "${VOL_UP_DEV}" ] || [ -n "${VOL_DOWN_DEV}" ]; then
        UP_DEV="${VOL_UP_DEV}"
        DOWN_DEV="${VOL_DOWN_DEV}"
        return 0
    fi
    scan_devices
    UP_DEV=""
    DOWN_DEV=""
    for _x in $SCAN_UP; do
        UP_DEV="$UP_DEV $_x"
    done
    for _x in $SCAN_DOWN; do
        DOWN_DEV="$DOWN_DEV $_x"
    done
    UP_DEV=${UP_DEV# }
    DOWN_DEV=${DOWN_DEV# }
    if [ -z "$UP_DEV$DOWN_DEV" ] && command -v getevent >/dev/null 2>&1; then
        _cur=""; _u=0; _v=0
        while IFS= read -r _l; do
            case "$_l" in
                "add device "*)
                    if [ -n "$_cur" ]; then
                        [ "$_u" = "1" ] && UP_DEV="$UP_DEV $_cur"
                        [ "$_v" = "1" ] && DOWN_DEV="$DOWN_DEV $_cur"
                    fi
                    _cur=${_l##*: }; _u=0; _v=0
                    ;;
                *KEY_VOLUMEUP*) _u=1 ;;
                *KEY_VOLUMEDOWN*) _v=1 ;;
            esac
        done <<EOF
$(getevent -pl 2>/dev/null)
EOF
        if [ -n "$_cur" ]; then
            [ "$_u" = "1" ] && UP_DEV="$UP_DEV $_cur"
            [ "$_v" = "1" ] && DOWN_DEV="$DOWN_DEV $_cur"
        fi
        UP_DEV=${UP_DEV# }
        DOWN_DEV=${DOWN_DEV# }
    fi
}
probe_key() {
    _pk_devs="$1"
    _pk_key="$2"
    _pk_sec="$3"
    _pk_dir="/data/local/tmp/.chk_$$"
    rm -rf "$_pk_dir" 2>/dev/null
    mkdir -p "$_pk_dir" 2>/dev/null
    _pk_files=""
    _pk_dl=""
    _pk_pids=""
    _pk_i=0
    for _pk_d in $_pk_devs; do
        _pk_i=$((_pk_i + 1))
        _pk_f="$_pk_dir/$_pk_i"
        : > "$_pk_f" 2>/dev/null
        _pk_files="$_pk_files $_pk_f"
        _pk_dl="$_pk_dl $_pk_d"
        getevent -l "$_pk_d" > "$_pk_f" 2>/dev/null &
        _pk_pids="$_pk_pids $!"
    done
    _pk_n=0
    _pk_lim=$((_pk_sec * 10))
    while [ "$_pk_n" -lt "$_pk_lim" ]; do
        _pk_any=0
        for _pk_f in $_pk_files; do
            grep -q "$_pk_key" "$_pk_f" 2>/dev/null && _pk_any=1
        done
        [ "$_pk_any" = "1" ] && break
        _pk_n=$((_pk_n + 1))
        sleep 0.1
    done
    for _pk_p in $_pk_pids; do kill "$_pk_p" 2>/dev/null; done
    sleep 0.2
    for _pk_p in $_pk_pids; do kill -9 "$_pk_p" 2>/dev/null; done
    set -- $_pk_dl
    for _pk_f in $_pk_files; do
        _pk_d="$1"
        shift
        grep -q "$_pk_key" "$_pk_f" 2>/dev/null && echo "$_pk_d"
    done
    rm -rf "$_pk_dir" 2>/dev/null
}
set_conf() {
    _key="$1"; _val="$2"
    case "$_val" in
        *" "*) _out="\"${_val}\"" ;;
        *)     _out="${_val}" ;;
    esac
    if grep -q "^${_key}=" "$CONF" 2>/dev/null; then
        sed -i "s|^${_key}=.*|${_key}=${_out}|" "$CONF"
        return 0
    fi
    _ln=$(grep -n "^# 函数定义" "$CONF" 2>/dev/null | head -1 | cut -d: -f1)
    if [ -n "$_ln" ]; then
        sed -i "${_ln}i\\${_key}=${_out}" "$CONF"
    else
        echo "${_key}=${_out}" >> "$CONF"
    fi
}
case "${1:-}" in
    env)
        read_env
        echo "android_ver=$ANDROID_VER"
        echo "android_sdk=$ANDROID_SDK"
        echo "os_name=$OS_NAME"
        echo "device=$DEVICE"
        echo "model=$MODEL"
        echo "build=$BUILD"
        echo "saved_android=${CHECK_ANDROID_VER:-}"
        echo "saved_os=${CHECK_OS_NAME:-}"
        echo "saved_assess=${VOLUME_CHECK_ASSESS:-0}"
        echo "saved_time=${CHECK_TIME:-}"
        ;;
    keys)
        detect_keys
        echo "up=$UP_DEV"
        echo "down=$DOWN_DEV"
        ;;
    scan)
        scan_devices
        for _x in $SCAN_ALL; do
            _kb=$(cat "/sys/class/input/${_x##*/}/device/capabilities/key" 2>/dev/null)
            _u=0; _v=0
            _hasbit "$_kb" 115 && _u=1
            _hasbit "$_kb" 114 && _v=1
            [ "$_u" = "0" ] && [ "$_v" = "0" ] && continue
            _tag=""
            [ "$_u" = "1" ] && _tag="${_tag}UP "
            [ "$_v" = "1" ] && _tag="${_tag}DOWN"
            echo "$_x|$(_devname "$_x")|$_tag"
        done
        ;;
    up)
        detect_keys
        [ -z "$UP_DEV" ] && UP_DEV="$SCAN_ALL"
        [ -z "$UP_DEV" ] && { echo "ERR 没有可用设备"; exit 1; }
        _hit=$(probe_key "$UP_DEV" "KEY_VOLUMEUP" "$WAIT_SEC")
        if [ -n "$_hit" ]; then
            echo "OK $_hit"
            exit 0
        fi
        scan_devices
        if [ "$UP_DEV" != "$SCAN_ALL" ] && [ -n "$SCAN_ALL" ]; then
            _hit=$(probe_key "$SCAN_ALL" "KEY_VOLUMEUP" "$WAIT_SEC")
            if [ -n "$_hit" ]; then
                echo "OK $_hit"
                exit 0
            fi
        fi
        echo "ERR 未检测到音量+"
        exit 1
        ;;
    down)
        detect_keys
        [ -z "$DOWN_DEV" ] && DOWN_DEV="$SCAN_ALL"
        [ -z "$DOWN_DEV" ] && { echo "ERR 没有可用设备"; exit 1; }
        _hit=$(probe_key "$DOWN_DEV" "KEY_VOLUMEDOWN" "$WAIT_SEC")
        if [ -n "$_hit" ]; then
            echo "OK $_hit"
            exit 0
        fi
        scan_devices
        if [ "$DOWN_DEV" != "$SCAN_ALL" ] && [ -n "$SCAN_ALL" ]; then
            _hit=$(probe_key "$SCAN_ALL" "KEY_VOLUMEDOWN" "$WAIT_SEC")
            if [ -n "$_hit" ]; then
                echo "OK $_hit"
                exit 0
            fi
        fi
        echo "ERR 未检测到音量-"
        exit 1
        ;;
    save)
        read_env
        set_conf "VOLUME_CHECK_ASSESS" "1"
        set_conf "CHECK_ANDROID_VER" "$ANDROID_VER"
        set_conf "CHECK_ANDROID_SDK" "$ANDROID_SDK"
        set_conf "CHECK_OS_NAME" "$OS_NAME"
        set_conf "CHECK_DEVICE" "$DEVICE"
        set_conf "CHECK_TIME" "$(date "+%Y-%m-%d %H:%M")"
        [ -n "${2:-}" ] && set_conf "VOL_UP_DEV" "$2"
        [ -n "${3:-}" ] && set_conf "VOL_DOWN_DEV" "$3"
        echo "OK 已保存"
        ;;
    verify)
        read_env
        detect_keys
        FAIL=0
        echo "INFO 安卓 $ANDROID_VER (SDK $ANDROID_SDK)"
        echo "INFO 系统 $OS_NAME"
        echo "INFO 机型 $MODEL ($DEVICE)"
        echo ""
        if [ -n "${CHECK_OS_NAME}" ] && [ "${CHECK_OS_NAME}" != "$OS_NAME" ]; then
            echo "WARN 系统版本已变化：${CHECK_OS_NAME} -> $OS_NAME"
        fi
        if [ -n "${CHECK_ANDROID_VER}" ] && [ "${CHECK_ANDROID_VER}" != "$ANDROID_VER" ]; then
            echo "WARN 安卓版本已变化：${CHECK_ANDROID_VER} -> $ANDROID_VER"
        fi
        [ -z "$UP_DEV" ] && { echo "FAIL 未识别到音量+设备"; FAIL=1; }
        [ -z "$DOWN_DEV" ] && { echo "FAIL 未识别到音量-设备"; FAIL=1; }
        if [ -n "$UP_DEV" ]; then
            echo "WAIT 请按一次【音量上】"
            _h=$(probe_key "$UP_DEV" "KEY_VOLUMEUP" "$WAIT_SEC")
            if [ -n "$_h" ]; then echo "PASS 音量+ 校验成功"; else echo "FAIL 音量+ 未检测到"; FAIL=1; fi
        fi
        if [ -n "$DOWN_DEV" ]; then
            echo "WAIT 请按一次【音量下】"
            _h=$(probe_key "$DOWN_DEV" "KEY_VOLUMEDOWN" "$WAIT_SEC")
            if [ -n "$_h" ]; then echo "PASS 音量- 校验成功"; else echo "FAIL 音量- 未检测到"; FAIL=1; fi
        fi
        if [ "$FAIL" = "1" ]; then echo "RESULT 校验失败"; exit 1; fi
        set_conf "VOLUME_CHECK_ASSESS" "1"
        set_conf "CHECK_ANDROID_VER" "$ANDROID_VER"
        set_conf "CHECK_ANDROID_SDK" "$ANDROID_SDK"
        set_conf "CHECK_OS_NAME" "$OS_NAME"
        set_conf "CHECK_DEVICE" "$DEVICE"
        set_conf "CHECK_TIME" "$(date "+%Y-%m-%d %H:%M")"
        echo "RESULT 校验通过"
        exit 0
        ;;
    *)
        echo "用法: check.sh env|keys|scan|up|down|save|verify"
        exit 1
        ;;
esac
