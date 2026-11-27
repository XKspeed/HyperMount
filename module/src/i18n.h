/*
 * i18n.h —— 模块文案查表（language/<lang>.json）
 *
 * 为什么自带解析器：模块二进制是裸 NDK 编出来的，没有 jansson/cJSON 可用，
 * 引一个库进来为了两百来条扁平字符串不划算。这里只认一种形状：
 *
 *     { "key": "value", "key2": "value2" }
 *
 * 不支持嵌套、数组、数字、true/false —— 语言文件里也不需要。
 *
 * 语言判定顺序：
 *   1. config/settings.conf 的 UI_LANG：0 跟随系统 / 1 中文 / 2 English
 *      （App 里切语言时写进来）
 *   2. 没有这个键就按系统语言：persist.sys.locale → ro.product.locale
 *      以 zh 开头算中文，其余一律英文
 *
 * 查不到 key 先回中文表，中文表也没有就原样返回 key —— 保证界面上不会出现空行。
 *
 * 用法：进程起来后调一次 i18n_init(moddir)，之后 L("key") 取文案。
 * 三个二进制各自 include，static 符号互不干扰。
 */

#ifndef HM_I18N_H
#define HM_I18N_H

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/system_properties.h>
#include <limits.h>

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

/* 老 NDK 的 system_properties.h 里才有，缺了就按官方值兜底 */
#ifndef PROP_VALUE_MAX
#define PROP_VALUE_MAX 92
#endif

#define I18N_MAX 512
#define I18N_UNUSED __attribute__((unused))

typedef struct {
    char *k;
    char *v;
} I18nEntry;

/* 0 = 当前语言，1 = 中文兜底 */
static I18nEntry i18n_tbl[2][I18N_MAX];
static int i18n_n[2];
static int i18n_ready = 0;
/* 当前语言是不是英文，只用于日志 */
static int i18n_en = 0;

/** 读整个文件，返回 malloc 的串（末尾补 0）；失败返回 NULL。
 *  语言文件是几 KB 的纯文本，一次读完比分块省事。 */
static I18N_UNUSED char *i18n_slurp(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    if (fseek(f, 0, SEEK_END) != 0) { fclose(f); return NULL; }
    long n = ftell(f);
    if (n < 0 || n > 1 << 20) { fclose(f); return NULL; }
    rewind(f);
    char *buf = malloc((size_t)n + 1);
    if (!buf) { fclose(f); return NULL; }
    size_t got = fread(buf, 1, (size_t)n, f);
    fclose(f);
    buf[got] = 0;
    return buf;
}

/** 解析一个 JSON 字符串值（p 指向开引号之后），返回反转义后的 malloc 串 */
static I18N_UNUSED char *i18n_unescape(const char *p, size_t n) {
    char *out = malloc(n + 1);
    if (!out) return NULL;
    size_t o = 0;
    for (size_t i = 0; i < n; i++) {
        char c = p[i];
        if (c == '\\' && i + 1 < n) {
            char e = p[++i];
            switch (e) {
                case 'n': out[o++] = '\n'; break;
                case 't': out[o++] = '\t'; break;
                case 'r': out[o++] = '\r'; break;
                case 'b': out[o++] = '\b'; break;
                case 'f': out[o++] = '\f'; break;
                case '"': out[o++] = '"';  break;
                case '\\': out[o++] = '\\'; break;
                case '/': out[o++] = '/';  break;
                case 'u': {
                    /* 只认 BMP 里的 ASCII 段，其余原样丢掉转义标记 */
                    unsigned u = 0;
                    int k = 0;
                    while (k < 4 && i + 1 < n) {
                        char h = p[++i];
                        u <<= 4;
                        if (h >= '0' && h <= '9') u |= (unsigned)(h - '0');
                        else if (h >= 'a' && h <= 'f') u |= (unsigned)(h - 'a' + 10);
                        else if (h >= 'A' && h <= 'F') u |= (unsigned)(h - 'A' + 10);
                        k++;
                    }
                    if (u < 0x80) out[o++] = (char)u;
                    /* 非 ASCII 的 \\u 在我们文件里不会出现，忽略即可 */
                    break;
                }
                default: out[o++] = e; break;
            }
        } else {
            out[o++] = c;
        }
    }
    out[o] = 0;
    return out;
}

/** 跳过空白 */
static I18N_UNUSED const char *i18n_skip(const char *p) {
    while (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n') p++;
    return p;
}

/** 把 buf 里的扁平对象读进 tbl[slot] */
static I18N_UNUSED void i18n_parse(const char *buf, int slot) {
    i18n_n[slot] = 0;
    const char *p = buf;
    while ((p = strchr(p, '"')) != NULL) {
        const char *ks = p + 1;
        const char *ke = ks;
        while (*ke && *ke != '"') {
            if (*ke == '\\' && ke[1]) ke++;
            ke++;
        }
        if (!*ke) break;
        char *key = i18n_unescape(ks, (size_t)(ke - ks));
        p = ke + 1;
        p = i18n_skip(p);
        if (*p != ':') { free(key); continue; }
        p = i18n_skip(p + 1);
        if (*p != '"') { free(key); continue; }
        const char *vs = p + 1;
        const char *ve = vs;
        while (*ve && *ve != '"') {
            if (*ve == '\\' && ve[1]) ve++;
            ve++;
        }
        if (!*ve) { free(key); break; }
        char *val = i18n_unescape(vs, (size_t)(ve - vs));
        if (i18n_n[slot] < I18N_MAX) {
            i18n_tbl[slot][i18n_n[slot]].k = key;
            i18n_tbl[slot][i18n_n[slot]].v = val;
            i18n_n[slot]++;
        } else {
            free(key);
            free(val);
        }
        p = ve + 1;
    }
}

/** settings.conf 里 UI_LANG 的值：-1 表示没写 */
static I18N_UNUSED int i18n_read_ui_lang(const char *path) {
    FILE *f = fopen(path, "r");
    if (!f) return -1;
    char line[512];
    int ret = -1;
    while (fgets(line, sizeof line, f)) {
        char *s = line;
        while (*s == ' ' || *s == '\t') s++;
        if (*s == '#') continue;
        if (strncmp(s, "UI_LANG", 7) != 0) continue;
        s += 7;
        while (*s == ' ' || *s == '\t') s++;
        if (*s != '=') continue;
        s++;
        while (*s == ' ' || *s == '\t') s++;
        if (*s == '"') s++;
        if (*s >= '0' && *s <= '9') ret = *s - '0';
        break;
    }
    fclose(f);
    return ret;
}

/** 系统语言是不是中文 */
static I18N_UNUSED int i18n_system_is_zh(void) {
    char loc[PROP_VALUE_MAX] = {0};
    if (!__system_property_get("persist.sys.locale", loc) || !loc[0]) {
        if (!__system_property_get("ro.product.locale", loc) || !loc[0]) {
            if (!__system_property_get("persist.sys.language", loc)) loc[0] = 0;
        }
    }
    if (loc[0] == 0) return 1;              /* 读不到就按中文，至少不是满屏英文 */
    return loc[0] == 'z' && loc[1] == 'h';
}

/**
 * 初始化文案表。moddir 是模块根目录。
 *
 * 中文表总会被加载：当前语言是英文时当兜底，某个 key 漏翻就回中文，
 * 总比界面上蹦出一个 l_xxx 强。
 */
static I18N_UNUSED void i18n_init(const char *moddir) {
    if (i18n_ready) return;
    i18n_ready = 1;

    int ui = -1;
    char p_conf[PATH_MAX], p_zh[PATH_MAX], p_en[PATH_MAX];
    snprintf(p_conf, sizeof p_conf, "%s/config/settings.conf", moddir);
    snprintf(p_zh, sizeof p_zh, "%s/language/zh-cn.json", moddir);
    snprintf(p_en, sizeof p_en, "%s/language/en-us.json", moddir);

    ui = i18n_read_ui_lang(p_conf);
    int want_en;
    if (ui == 2) want_en = 1;
    else if (ui == 1) want_en = 0;
    else want_en = !i18n_system_is_zh();

    i18n_en = want_en;

    char *zh = i18n_slurp(p_zh);
    if (zh) { i18n_parse(zh, 1); free(zh); }

    if (want_en) {
        char *en = i18n_slurp(p_en);
        if (en) { i18n_parse(en, 0); free(en); }
    }

    /* 英文文件不存在或为空时退回中文，别让整进程没文案 */
    if (i18n_n[0] == 0) {
        for (int i = 0; i < i18n_n[1]; i++) i18n_tbl[0][i] = i18n_tbl[1][i];
        i18n_n[0] = i18n_n[1];
        i18n_en = 0;
    }
}

/**
 * 数字转字符串，配合 %s 占位用。
 *
 * 为什么格式串里不写 %ld / %lld：同一条文案 shell 端也在用，而 mksh / dash 的
 * printf 不认长度修饰符（dash 直接报 "invalid directive"），所以统一 %s，
 * 由 C 这边把数字转成串。
 *
 * 一条 printf 里最多出现 3 个数字，4 个槽轮转足够；参数求值顺序不影响正确性
 * （每个实参求值时就把值写进了自己的槽）。
 */
static I18N_UNUSED const char *i18n_num(long long v) {
    static char buf[4][32];
    static int slot = 0;
    char *p = buf[slot];
    slot = (slot + 1) & 3;
    snprintf(p, sizeof buf[0], "%lld", v);
    return p;
}

/** 取文案：当前语言 → 中文兜底 → key 本身 */
static I18N_UNUSED const char *L(const char *key) {
    if (!key) return "";
    for (int i = 0; i < i18n_n[0]; i++)
        if (strcmp(i18n_tbl[0][i].k, key) == 0) return i18n_tbl[0][i].v;
    for (int i = 0; i < i18n_n[1]; i++)
        if (strcmp(i18n_tbl[1][i].k, key) == 0) return i18n_tbl[1][i].v;
    return key;
}

#endif /* HM_I18N_H */
