#!/system/bin/sh
# ============================================================
#  模块一键打包
#  用法:
#    sh 打包.sh 1.6.0          本地发版：版本号写回 main/module.prop
#                              （versionCode 自动 +1），产出 zip
#    sh 打包.sh --keep         只打包当前版本 不改任何文件
#    sh 打包.sh 1.6.0 --keep   用指定版本打包 但不写回
#    sh 打包.sh --debug        内测包：版本名加 -debug 不改源码
#                              versionCode 与正式版同号 清单仍指正式清单
#    sh 打包.sh --debug --build 42
#                              内测包再带构建号：1.5.6-debug.42
#                              只有 App 的内测通道认这个后缀 Magisk 不提示
#    sh 打包.sh --out DIR ...  自定义输出目录（默认 module/dist）
#
#  不传版本号且不是 --keep 时 → 交互式输入（直接回车取消）
#
#  输出: <输出目录>/log挂载(配置版)_v<版本号>.zip
#  脚本只依赖 sh + zip（没有 zip 时退化用 python）/ awk / sed，
#  本地、Termux、GitHub Actions 都能直接跑 不写死任何绝对路径
# ============================================================

set -u
MODDIR=$(cd "$(dirname "$0")" && pwd) || exit 1
MAIN="${MODDIR}/main"
OUT_DIR="${MODDIR}/dist"
KEEP=0
DEBUG=0
# 内测构建号（CI 传 run_number）：只写进版本名 让 App 能分辨第几次内测
BUILD_NO=""

# ---------- 参数 ----------
VER=""
while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        --keep) KEEP=1 ;;
        --debug) DEBUG=1; KEEP=1 ;;
        --build) [ $# -ge 2 ] || { echo "错误: --build 后面要跟构建号"; exit 1; }; BUILD_NO="$2"; shift ;;
        --build=*) BUILD_NO="${1#--build=}" ;;
        --out) [ $# -ge 2 ] || { echo "错误: --out 后面要跟目录"; exit 1; }; OUT_DIR="$2"; shift ;;
        --out=*) OUT_DIR="${1#--out=}" ;;
        -*) echo "错误: 未知参数 $1"; exit 1 ;;
        *) VER="$1" ;;
    esac
    shift
done

[ -d "${MAIN}" ] || { echo "错误: 找不到 main 目录（${MAIN}）"; exit 1; }
PROP="${MAIN}/module.prop"
[ -f "${PROP}" ] || { echo "错误: 找不到 ${PROP}"; exit 1; }

# ---------- 定位 zip / unzip（PATH 里没有就找常见位置） ----------
find_bin() {
    _n="$1"; shift
    command -v "$_n" 2>/dev/null && return 0
    for _d in "$@"; do
        [ -x "${_d}/${_n}" ] && { echo "${_d}/${_n}"; return 0; }
    done
    return 1
}
ZIP=$(find_bin zip /data/user/0/com.termux/files/usr/bin /data/data/com.termux/files/usr/bin /data/adb/ksu/bin /data/adb/magisk /usr/bin)
UNZIP=$(find_bin unzip /data/user/0/com.termux/files/usr/bin /data/data/com.termux/files/usr/bin /data/adb/ksu/bin /data/adb/magisk /usr/bin)
PY=$(find_bin python3 /data/user/0/com.termux/files/usr/bin /data/data/com.termux/files/usr/bin /data/adb/ksu/bin /data/adb/magisk /usr/bin) \
    || PY=$(find_bin python /data/user/0/com.termux/files/usr/bin /data/data/com.termux/files/usr/bin /data/adb/ksu/bin /data/adb/magisk /usr/bin) \
    || PY=""
NODE=$(find_bin node /data/user/0/com.termux/files/usr/bin /data/data/com.termux/files/usr/bin /data/adb/ksu/bin /data/adb/magisk /usr/bin) \
    || NODE=""
if [ -z "${ZIP}" ] && [ -z "${PY}" ]; then
    echo "错误: 找不到 zip 命令，也没有 python"
    exit 1
fi
[ -z "${ZIP}" ] && echo "无 zip 命令，改用 python zipfile"
[ -z "${UNZIP}" ] && UNZIP=""

# ---------- 当前版本 ----------
OLDVER=$(grep '^version=' "${PROP}" 2>/dev/null | head -1 | cut -d= -f2 | tr -d ' \r')
OLDCODE=$(grep '^versionCode=' "${PROP}" 2>/dev/null | head -1 | cut -d= -f2 | tr -d ' \r')

trim_ver() {
    _tv=$(printf '%s' "$1" | tr -d '\r' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')
    case "${_tv}" in
        v*|V*) _tv=${_tv#?} ;;
    esac
    printf '%s' "${_tv}"
}

# 1.6 / 1.6.0 / 1.6.0_hotfix1 / 1.6.0-rc1 都算合法
valid_ver() {
    printf '%s' "$1" | grep -qE '^[0-9]+\.[0-9]+(\.[0-9]+)?([._-][0-9A-Za-z._-]+)?$'
}

if [ -n "${VER}" ]; then
    VER=$(trim_ver "${VER}")
    if ! valid_ver "${VER}"; then
        echo "错误: 版本号格式不对: $1"
        echo "  示例: 1.6.0 / 1.6 / 1.5.3_hotfix1"
        exit 1
    fi
elif [ "${KEEP}" -eq 0 ]; then
    if [ ! -t 0 ]; then
        echo "错误: 当前不是交互环境，请直接传版本号，或用 --keep 只打包当前版本"
        echo "  用法: sh 打包.sh 1.6.0"
        exit 1
    fi
    echo "当前版本: ${OLDVER:-未知}${OLDCODE:+（versionCode ${OLDCODE}）}"
    _try=0
    while [ -z "${VER}" ]; do
        _try=$((_try + 1))
        [ "${_try}" -gt 5 ] && { echo "已取消"; exit 1; }
        printf "请输入新版本号（如 1.6.0，直接回车取消）: "
        _in=""
        if ! read -r _in; then
            echo ""
            echo "已取消"
            exit 1
        fi
        _v=$(trim_ver "${_in}")
        if [ -z "${_v}" ]; then
            echo "已取消"
            exit 1
        fi
        if valid_ver "${_v}"; then
            VER="${_v}"
        else
            echo "  格式不对，示例: 1.6.0 / 1.6 / 1.5.3_hotfix1"
        fi
    done
fi
[ -n "${VER}" ] || { VER="${OLDVER}"; KEEP=1; }

# ---------- versionCode：版本号没变就保持原值，出了新版本才 +1 ----------
# 内测包与正式版同号：Magisk 只拿它跟正式清单比 号大了反而会把正式更新吃掉
if [ "${DEBUG}" -eq 1 ]; then
    case "${OLDCODE}" in
        ''|*[!0-9]*) VERCODE=1
                     CODE_NOTE="内测包，原 versionCode 缺失，取 1" ;;
        *)           VERCODE=${OLDCODE}
                     CODE_NOTE="内测包，versionCode 与正式版同号" ;;
    esac
elif [ "${VER}" = "${OLDVER}" ]; then
    case "${OLDCODE}" in
        ''|*[!0-9]*) VERCODE=1
                     CODE_NOTE="版本号未变，原 versionCode 缺失，初始化为 1" ;;
        *)           VERCODE=${OLDCODE}
                     CODE_NOTE="版本号未变，versionCode 保持不变" ;;
    esac
else
    case "${OLDCODE}" in
        ''|*[!0-9]*) VERCODE=1
                     CODE_NOTE="原 versionCode 缺失，从 1 开始" ;;
        *)           VERCODE=$((OLDCODE + 1))
                     CODE_NOTE="新版本，versionCode +1" ;;
    esac
fi

# 写回只发生在本地发版（没带 --keep / --debug）：CI 里源码不能被脚本改脏
WRITEBACK=0
if [ "${KEEP}" -eq 0 ] && [ "${DEBUG}" -eq 0 ] \
    && { [ "${VER}" != "${OLDVER}" ] || [ "${VERCODE}" != "${OLDCODE}" ]; }; then
    WRITEBACK=1
fi

# 内测包只在产物里改名 源码里的版本号不动
# 带构建号时写成 1.5.6-debug.42：App 在 versionCode 相同时靠这个后缀分辨新旧
DISPLAY_VER="${VER}"
if [ "${DEBUG}" -eq 1 ]; then
    case "${BUILD_NO}" in
        ''|*[!0-9]*) DISPLAY_VER="${VER}-debug" ;;
        *)           DISPLAY_VER="${VER}-debug.${BUILD_NO}" ;;
    esac
fi

mkdir -p "${OUT_DIR}" 2>/dev/null
OUT="${OUT_DIR}/log挂载(配置版)_v${DISPLAY_VER}.zip"

printf "v%s (versionCode %s)  →  %s\n" "${DISPLAY_VER}" "${VERCODE}" "${OUT##*/}"
printf "          %s\n" "${CODE_NOTE}"

# ---------- 语法检查（main 下所有 .sh，含 webroot） ----------
SHELLS=$(find "${MAIN}" -type f -name '*.sh' | sort)

SYN_TOTAL=0
SYN_OK=0
SYN_BAD=""

while IFS= read -r f; do
    [ -n "$f" ] || continue
    SYN_TOTAL=$((SYN_TOTAL + 1))
    if sh -n "$f" 2>/dev/null; then
        SYN_OK=$((SYN_OK + 1))
    else
        SYN_BAD="${SYN_BAD}${f}
"
    fi
done <<EOF
${SHELLS}
EOF

if [ "${SYN_OK}" != "${SYN_TOTAL}" ]; then
    printf "%-14s%s\n" "语法检查" "${SYN_OK}/${SYN_TOTAL} 有错"
    printf '%s' "${SYN_BAD}" | while IFS= read -r bf; do
        [ -n "${bf}" ] || continue
        echo "  FAIL ${bf#${MAIN}/}"
        sh -n "${bf}" 2>&1 | sed "s#${MAIN}/##g; s/^/      /"
    done
    echo ""
    echo "已中止：先修语法"
    exit 1
fi
echo "语法检查通过（${SYN_TOTAL} 个 .sh 全部 OK）"

# ---------- 关键文件 ----------
MISS=""
for f in module.prop META-INF/com/google/android/update-binary; do
    [ -f "${MAIN}/$f" ] || MISS="${MISS} $f"
done
if [ -n "${MISS}" ]; then
    printf "%-14s%s\n" "关键文件" "缺:${MISS}"
    exit 1
fi
printf "%-14s%s\n" "关键文件" "OK"

# ---------- 更新日志 ----------
if [ ! -f "${MAIN}/update.md" ]; then
    printf "%-14s%s\n" "更新日志" "无 update.md，跳过"
else
    MDVER=$(grep -oE 'v[0-9]+\.[0-9]+\.[0-9]+' "${MAIN}/update.md" 2>/dev/null | head -1 | sed 's/^v//')
    if [ "${MDVER}" = "${VER}" ]; then
        printf "%-14s%s\n" "更新日志" "v${MDVER} 匹配"
    elif [ -z "${MDVER}" ]; then
        printf "%-14s%s\n" "更新日志" "未标注版本，跳过"
    else
        printf "%-14s%s\n" "更新日志" "v${MDVER} != v${VER}（只是提醒）"
    fi
fi

# 整行替换 避免 sed 分隔符问题（$1 目标文件 $2 键 $3 值）
set_prop_in() {
    _f="$1"; _pk="$2"; _pv="$3"
    if grep -q "^${_pk}=" "${_f}" 2>/dev/null; then
        awk -v k="${_pk}" -v v="${_pv}" 'index($0, k "=") == 1 { print k "=" v; next } { print }' \
            "${_f}" > "${_f}.tmp" && mv "${_f}.tmp" "${_f}"
    else
        echo "${_pk}=${_pv}" >> "${_f}"
    fi
}

# ---------- 本地发版：写回 main/module.prop ----------
if [ "${WRITEBACK}" -eq 1 ]; then
    set_prop_in "${PROP}" version "${VER}"
    set_prop_in "${PROP}" versionCode "${VERCODE}"
    CHK=$(grep '^version=' "${PROP}" 2>/dev/null | head -1 | cut -d= -f2 | tr -d ' \r')
    if [ "${CHK}" != "${VER}" ]; then
        echo "错误: 写入 module.prop 失败（version=${CHK}）"
        exit 1
    fi
    echo "已写回 main/module.prop（version=${VER} versionCode=${VERCODE}）"
else
    echo "不写回 module.prop（--keep 或版本未变）"
fi

# ---------- 复制到临时目录，在副本上剥注释 ----------
PACK=$(mktemp -d) || { echo "错误: 无法创建临时目录"; exit 1; }
STRIP_DIR=$(mktemp -d) || { echo "错误: 无法创建临时目录"; exit 1; }
trap 'rm -rf "$PACK" "$STRIP_DIR"' EXIT INT TERM
cp -r "${MAIN}/." "${PACK}/" || { echo "错误: 复制到临时目录失败"; exit 1; }

# 副本里的版本与发布时间：打包产物永远和本次一致，源码是否改脏不影响
set_prop_in "${PACK}/module.prop" version "${DISPLAY_VER}"
set_prop_in "${PACK}/module.prop" versionCode "${VERCODE}"

PKG_TIME_FMT=$(grep '^TIME_FORMAT=' "${PACK}/config/settings.conf" 2>/dev/null \
    | head -1 | cut -d= -f2- | tr -d '"' | tr -d '\r')
[ -z "${PKG_TIME_FMT}" ] && PKG_TIME_FMT="+%Y年%m月%d日%H:%M:%S"
PKG_TIME=$(date "${PKG_TIME_FMT}" 2>/dev/null)
[ -z "${PKG_TIME}" ] && PKG_TIME=$(date "+%Y年%m月%d日%H:%M:%S")
NEW_DESC="${PKG_TIME} 发布｜状态：待重启"
set_prop_in "${PACK}/module.prop" description "${NEW_DESC}"

cat > "${STRIP_DIR}/js.awk" <<'AWKEOF'
# 逐字符扫：跳过 ' " ` 字符串和 /正则/，只剥 // 与 /* */
function lastnb(s,   k, ch) {
    k = length(s)
    while (k > 0) {
        ch = substr(s, k, 1)
        if (ch == " " || ch == "\t") { k--; continue }
        break
    }
    return (k > 0) ? substr(s, k, 1) : ""
}
BEGIN { inblock = 0; q = ""; esc = 0 }
{
    s = $0; n = length(s); out = ""; i = 1
    while (i <= n) {
        c = substr(s, i, 1)
        nx = (i < n) ? substr(s, i + 1, 1) : ""
        if (inblock) {
            if (c == "*" && nx == "/") { inblock = 0; i += 2; continue }
            i++; continue
        }
        if (q != "") {
            out = out c
            if (esc) esc = 0
            else if (c == "\\") esc = 1
            else if (c == q) q = ""
            i++; continue
        }
        if (c == "\047" || c == "\"" || c == "`") { q = c; out = out c; i++; continue }
        if (c == "/" && nx == "*") { inblock = 1; i += 2; continue }
        if (c == "/" && nx == "/") break
        if (c == "/") {
            pc = lastnb(out)
            if (pc == "" || index("(,=:[!&|?{;+-*%<>~^", pc) > 0) {
                j = i + 1; ok = 0; cls = 0
                while (j <= n) {
                    ch = substr(s, j, 1)
                    if (ch == "\\") { j += 2; continue }
                    if (ch == "[") { cls = 1; j++; continue }
                    if (ch == "]") { cls = 0; j++; continue }
                    if (ch == "/" && cls == 0) { ok = 1; break }
                    j++
                }
                if (ok) {
                    out = out substr(s, i, j - i + 1)
                    i = j + 1
                    while (i <= n && substr(s, i, 1) ~ /[a-z]/) { out = out substr(s, i, 1); i++ }
                    continue
                }
            }
        }
        out = out c
        i++
    }
    print out
}
END { if (inblock) exit 3 }
AWKEOF

cat > "${STRIP_DIR}/css.awk" <<'AWKEOF'
BEGIN { inblock = 0; q = ""; esc = 0 }
{
    s = $0; n = length(s); out = ""; i = 1
    while (i <= n) {
        c = substr(s, i, 1)
        nx = (i < n) ? substr(s, i + 1, 1) : ""
        if (inblock) {
            if (c == "*" && nx == "/") { inblock = 0; i += 2; continue }
            i++; continue
        }
        if (q != "") {
            out = out c
            if (esc) esc = 0
            else if (c == "\\") esc = 1
            else if (c == q) q = ""
            i++; continue
        }
        if (c == "\"" || c == "\047") { q = c; out = out c; i++; continue }
        if (c == "/" && nx == "*") { inblock = 1; i += 2; continue }
        out = out c
        i++
    }
    print out
}
END { if (inblock) exit 3 }
AWKEOF

cat > "${STRIP_DIR}/html.awk" <<'AWKEOF'
BEGIN { inc = 0 }
{
    s = $0; n = length(s); out = ""; i = 1
    while (i <= n) {
        if (inc) {
            if (substr(s, i, 3) == "-->") { inc = 0; i += 3; continue }
            i++; continue
        }
        if (substr(s, i, 4) == "<!--") { inc = 1; i += 4; continue }
        out = out substr(s, i, 1)
        i++
    }
    print out
}
END { if (inc) exit 3 }
AWKEOF

echo ""
echo "剥离注释"

B_TOTAL=0
A_TOTAL=0
N_FILE=0

TARGETS=$(find "${PACK}" -type f \( -name '*.sh' -o -name '*.js' -o -name '*.css' -o -name '*.html' \) | sort)

while IFS= read -r f; do
    [ -n "$f" ] || continue
    rel=${f#${PACK}/}
    BEFORE=$(wc -c < "${f}")
    case "${rel}" in
        *.sh)
            awk '/^#!/ { print; next } /^[[:space:]]*#/ { next } /^[[:space:]]*$/ { next } { print }' "${f}" > "${f}.tmp"
            ;;
        *.js|*.css|*.html)
            ext=${rel##*.}
            awk -f "${STRIP_DIR}/${ext}.awk" "${f}" > "${f}.tmp1" || {
                rm -f "${f}.tmp1"
                echo "剥离失败: ${rel}（注释可能没闭合）"
                exit 1
            }
            awk 'BEGIN{b=0} /^[[:space:]]*$/{b++; if(b<=1) print; next} {b=0; print}' "${f}.tmp1" > "${f}.tmp"
            rm -f "${f}.tmp1"
            ;;
        *) continue ;;
    esac
    mv "${f}.tmp" "${f}"
    AFTER=$(wc -c < "${f}")
    B_TOTAL=$((B_TOTAL + BEFORE))
    A_TOTAL=$((A_TOTAL + AFTER))
    N_FILE=$((N_FILE + 1))
    printf "  %-24s %7d → %7d  %7d\n" "${rel}" "${BEFORE}" "${AFTER}" "$((AFTER - BEFORE))"
done <<EOF
${TARGETS}
EOF

# settings.conf 保留注释（用户要看说明），只压连续空行
if [ -f "${PACK}/config/settings.conf" ]; then
    SC_B=$(wc -c < "${PACK}/config/settings.conf")
    awk 'BEGIN{blank=0}
         /^[[:space:]]*$/ { blank++; if (blank<=1) print; next }
         { blank=0; print }' "${PACK}/config/settings.conf" > "${PACK}/config/settings.conf.tmp" \
        && mv "${PACK}/config/settings.conf.tmp" "${PACK}/config/settings.conf"
    SC_A=$(wc -c < "${PACK}/config/settings.conf")
    printf "  %-24s %7d → %7d  %7d  (保留注释)\n" "config/settings.conf" "${SC_B}" "${SC_A}" "$((SC_A - SC_B))"
fi

SAVE=$((B_TOTAL - A_TOTAL))
if [ "${B_TOTAL}" -gt 0 ]; then
    PCT=$((SAVE * 100 / B_TOTAL))
else
    PCT=0
fi
printf "  ----------------------------------------------------------------\n"
printf "  合计 %d 个文件：%d → %d 字节，减少 %d（%d%%）\n" "${N_FILE}" "${B_TOTAL}" "${A_TOTAL}" "${SAVE}" "${PCT}"

# JS 剥离后语法校验（找到 node 才做）
if [ -n "${NODE}" ]; then
    JS_N=0
    JS_BAD=0
    while IFS= read -r jf; do
        [ -n "${jf}" ] || continue
        JS_N=$((JS_N + 1))
        "${NODE}" --check "${jf}" 2>/dev/null || {
            echo "JS 校验失败: ${jf#${PACK}/}"
            JS_BAD=1
            break
        }
    done <<EOF
$(find "${PACK}" -type f -name '*.js')
EOF
    [ "${JS_BAD}" = "1" ] && exit 1
    echo "JS 校验通过（${JS_N} 个文件）"
else
    echo "JS 校验跳过（未找到 node）"
fi

# ---------- 权限 ----------
chmod 755 "${PACK}/service.sh" "${PACK}/customize.sh" "${PACK}/action.sh" "${PACK}/config/log.sh" 2>/dev/null

# ---------- 打包 ----------
rm -f "${OUT}"
if [ -n "${ZIP}" ]; then
    (cd "${PACK}" && "${ZIP}" -r -q "${OUT}" . -x "*.DS_Store" -x "__MACOSX/*") || { echo "打包失败"; exit 1; }
else
    "${PY}" - "${PACK}" "${OUT}" <<'PYEOF' || { echo "打包失败"; exit 1; }
import os, sys, zipfile
main, out = sys.argv[1], sys.argv[2]
if os.path.exists(out):
    os.remove(out)
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(main):
        dirs[:] = sorted(d for d in dirs if d not in ("__MACOSX",))
        rel = os.path.relpath(root, main)
        if rel != ".":
            z.write(root, rel)
        for f in sorted(files):
            if f == ".DS_Store":
                continue
            fp = os.path.join(root, f)
            z.write(fp, os.path.relpath(fp, main))
PYEOF
fi

# ---------- 结果 ----------
if [ ! -f "${OUT}" ]; then
    echo "打包失败"
    exit 1
fi
SIZE=$(wc -c < "${OUT}")
printf "%-14s%s  %s 字节\n" "打包完成" "${OUT##*/}" "${SIZE}"
# 给 CI 用的稳定输出：最后一行永远是产物路径
printf 'OUTPUT=%s\n' "${OUT}"
[ -n "${GITHUB_OUTPUT:-}" ] && printf 'zip=%s\n' "${OUT}" >> "${GITHUB_OUTPUT}"
exit 0
