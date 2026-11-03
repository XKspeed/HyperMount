#!/system/bin/sh
# ============================================================
#  音量键识别与校验
#  直接运行：  ./volkey.sh
#
#  思路：不猜设备编号（每台机器不一样），改为实测。
#    1. 列出所有声明了音量键的设备（能力位）
#    2. 让用户按音量+，实测哪个设备真的收到
#    3. 用户选择（给出推荐）
#    4. 音量- 同理
#    5. 用选定设备做最终校验
# ============================================================

echo "========================================"
echo "  音量键识别与校验"
echo "========================================"
echo ""

BIT_UP=115
BIT_DOWN=114
PROBE_SEC=8

# ---------- 位图解析 ----------
_hasbit_w() {
    _bm="$1"
    _bit="$2"
    _wl="$3"
    if [ "$_wl" = "16" ]; then _need=12; else _need=24; fi
    _body=""
    _cnt=0
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
    _rest="$_body"
    _i=0
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

# ---------- 第1步：列出声明了音量键的设备 ----------
echo "--- 第 1 步：扫描输入设备 ---"

ALL_DEVS=""
DECL_UP=""
DECL_DOWN=""

for _d in /sys/class/input/event*; do
    [ -e "$_d" ] || continue
    _ev="/dev/input/${_d##*/}"
    ALL_DEVS="$ALL_DEVS $_ev"
    _kb=$(cat "$_d/device/capabilities/key" 2>/dev/null)
    [ -z "$_kb" ] && _kb=$(cat "$_d/capabilities/key" 2>/dev/null)
    [ -z "$_kb" ] && continue
    _u=0
    _v=0
    _hasbit "$_kb" $BIT_UP && _u=1
    _hasbit "$_kb" $BIT_DOWN && _v=1
    if [ "$_u" = "1" ] || [ "$_v" = "1" ]; then
        _nm=$(_devname "$_ev")
        _tag=""
        [ "$_u" = "1" ] && _tag="${_tag}UP "
        [ "$_v" = "1" ] && _tag="${_tag}DOWN"
        echo "  $_ev  $_nm  [$_tag]"
        [ "$_u" = "1" ] && DECL_UP="$DECL_UP $_ev"
        [ "$_v" = "1" ] && DECL_DOWN="$DECL_DOWN $_ev"
    fi
done

if [ -z "$ALL_DEVS" ]; then
    echo "  读不到任何输入设备"
    echo ""
    echo "原因：没有 root 权限（/dev/input 只对 root:input 开放）"
    echo "请在 root 环境（su / APatch / Magisk）下运行"
    exit 1
fi

echo ""
echo "  声明音量+ 的设备:${DECL_UP:- 无}"
echo "  声明音量- 的设备:${DECL_DOWN:- 无}"
echo ""

# 声明为空就监听全部（能力位可能不可读）
[ -z "$DECL_UP" ] && DECL_UP="$ALL_DEVS"
[ -z "$DECL_DOWN" ] && DECL_DOWN="$ALL_DEVS"

# ---------- 实测：监听一组设备，返回收到目标键的设备 ----------
# $1=设备列表  $2=目标键名  $3=秒数
_probe() {
    _pb_devs="$1"
    _pb_key="$2"
    _pb_sec="$3"
    _pb_dir="/data/local/tmp/.vkp_$$"
    rm -rf "$_pb_dir" 2>/dev/null
    mkdir -p "$_pb_dir" 2>/dev/null

    _pb_flist=""
    _pb_dlist=""
    _pb_pids=""
    _pb_i=0
    for _pb_d in $_pb_devs; do
        _pb_i=$((_pb_i + 1))
        _pb_f="$_pb_dir/$_pb_i"
        : > "$_pb_f" 2>/dev/null
        _pb_flist="$_pb_flist $_pb_f"
        _pb_dlist="$_pb_dlist $_pb_d"
        getevent -l "$_pb_d" > "$_pb_f" 2>/dev/null &
        _pb_pids="$_pb_pids $!"
    done

    _pb_n=0
    _pb_lim=$((_pb_sec * 10))
    while [ "$_pb_n" -lt "$_pb_lim" ]; do
        _pb_any=0
        for _pb_f in $_pb_flist; do
            grep -q "$_pb_key" "$_pb_f" 2>/dev/null && _pb_any=1
        done
        [ "$_pb_any" = "1" ] && break
        _pb_n=$((_pb_n + 1))
        sleep 0.1
    done

    for _pb_p in $_pb_pids; do kill "$_pb_p" 2>/dev/null; done
    sleep 0.2
    for _pb_p in $_pb_pids; do kill -9 "$_pb_p" 2>/dev/null; done

    # 输出：收到目标键的设备（一行一个）
    set -- $_pb_dlist
    for _pb_f in $_pb_flist; do
        _pb_d="$1"
        shift
        grep -q "$_pb_key" "$_pb_f" 2>/dev/null && echo "$_pb_d"
    done

    rm -rf "$_pb_dir" 2>/dev/null
}

# ---------- 选择：$1=提示  $2=候选(空格分隔)  $3=推荐 ----------
_choose() {
    _ch_title="$1"
    _ch_list="$2"
    _ch_rec="$3"
    _ch_n=0
    _ch_items=""
    echo ""
    echo "  候选设备："
    for _ch_d in $_ch_list; do
        _ch_n=$((_ch_n + 1))
        _ch_items="$_ch_items $_ch_d"
        _ch_mark=""
        [ "$_ch_d" = "$_ch_rec" ] && _ch_mark="  <- 推荐"
        echo "    $_ch_n) $_ch_d  $(_devname "$_ch_d")$_ch_mark"
    done
    echo ""
    printf "  请选择编号 [回车=推荐]: "
    _ch_sel=""
    if read -t 30 _ch_sel; then
        :
    fi
    echo ""
    # 解析选择
    _ch_pick=""
    case "$_ch_sel" in
        "") _ch_pick="$_ch_rec" ;;
        *[!0-9]*) _ch_pick="" ;;
        *)
            _ch_i=0
            for _ch_d in $_ch_items; do
                _ch_i=$((_ch_i + 1))
                [ "$_ch_i" = "$_ch_sel" ] && _ch_pick="$_ch_d"
            done
            ;;
    esac
    if [ -z "$_ch_pick" ]; then
        echo "  输入无效，使用推荐: $_ch_rec"
        _ch_pick="$_ch_rec"
    fi
    echo "  已选择: $_ch_pick"
    echo "$_ch_pick"
}

# ---------- 第2步：音量+ ----------
echo "========================================"
echo "  第 2 步：音量+ 实测"
echo "========================================"
echo ""
echo "  请按一次【音量上】...（${PROBE_SEC} 秒内）"

UP_HITS=$(_probe "$DECL_UP" "KEY_VOLUMEUP" "$PROBE_SEC")

if [ -z "$UP_HITS" ]; then
    echo "  没有捕获到音量+ 事件"
    echo ""
    echo "  可能原因："
    echo "    - 没有按键"
    echo "    - 按键未被系统上报到 /dev/input"
    echo ""
    printf "  是否改用「监听全部设备」重试? [Y/n]: "
    _rt=""
    read -t 20 _rt
    echo ""
    case "$_rt" in
        [nN]*) echo "  已跳过音量+"; UP_DEV="" ;;
        *)
            echo "  重试：请按一次【音量上】...（${PROBE_SEC} 秒内）"
            UP_HITS=$(_probe "$ALL_DEVS" "KEY_VOLUMEUP" "$PROBE_SEC")
            if [ -z "$UP_HITS" ]; then
                echo "  仍未捕获到"; UP_DEV=""
            fi
            ;;
    esac
fi

if [ -n "$UP_HITS" ]; then
    _cnt=0
    for _x in $UP_HITS; do _cnt=$((_cnt + 1)); done
    echo ""
    echo "  捕获到音量+ 的设备："
    _n=0
    for _x in $UP_HITS; do
        _n=$((_n + 1))
        echo "    $_x  $(_devname "$_x")"
    done
    if [ "$_cnt" -gt 1 ]; then
        UP_DEV=$(_choose "音量+" "$UP_HITS" "$(echo $UP_HITS | awk "{print \$1}")")
    else
        UP_DEV="$UP_HITS"
        echo ""
        echo "  只有这一个设备，自动选用: $UP_DEV"
    fi
fi

echo ""

# ---------- 第3步：音量- ----------
echo "========================================"
echo "  第 3 步：音量- 实测"
echo "========================================"
echo ""
echo "  请按一次【音量下】...（${PROBE_SEC} 秒内）"

DOWN_HITS=$(_probe "$DECL_DOWN" "KEY_VOLUMEDOWN" "$PROBE_SEC")

if [ -z "$DOWN_HITS" ]; then
    echo "  没有捕获到音量- 事件"
    echo ""
    printf "  是否改用「监听全部设备」重试? [Y/n]: "
    _rt2=""
    read -t 20 _rt2
    echo ""
    case "$_rt2" in
        [nN]*) echo "  已跳过音量-"; DOWN_DEV="" ;;
        *)
            echo "  重试：请按一次【音量下】...（${PROBE_SEC} 秒内）"
            DOWN_HITS=$(_probe "$ALL_DEVS" "KEY_VOLUMEDOWN" "$PROBE_SEC")
            if [ -z "$DOWN_HITS" ]; then
                echo "  仍未捕获到"; DOWN_DEV=""
            fi
            ;;
    esac
fi

if [ -n "$DOWN_HITS" ]; then
    _cnt2=0
    for _x in $DOWN_HITS; do _cnt2=$((_cnt2 + 1)); done
    echo ""
    echo "  捕获到音量- 的设备："
    for _x in $DOWN_HITS; do
        echo "    $_x  $(_devname "$_x")"
    done
    if [ "$_cnt2" -gt 1 ]; then
        DOWN_DEV=$(_choose "音量-" "$DOWN_HITS" "$(echo $DOWN_HITS | awk "{print \$1}")")
    else
        DOWN_DEV="$DOWN_HITS"
        echo ""
        echo "  只有这一个设备，自动选用: $DOWN_DEV"
    fi
fi

echo ""
echo "========================================"
echo "  配置结果"
echo "========================================"
echo "  音量+ 设备: ${UP_DEV:-未配置}"
echo "  音量- 设备: ${DOWN_DEV:-未配置}"
echo ""

if [ -z "$UP_DEV" ] && [ -z "$DOWN_DEV" ]; then
    echo "两个键都没配置成功，退出"
    exit 1
fi

# ---------- 第4步：最终校验 ----------
echo "========================================"
echo "  第 4 步：最终校验"
echo "========================================"
echo ""

R_UP=0
R_DOWN=0

if [ -n "$UP_DEV" ]; then
    echo "  请按一次【音量上】...（${PROBE_SEC} 秒内）"
    _h=$(_probe "$UP_DEV" "KEY_VOLUMEUP" "$PROBE_SEC")
    if [ -n "$_h" ]; then
        echo "  音量+ 校验通过（$_h）"
        R_UP=1
    else
        echo "  音量+ 校验失败"
    fi
    echo ""
fi

if [ -n "$DOWN_DEV" ]; then
    echo "  请按一次【音量下】...（${PROBE_SEC} 秒内）"
    _h2=$(_probe "$DOWN_DEV" "KEY_VOLUMEDOWN" "$PROBE_SEC")
    if [ -n "$_h2" ]; then
        echo "  音量- 校验通过（$_h2）"
        R_DOWN=1
    else
        echo "  音量- 校验失败"
    fi
    echo ""
fi

echo "========================================"
echo "  结果"
echo "========================================"
if [ "$R_UP" = "1" ]; then echo "  音量+ : 通过"; else echo "  音量+ : 未通过"; fi
if [ "$R_DOWN" = "1" ]; then echo "  音量- : 通过"; else echo "  音量- : 未通过"; fi
echo "========================================"

# 输出可直接写入配置的行
if [ "$R_UP" = "1" ] && [ "$R_DOWN" = "1" ]; then
    echo ""
    echo "建议写入 settings.conf："
    echo "  VOL_UP_DEV="$UP_DEV""
    echo "  VOL_DOWN_DEV="$DOWN_DEV""
    exit 0
fi
exit 1
