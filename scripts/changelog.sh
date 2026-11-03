#!/bin/sh
# update.md 变更日志工具
#
# 约定：文件顶部永远是一个 ## latest 段，开发中的改动都写在这里；
#       发版时用它把 ## latest 就地改名成 ## v<版本> 更新，并在顶部插入新的 ## latest。
#
# 用法:
#   changelog.sh finalize <文件> <版本号>   归档 latest 段并插入新的空 latest
#   changelog.sh has      <文件> latest      latest 段是否有正文（有 → 退出码 0）
#   changelog.sh has      <文件> <版本号>    该版本段是否有正文（有 → 退出码 0）
#   changelog.sh extract  <文件> latest     输出 latest 段正文（不含标题）
#   changelog.sh extract  <文件> <版本号>    输出 ## v<版本> 更新 段正文（不含标题）
#   changelog.sh title    <文件>            输出第一个 ## 标题（用于校验）
#
# 只依赖 POSIX sh + awk，本地和 GitHub Actions 跑结果一致。

set -eu

die() {
    printf '%s\n' "$*" >&2
    exit 2
}

# 段标题第一段：## v1.5.0 更新 → v1.5.0；## latest → latest
heading_key() {
    printf '%s' "$1" | awk '{ sub(/^##[ \t]*/, ""); print $1 }'
}

# 输出某个段落的正文（不含标题行）
extract_body() {
    file=$1
    want=$2
    awk -v want="$want" '
        /^## / {
            if (on) exit
            t = $0
            sub(/^##[ \t]*/, "", t)
            split(t, a, " ")
            h = a[1]
            if (want == "latest") on = (h == "latest")
            else on = (h == "v" want || h == want)
            next
        }
        on { print }
    ' "$file"
}

# 正文里是否有实际内容（忽略空行和 HTML 注释）
body_has_text() {
    printf '%s\n' "$1" | grep -v '^[[:space:]]*<!--' | grep -q '[^[:space:]]'
}

cmd=${1:-}
[ -n "$cmd" ] || die "缺少子命令。用 changelog.sh 查看用法。"

case "$cmd" in
finalize)
    file=${2:-}
    ver=${3:-}
    [ -n "$file" ] && [ -n "$ver" ] || die "finalize 需要 <文件> <版本号>"
    [ -f "$file" ] || die "文件不存在：$file"

    top=$(awk '/^## /{print; exit}' "$file")
    [ -n "$top" ] || die "$file 里没有 ## 标题"
    key=$(heading_key "$top")
    [ "$key" = "latest" ] || die "$file 顶部是「$top」，应为「## latest」—— 先手动把待发布的改动挪进 ## latest 段"

    # 已有同名段就不重复归档（重发同一版本时会出现）
    if extract_body "$file" "$ver" | grep -q '[^[:space:]]'; then
        die "$file 里已经存在 v$ver 的段落，不能再归档一次"
    fi

    awk -v ver="$ver" '
        { buf[NR] = $0 }
        END {
            first = 0
            for (i = 1; i <= NR; i++) if (buf[i] ~ /^## /) { first = i; break }
            print "## latest"
            print ""
            for (i = 1; i <= NR; i++) {
                if (i == first) print "## v" ver " 更新"
                else print buf[i]
            }
        }
    ' "$file" > "$file.tmp" && mv "$file.tmp" "$file"
    echo "$file: ## latest → ## v${ver} 更新，并插入新的 ## latest"
    ;;

has)
    file=${2:-}
    want=${3:-}
    [ -n "$file" ] && [ -n "$want" ] || die "has 需要 <文件> <latest|版本号>"
    [ -f "$file" ] || exit 1
    body=$(extract_body "$file" "$want") || exit 1
    body_has_text "$body"
    ;;

extract)
    file=${2:-}
    want=${3:-}
    [ -n "$file" ] && [ -n "$want" ] || die "extract 需要 <文件> <latest|版本号>"
    [ -f "$file" ] || die "文件不存在：$file"
    extract_body "$file" "$want"
    ;;

title)
    file=${2:-}
    [ -n "$file" ] || die "title 需要 <文件>"
    [ -f "$file" ] || die "文件不存在：$file"
    awk '/^## /{print; exit}' "$file"
    ;;

*)
    sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
