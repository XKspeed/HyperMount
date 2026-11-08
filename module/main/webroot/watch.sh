#!/system/bin/sh
#
# 全局常驻救砖守护
#
# 由 App 拉起（跑多久由 App 那边用 timeout 决定），不写进 service.sh、不做开机自启。
#
# 判定：WATCH_WINDOW 秒（默认 30）内，com.android.systemui 的 PID 换了
# WATCH_COUNT 次（默认 3）。换一次可能只是用户自己重启界面，连着换三次才当成
# 「界面在反复崩」。达到阈值之后才抓日志，然后交给 action.sh --now 走原来那套
# fatal 判定 + 音量键流程 —— 常驻只是把「什么时候开始看日志」提前，判定规则不变。
#
# 静默开销：平时只有一个 logcat 进程阻塞在 events buffer 的 read 上，
# 内核等待、不占 CPU；没有定时器、没有 pidof 轮询、也不提前录 crash 日志。
# 只有 events buffer 不可用的机器才退回 PID 轮询（每 2 秒一次）。
#
# 为什么不用 pidof 当主判据：轮询有延迟也要一直醒着。am_proc_start 是
# system_server 自己发的 EventLog，进程起来的那一刻就写进去了，比轮询准。

case "$0" in
    */*) WEB_DIR="${0%/*}" ;;
    *)   WEB_DIR="." ;;
esac
WEB_DIR=$(cd "$WEB_DIR" && pwd)
MODDIR=$(cd "${WEB_DIR}/.." && pwd)
WORK_DIR="${WEB_DIR}/.work"
WATCH_PID="${WORK_DIR}/watch.pid"
WATCH_OUT="${WORK_DIR}/watch.out"
WATCH_STATE="${WORK_DIR}/watch.state"
STAMPS="${WORK_DIR}/restarts"
SELF_MARK="${MODDIR}/config/.self_restart"

[ -f "${MODDIR}/config/settings.conf" ] && . "${MODDIR}/config/settings.conf"

# 窗口和阈值都可以在 settings.conf 里改 没写就用默认 30 秒 3 次
WINDOW="${WATCH_WINDOW:-30}"
COUNT="${WATCH_COUNT:-3}"
mkdir -p "${WORK_DIR}" 2>/dev/null

# 防重入：同一时间只跑一个守护
if [ -f "$WATCH_PID" ]; then
    _old=$(cat "$WATCH_PID" 2>/dev/null | tr -d ' \r\n')
    if [ -n "$_old" ] && [ "$_old" != "$$" ] && kill -0 "$_old" 2>/dev/null; then
        echo "$(date +%H:%M:%S) 已有守护在跑（$_old），本实例退出" >> "$WATCH_OUT"
        exit 0
    fi
fi
echo "$$" > "$WATCH_PID"
: > "$STAMPS"

SINCE=$(date +%s)

write_state() {
    {
        echo "running=1"
        echo "since=${SINCE}"
        echo "pid=$$"
        echo "window=${WINDOW}"
        echo "count=${1:-0}"
    } > "$WATCH_STATE"
}

# 模块自己把界面换进去引起的那次重启 不算崩溃
# action.sh 覆盖/替换完会写一个时间戳 这里读到紧随其后的第一条 systemui 启动就跳过
# 消费即删；超过 5 分钟还没等到就作废 免得把后面的真崩溃吞掉
skip_self_restart() {
    [ -f "${SELF_MARK}" ] || return 1
    _sm=$(cat "${SELF_MARK}" 2>/dev/null | tr -d ' \r\n')
    rm -f "${SELF_MARK}" 2>/dev/null
    case "${_sm}" in ''|*[!0-9]*) return 1 ;; esac
    _age=$(( ${1:-0} - _sm ))
    [ "${_age}" -lt 0 ] && _age=$(( -_age ))
    [ "${_age}" -gt 300 ] && return 1
    return 0
}

# 记一次 PID 变化 顺带把窗口外的剔掉 输出窗口内还剩几次
count_tick() {
    _t="$1"
    echo "$_t" >> "$STAMPS"
    awk -v t="$_t" -v w="$WINDOW" '$1 >= t - w' "$STAMPS" > "${STAMPS}.tmp" 2>/dev/null \
        && mv -f "${STAMPS}.tmp" "$STAMPS"
    _c=$(wc -l < "$STAMPS" 2>/dev/null | tr -d ' ')
    [ -z "$_c" ] && _c=0
    write_state "$_c"
    printf '%s' "$_c"
}

# 够阈值了：清计数 交给 action.sh 的 --now（抓日志 + fatal 判定 + 音量键）
fire() {
    _n="$1"
    echo "$(date +%H:%M:%S) ${WINDOW} 秒内界面重启 ${_n} 次，开始抓日志判定" >> "$WATCH_OUT"
    : > "$STAMPS"
    write_state 0
    sh "${MODDIR}/action.sh" --now >> "$WATCH_OUT" 2>&1
    # 救砖会把界面再拉一次 那条 start 事件随后才读到 正好作为新窗口的第一次
    echo "$(date +%H:%M:%S) 本轮结束" >> "$WATCH_OUT"
}

write_state 0
echo "$(date +%H:%M:%S) 开始监测 窗口=${WINDOW}s 阈值=${COUNT}次" >> "$WATCH_OUT"

# 事件循环进程 退出时一起收掉 否则 logcat 会挂着不走
CHILD_PID=""

cleanup() {
    [ -n "$CHILD_PID" ] && kill "$CHILD_PID" 2>/dev/null
    command -v pkill >/dev/null 2>&1 && pkill -f "am_proc_start -T 1" 2>/dev/null
    rm -f "$WATCH_PID" 2>/dev/null
    echo "running=0" > "$WATCH_STATE"
    echo "$(date +%H:%M:%S) 监测结束" >> "$WATCH_OUT"
    exit 0
}
trap cleanup EXIT INT TERM

# 主路：events buffer 里等 am_proc_start（阻塞读 静默零开销）
run_events() {
    logcat -v epoch -b events -s am_proc_start -T 1 2>/dev/null | {
        # -T 1 会先吐一条存量事件 丢掉 别把开机那次算进来
        _skip=1
        while IFS= read -r _l; do
            if [ "$_skip" = "1" ]; then _skip=0; continue; fi
            case "$_l" in
                *com.android.systemui*) ;;
                *) continue ;;
            esac
            _ts=$(printf '%s' "$_l" | awk '{print int($1)}')
            case "$_ts" in ''|*[!0-9]*) _ts=0 ;; esac
            # -v epoch 不支持时第一列是日期 兜底用当前时间
            [ "$_ts" -lt 1000000000 ] && _ts=$(date +%s)
            # 自己换进去引发的那次重启 先吃掉 别写进窗口
            if skip_self_restart "$_ts"; then
                echo "$(date +%H:%M:%S) 模块自身替换引起重启 不计入崩溃次数" >> "$WATCH_OUT"
                continue
            fi
            _n=$(count_tick "$_ts")
            [ "${_n:-0}" -ge "$COUNT" ] && fire "$_n"
        done
    } &
    CHILD_PID=$!
    wait "$CHILD_PID"
    CHILD_PID=""
}

# 兜底：events buffer 不可用 只能每 2 秒对一次 PID
run_poll() {
    echo "$(date +%H:%M:%S) events 不可用，退回 PID 轮询" >> "$WATCH_OUT"
    _base=$(pidof com.android.systemui 2>/dev/null | awk '{print $1}')
    while :; do
        sleep 2
        _now=$(pidof com.android.systemui 2>/dev/null | awk '{print $1}')
        # 界面正在重启的瞬间查不到 PID 跳过这轮等它起来
        [ -z "$_now" ] && continue
        [ "$_now" = "$_base" ] && continue
        _base="$_now"
        if skip_self_restart "$(date +%s)"; then
            echo "$(date +%H:%M:%S) 模块自身替换引起重启 不计入崩溃次数" >> "$WATCH_OUT"
            continue
        fi
        _n=$(count_tick "$(date +%s)")
        [ "${_n:-0}" -ge "$COUNT" ] && fire "$_n"
    done
}

if logcat -d -b events -s am_proc_start -T 1 >/dev/null 2>&1; then
    while :; do
        run_events
        # 管道断了（logcat 挂了）重连 期间不占 CPU
        echo "$(date +%H:%M:%S) 事件流中断，3 秒后重连" >> "$WATCH_OUT"
        sleep 3
    done
else
    run_poll
fi
