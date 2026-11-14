#!/system/bin/sh
# ============================================================
#  编译模块里的原生二进制
#
#  把 module/src 下每个 .c 编成 module/main/bin/<同名>：
#    src/watchd.c  → bin/watchd    全局守护
#    src/actiond.c → bin/actiond   救砖执行体
#  新增源文件不用改这个脚本。
#
#  用法:
#    sh module/build-bin.sh          编 arm64 到 main/bin/
#    sh module/build-bin.sh --check  只找 NDK 不编译
#
#  产物不带架构后缀：App 端只打 arm64-v8a，模块也只出 arm64。
#  输出目录可用 $OUT_DIR 覆盖，打包脚本借此先编到临时目录、成功才替换。
#
#  退出码:
#    0  编译成功
#    2  找不到 NDK
#    1  编译失败
#
#  NDK 查找顺序:
#    $ANDROID_NDK_HOME → $ANDROID_HOME/ndk → $ANDROID_SDK_ROOT/ndk
#    → /opt/android-sdk/ndk → ~/Android/Sdk/ndk → ~/Library/Android/sdk/ndk
#  多个版本时取版本号最高的那个。
# ============================================================

set -u
ROOT=$(cd "$(dirname "$0")" && pwd) || exit 1
SRC_DIR="${ROOT}/src"
OUT_DIR="${OUT_DIR:-${ROOT}/main/bin}"
API=21

CHECK_ONLY=0
for a in "$@"; do
    case "$a" in
        --check) CHECK_ONLY=1 ;;
        -h|--help) awk 'NR>2 { if ($0 ~ /^# ={10,}/) exit; sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
        *) echo "未知参数: $a"; exit 1 ;;
    esac
done

# ---------- 找 NDK ----------
pick_ndk() {
    for base in \
        "${ANDROID_NDK_HOME:-}" \
        "${ANDROID_HOME:+${ANDROID_HOME}/ndk}" \
        "${ANDROID_SDK_ROOT:+${ANDROID_SDK_ROOT}/ndk}" \
        "/opt/android-sdk/ndk" \
        "${HOME:-/root}/Android/Sdk/ndk" \
        "${HOME:-/root}/Library/Android/sdk/ndk"
    do
        [ -n "$base" ] && [ -d "$base" ] || continue
        # 直接指到某个具体 NDK 目录（含 toolchains/llvm）也算
        if [ -d "${base}/toolchains/llvm/prebuilt" ]; then
            echo "$base"
            return 0
        fi
        _v=$(ls -1 "$base" 2>/dev/null | sort -V 2>/dev/null | tail -1)
        [ -z "$_v" ] && _v=$(ls -1 "$base" 2>/dev/null | tail -1)
        if [ -n "$_v" ] && [ -d "${base}/${_v}/toolchains/llvm/prebuilt" ]; then
            echo "${base}/${_v}"
            return 0
        fi
    done
    return 1
}

NDK=$(pick_ndk) || NDK=""
if [ -z "$NDK" ]; then
    echo "找不到 Android NDK。"
    echo "  装一个（约 600MB）: \$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager 'ndk;27.0.12077973'"
    echo "  或者先 export ANDROID_NDK_HOME=/path/to/ndk"
    exit 2
fi
echo "NDK: $NDK"

PREBUILT=""
for d in "$NDK/toolchains/llvm/prebuilt"/*; do
    [ -d "$d/bin" ] && PREBUILT="$d" && break
done
[ -n "$PREBUILT" ] || { echo "错误: NDK 里没有 llvm/prebuilt"; exit 1; }
BIN="${PREBUILT}/bin"
STRIP="${BIN}/llvm-strip"
[ -x "$STRIP" ] || STRIP=$(command -v llvm-strip 2>/dev/null || command -v strip)
CC="${BIN}/aarch64-linux-android${API}-clang"
[ -x "$CC" ] || { echo "错误: 找不到编译器 $CC"; exit 1; }

if [ "$CHECK_ONLY" = "1" ]; then
    echo "OK NDK 可用"
    exit 0
fi

mkdir -p "$OUT_DIR" || exit 1

echo "编译（API ${API}，动态链接 bionic）:"
_any=0
for _src in "${SRC_DIR}"/*.c; do
    [ -f "$_src" ] || continue
    _any=1
    _name=$(basename "$_src" .c)
    _out="${OUT_DIR}/${_name}"
    # -Os 体积优先；-fno-unwind-tables 省掉 unwind 段（这份代码不用 C++ 异常）
    if ! "$CC" -Os -s -fno-unwind-tables -fno-asynchronous-unwind-tables \
        -Wall -Wextra -Wno-unused-parameter \
        -o "$_out" "$_src"; then
        echo "编译 ${_name} 失败"
        exit 1
    fi
    [ -n "$STRIP" ] && "$STRIP" "$_out" 2>/dev/null
    chmod 755 "$_out"
    echo "  ${_name}  →  $(basename "$_out")  $(wc -c < "$_out") 字节"
done

if [ "$_any" = "0" ]; then
    echo "src/ 下没有 .c"
    exit 1
fi

echo "完成: $OUT_DIR"
ls -l "$OUT_DIR"
