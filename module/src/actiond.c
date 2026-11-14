/*
 * actiond —— 救砖执行体（action.sh 的 C 版）
 *
 * 为什么重写：action.sh 跑在一个 100ms 的热循环里，每一轮要 fork 5~7 个进程
 * （date / wc / stat / grep × N），观察窗口 60 秒就是三四千次 fork。日志超过
 * 1MB 之后每轮还要 wc + grep 全文扫两遍，40MB 的日志等于每秒读 800MB。
 * 这里把循环整个搬到进程内：判定全是 syscall，日志增量读，只留三个子进程——
 * logcat、每个 getevent、以及替换后不需要的都不留。
 *
 * 和 sh 版逐字保持一致的对外行为：
 *   stdout 文案、action.pid / action-now.pid、settings.conf 的 PROTECT_COUNT、
 *   module.prop 的 description、config/.self_restart 时间戳、退出码。
 *
 * 用法：
 *   actiond [--dir <模块根>] --test <apk>   测试模式：换界面 + 等重启 + 判定
 *   actiond [--dir <模块根>] --now          常驻守护触发的一轮：界面已重启过
 *   actiond --version
 *
 * 不带 --dir 时按 /proc/self/exe 推导：<模块根>/bin/actiond → <模块根>。
 *
 * 子进程策略（已定）：logcat 与 getevent 继续用系统命令，理由见 README 讨论——
 *   struct input_event 的 timeval 在 32/64 位内核宽度不同，SELinux 能不能直读
 *   /dev/input 也因 ROM 而异，为了省两个进程去踩这些坑不划算。
 */

#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define VERSION "1.0"
#define POLL_MS 100

/* ---------------- 路径 ---------------- */

static char moddir[PATH_MAX];
static char cfgdir[PATH_MAX];
static char workdir[PATH_MAX];
static char p_conf[PATH_MAX];
static char p_prop[PATH_MAX];
static char p_selfmark[PATH_MAX];
static char p_watchpid[PATH_MAX];
static char p_pidfile[PATH_MAX];
static char p_legacy[PATH_MAX];     /* action.sh，exec 失败时回落 */
static char p_actiond[PATH_MAX];    /* 自己，留给日志 */
static char logfile[PATH_MAX + 300];
static char rescue_apk[PATH_MAX];
static char aim_apk[PATH_MAX];

/* ---------------- 配置 ---------------- */

enum {
    HAVE_TIME = 1u << 0,
    HAVE_LOGDIR = 1u << 1,
    HAVE_RETENTION = 1u << 2,
    HAVE_TIMEOUT = 1u << 3,
    HAVE_LINE = 1u << 4,
    HAVE_MODE = 1u << 5,
    HAVE_ASSESS = 1u << 6,
    HAVE_PROTECT = 1u << 7,
    HAVE_THRESHOLD = 1u << 8
};

static struct {
    char time_fmt[256];
    char log_dir[PATH_MAX];
    char line[256];
    long retention;
    long key_timeout;
    long log_mode;
    long protect;
    long threshold;
    long long flood;
    long long max_bytes;
    int assess;
    char vol_up[1024];
    char vol_down[1024];
    unsigned have;
} cfg;

/* ---------------- 运行状态 ---------------- */

static int now_mode = 0;
static char test_apk[PATH_MAX];

static pid_t log_pid = 0;
static pid_t ge_pids[16];
static int ge_fds[16];
static int ge_flags[16];
static int ge_n = 0;

static off_t log_off = 0;
static char scan_partial[4096];
static size_t scan_plen = 0;
static long fatal_cnt = 0;
static long total_lines = 0;
static long stack_lines = 0;
static long last_size = 0;
static long flood_hits = 0;
static long last_cnt = 0;

static const char *key_result = "";
static volatile sig_atomic_t want_exit = 0;

/* ---------------- 小工具 ---------------- */

static void debug(const char *fmt, ...) {
    char ts[32];
    time_t t = time(NULL);
    struct tm tm;
    localtime_r(&t, &tm);
    strftime(ts, sizeof ts, "%H:%M:%S", &tm);
    printf("%s ", ts);
    va_list ap;
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    printf("\n");
    fflush(stdout);
}

static void out(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    printf("\n");
    fflush(stdout);
}

static char *trim(char *s) {
    while (*s && isspace((unsigned char)*s)) s++;
    if (!*s) return s;
    char *e = s + strlen(s) - 1;
    while (e > s && isspace((unsigned char)*e)) *e-- = 0;
    return s;
}

static void unquote(char *s) {
    size_t n = strlen(s);
    if (n >= 2 && s[0] == '"' && s[n - 1] == '"') {
        memmove(s, s + 1, n - 2);
        s[n - 2] = 0;
    }
}

static void join(char *dst, size_t n, const char *a, const char *b) {
    snprintf(dst, n, "%s/%s", a, b);
}

static long long file_size(const char *p) {
    struct stat st;
    if (stat(p, &st) != 0) return 0;
    return (long long)st.st_size;
}

static long read_num(const char *p) {
    FILE *f = fopen(p, "r");
    if (!f) return 0;
    long v = 0;
    if (fscanf(f, "%ld", &v) != 1) v = 0;
    fclose(f);
    return v;
}

/* 递归建目录，等价 mkdir -p */
static int mkdir_p(const char *path) {
    char tmp[PATH_MAX];
    snprintf(tmp, sizeof tmp, "%s", path);
    size_t len = strlen(tmp);
    if (len == 0) return -1;
    if (tmp[len - 1] == '/') tmp[len - 1] = 0;
    for (char *p = tmp + 1; *p; p++) {
        if (*p == '/') {
            *p = 0;
            mkdir(tmp, 0755);
            *p = '/';
        }
    }
    if (mkdir(tmp, 0755) != 0 && errno != EEXIST) return -1;
    return 0;
}

/* 整文件写：先写 .tmp 再 rename，避免写一半断电留下残文件 */
static int write_atomic(const char *path, const char *data, size_t len) {
    char tmp[PATH_MAX];
    snprintf(tmp, sizeof tmp, "%s.tmp", path);
    int fd = open(tmp, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) return -1;
    size_t off = 0;
    while (off < len) {
        ssize_t w = write(fd, data + off, len - off);
        if (w <= 0) { close(fd); unlink(tmp); return -1; }
        off += (size_t)w;
    }
    if (fsync(fd) != 0) { /* 尽力而为 */ }
    close(fd);
    if (rename(tmp, path) != 0) { unlink(tmp); return -1; }
    return 0;
}

static char *read_all(const char *path, size_t *lenp) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return NULL;
    size_t cap = 65536, len = 0;
    char *buf = malloc(cap);
    if (!buf) { close(fd); return NULL; }
    for (;;) {
        if (len + 4096 > cap) {
            cap *= 2;
            char *nb = realloc(buf, cap);
            if (!nb) { free(buf); close(fd); return NULL; }
            buf = nb;
        }
        ssize_t n = read(fd, buf + len, cap - len - 1);
        if (n <= 0) break;
        len += (size_t)n;
    }
    close(fd);
    buf[len] = 0;
    if (lenp) *lenp = len;
    return buf;
}

/* 复制文件内容到已存在/新建的文件里，O_TRUNC 保 inode（bind 挂载点不能换 inode） */
static int copy_into(const char *src, const char *dst) {
    int in = open(src, O_RDONLY);
    if (in < 0) return -1;
    int outfd = open(dst, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (outfd < 0) { close(in); return -1; }
    static char buf[262144];
    int rc = 0;
    for (;;) {
        ssize_t n = read(in, buf, sizeof buf);
        if (n == 0) break;
        if (n < 0) {
            if (errno == EINTR) continue;
            rc = -1;
            break;
        }
        char *p = buf;
        ssize_t left = n;
        while (left > 0) {
            ssize_t w = write(outfd, p, (size_t)left);
            if (w < 0) {
                if (errno == EINTR) continue;
                rc = -1;
                break;
            }
            p += w;
            left -= w;
        }
        if (rc != 0) break;
    }
    close(in);
    close(outfd);
    return rc;
}

/* 扫 /proc 找 com.android.systemui，零 fork（同 watchd） */
static pid_t scan_systemui(void) {
    DIR *d = opendir("/proc");
    if (!d) return -1;
    struct dirent *e;
    pid_t found = -1;
    while ((e = readdir(d)) != NULL) {
        if (!isdigit((unsigned char)e->d_name[0])) continue;
        char path[PATH_MAX];
        snprintf(path, sizeof path, "/proc/%s/cmdline", e->d_name);
        int fd = open(path, O_RDONLY);
        if (fd < 0) continue;
        char buf[256];
        ssize_t n = read(fd, buf, sizeof buf - 1);
        close(fd);
        if (n <= 0) continue;
        buf[n] = 0;
        if (strncmp(buf, "com.android.systemui", 20) == 0) {
            found = (pid_t)atoi(e->d_name);
            break;
        }
    }
    closedir(d);
    return found;
}

/* ---------------- 时间 ---------------- */

static void fmt_time(char *outbuf, size_t n) {
    const char *f = cfg.time_fmt[0] ? cfg.time_fmt : "+%Y年%m月%d日%H:%M:%S";
    if (*f == '+') f++;
    time_t t = time(NULL);
    struct tm tm;
    localtime_r(&t, &tm);
    if (strftime(outbuf, n, f, &tm) == 0) snprintf(outbuf, n, "%ld", (long)t);
}

/* ---------------- 配置读写 ---------------- */

static long parse_long(const char *key, const char *val, int *ok) {
    char *end = NULL;
    errno = 0;
    long v = strtol(val, &end, 10);
    if (end == val || *end != 0 || v < 0 || errno != 0) {
        out("错误：%s 必须为非负整数（当前: '%s'）", key, val);
        *ok = 0;
        return 0;
    }
    *ok = 1;
    return v;
}

static void load_conf(void) {
    FILE *f = fopen(p_conf, "r");
    if (!f) return;
    char *ln = NULL;
    size_t cap = 0;
    while (getline(&ln, &cap, f) > 0) {
        char *p = trim(ln);
        if (!*p || *p == '#') continue;
        char *eq = strchr(p, '=');
        if (!eq) continue;
        *eq = 0;
        char *k = trim(p);
        char *v = trim(eq + 1);
        unquote(v);
        if (!strcmp(k, "TIME_FORMAT")) {
            snprintf(cfg.time_fmt, sizeof cfg.time_fmt, "%s", v);
            cfg.have |= HAVE_TIME;
        } else if (!strcmp(k, "LOG_DIR")) {
            snprintf(cfg.log_dir, sizeof cfg.log_dir, "%s", v);
            cfg.have |= HAVE_LOGDIR;
        } else if (!strcmp(k, "LINE")) {
            snprintf(cfg.line, sizeof cfg.line, "%s", v);
            cfg.have |= HAVE_LINE;
        } else if (!strcmp(k, "LOG_RETENTION_DAYS")) {
            int ok;
            cfg.retention = parse_long(k, v, &ok);
            if (ok) cfg.have |= HAVE_RETENTION;
        } else if (!strcmp(k, "KEY_TIMEOUT")) {
            int ok;
            cfg.key_timeout = parse_long(k, v, &ok);
            if (ok) cfg.have |= HAVE_TIMEOUT;
        } else if (!strcmp(k, "LOG_MODE")) {
            int ok;
            cfg.log_mode = parse_long(k, v, &ok);
            if (ok) cfg.have |= HAVE_MODE;
        } else if (!strcmp(k, "PROTECT_COUNT")) {
            int ok;
            cfg.protect = parse_long(k, v, &ok);
            if (ok) cfg.have |= HAVE_PROTECT;
        } else if (!strcmp(k, "FATAL_THRESHOLD")) {
            int ok;
            cfg.threshold = parse_long(k, v, &ok);
            if (ok) cfg.have |= HAVE_THRESHOLD;
        } else if (!strcmp(k, "VOLUME_CHECK_ASSESS")) {
            int ok;
            long v2 = parse_long(k, v, &ok);
            if (ok) { cfg.assess = (v2 != 0); cfg.have |= HAVE_ASSESS; }
        } else if (!strcmp(k, "LOG_FLOOD_BYTES")) {
            int ok;
            long v2 = parse_long(k, v, &ok);
            if (ok) cfg.flood = v2;
        } else if (!strcmp(k, "LOG_MAX_BYTES")) {
            int ok;
            long v2 = parse_long(k, v, &ok);
            if (ok) cfg.max_bytes = v2;
        } else if (!strcmp(k, "VOL_UP_DEV")) {
            snprintf(cfg.vol_up, sizeof cfg.vol_up, "%s", v);
        } else if (!strcmp(k, "VOL_DOWN_DEV")) {
            snprintf(cfg.vol_down, sizeof cfg.vol_down, "%s", v);
        }
    }
    free(ln);
    fclose(f);
    if (cfg.flood <= 0) cfg.flood = 2097152;
    if (cfg.max_bytes <= 0) cfg.max_bytes = 52428800;
}

/* 改 settings.conf 里的单个键：有就替换整行，没有就追加 */
static void set_conf(const char *key, const char *val, const char *path) {
    char *data = read_all(path, NULL);
    if (!data) return;
    size_t need = strlen(data) + strlen(key) + strlen(val) + 64;
    char *nb = malloc(need);
    if (!nb) { free(data); return; }
    size_t w = 0;
    int done = 0;
    char *p = data;
    while (*p) {
        char *nl = strchr(p, '\n');
        size_t len = nl ? (size_t)(nl - p) : strlen(p);
        if (len > strlen(key) && !strncmp(p, key, strlen(key)) && p[strlen(key)] == '=') {
            w += (size_t)snprintf(nb + w, need - w, "%s=%s\n", key, val);
            done = 1;
        } else {
            memcpy(nb + w, p, len);
            w += len;
            nb[w++] = '\n';
        }
        if (!nl) break;
        p = nl + 1;
    }
    if (!done) w += (size_t)snprintf(nb + w, need - w, "%s=%s\n", key, val);
    nb[w] = 0;
    write_atomic(path, nb, w);
    free(nb);
    free(data);
}

/* module.prop 里的 description —— Magisk 应用里显示的那行 */
static void update_desc(const char *desc) {
    char *data = read_all(p_prop, NULL);
    if (!data) return;
    size_t need = strlen(data) + strlen(desc) + 64;
    char *nb = malloc(need);
    if (!nb) { free(data); return; }
    size_t w = 0;
    int done = 0;
    char *p = data;
    while (*p) {
        char *nl = strchr(p, '\n');
        size_t len = nl ? (size_t)(nl - p) : strlen(p);
        if (!strncmp(p, "description=", 12)) {
            w += (size_t)snprintf(nb + w, need - w, "description=%s\n", desc);
            done = 1;
        } else {
            memcpy(nb + w, p, len);
            w += len;
            nb[w++] = '\n';
        }
        if (!nl) break;
        p = nl + 1;
    }
    if (!done) w += (size_t)snprintf(nb + w, need - w, "description=%s\n", desc);
    nb[w] = 0;
    write_atomic(p_prop, nb, w);
    free(nb);
    free(data);
}

static void set_desc(const char *status, long kb, const char *extra) {
    char ts[256];
    fmt_time(ts, sizeof ts);
    char desc[1024];
    if (extra && *extra) {
        if (kb >= 0)
            snprintf(desc, sizeof desc, "%s | 状态: %s | %s | 日志: %ldKB", ts, status, extra, kb);
        else
            snprintf(desc, sizeof desc, "%s | 状态: %s | %s", ts, status, extra);
    } else {
        if (kb >= 0)
            snprintf(desc, sizeof desc, "%s | 状态: %s | 日志: %ldKB", ts, status, kb);
        else
            snprintf(desc, sizeof desc, "%s | 状态: %s", ts, status);
    }
    update_desc(desc);
}

/* ---------------- 进程 ---------------- */

static void kill_pid(pid_t p, int sig) {
    if (p > 0) kill(p, sig);
}

static void cleanup(void) {
    if (log_pid > 0) {
        kill(log_pid, SIGTERM);
        kill(-log_pid, SIGTERM);
        log_pid = 0;
    }
    for (int i = 0; i < ge_n; i++) {
        kill_pid(ge_pids[i], SIGTERM);
        kill(-ge_pids[i], SIGTERM);
        if (ge_fds[i] >= 0) close(ge_fds[i]);
    }
    ge_n = 0;
    if (p_pidfile[0]) unlink(p_pidfile);
}

static void on_signal(int sig) {
    (void)sig;
    /* stop 里只等 0.3 秒就 SIGKILL，来不及从主循环返回，直接在这里收尾。
     * cleanup 里只做 kill/close/unlink，都是 async-signal-safe 的。 */
    cleanup();
    _exit(0);
}

/* ---------------- 音量键设备识别 ---------------- */

/* hex 能力位里的第 bit 位：把空格分隔的 hex 串拼成一个大数，
 * 最低位在最右，等价于 sh 版 _hasbit_w 那一串字符移位 */
static int has_bit(const char *map, int bit) {
    char hex[512];
    size_t n = 0;
    for (const char *p = map; *p && n < sizeof hex - 1; p++) {
        if (isxdigit((unsigned char)*p)) hex[n++] = *p;
    }
    hex[n] = 0;
    if (n == 0) return 0;
    long idx = (long)n - 1 - bit / 4;
    if (idx < 0) return 0;
    int v = isdigit((unsigned char)hex[idx]) ? hex[idx] - '0'
                                             : (tolower((unsigned char)hex[idx]) - 'a' + 10);
    return (v >> (bit % 4)) & 1;
}

static int add_dev(char *list, size_t n, const char *dev) {
    if (!dev || !*dev) return 0;
    char needle[PATH_MAX + 3];
    snprintf(needle, sizeof needle, " %s ", dev);
    char hay[2048];
    snprintf(hay, sizeof hay, " %s ", list);
    if (strstr(hay, needle)) return 0;
    size_t cur = strlen(list);
    if (cur && cur + strlen(dev) + 2 < n)
        snprintf(list + cur, n - cur, " %s", dev);
    else if (!cur)
        snprintf(list, n, "%s", dev);
    return 1;
}

static void parse_input_devs(char *up, size_t upn, char *down, size_t dn) {
    up[0] = 0;
    down[0] = 0;

    /* 优先用 check.sh 实测写进配置的设备 */
    if (cfg.vol_up[0] || cfg.vol_down[0]) {
        snprintf(up, upn, "%s", cfg.vol_up);
        snprintf(down, dn, "%s", cfg.vol_down);
        return;
    }

    /* 方式1：内核 sysfs 能力位 */
    DIR *d = opendir("/sys/class/input");
    if (d) {
        struct dirent *e;
        while ((e = readdir(d)) != NULL) {
            if (strncmp(e->d_name, "event", 5) != 0) continue;
            char base[PATH_MAX + 32], kb[1024] = "";
            char path[PATH_MAX];
            join(path, sizeof path, "/sys/class/input", e->d_name);
            int fd;
            snprintf(base, sizeof base, "%s/device/capabilities/key", path);
            fd = open(base, O_RDONLY);
            if (fd < 0) {
                snprintf(base, sizeof base, "%s/capabilities/key", path);
                fd = open(base, O_RDONLY);
            }
            if (fd < 0) continue;
            ssize_t r = read(fd, kb, sizeof kb - 1);
            close(fd);
            if (r <= 0) continue;
            kb[r] = 0;
            char dev[PATH_MAX];
            snprintf(dev, sizeof dev, "/dev/input/%s", e->d_name);
            if (has_bit(kb, 115)) add_dev(up, upn, dev);
            if (has_bit(kb, 114)) add_dev(down, dn, dev);
        }
        closedir(d);
    }
    if (up[0] || down[0]) return;

    /* 方式2：/proc/bus/input/devices */
    char *devs = read_all("/proc/bus/input/devices", NULL);
    if (devs) {
        char handlers[2048] = "", keymap[2048] = "";
        char *p = devs;
        while (*p) {
            char *nl = strchr(p, '\n');
            size_t len = nl ? (size_t)(nl - p) : strlen(p);
            char line[2048];
            if (len >= sizeof line) len = sizeof line - 1;
            memcpy(line, p, len);
            line[len] = 0;
            if (!strncmp(line, "I: ", 3)) {
                /* 一个设备结束，结算上一段 */
                if (keymap[0]) {
                    char ev[PATH_MAX] = "";
                    char *tok = strtok(handlers, " ");
                    while (tok) {
                        if (!strncmp(tok, "event", 5)) {
                            snprintf(ev, sizeof ev, "/dev/input/%s", tok);
                            break;
                        }
                        tok = strtok(NULL, " ");
                    }
                    if (ev[0]) {
                        if (has_bit(keymap, 115)) add_dev(up, upn, ev);
                        if (has_bit(keymap, 114)) add_dev(down, dn, ev);
                    }
                }
                handlers[0] = 0;
                keymap[0] = 0;
            } else if (!strncmp(line, "H: Handlers=", 12)) {
                snprintf(handlers, sizeof handlers, "%s", line + 12);
            } else if (!strncmp(line, "B: KEY=", 7)) {
                snprintf(keymap, sizeof keymap, "%s", line + 7);
            }
            if (!nl) break;
            p = nl + 1;
        }
        if (keymap[0]) {
            char ev[PATH_MAX] = "";
            char *tok = strtok(handlers, " ");
            while (tok) {
                if (!strncmp(tok, "event", 5)) {
                    snprintf(ev, sizeof ev, "/dev/input/%s", tok);
                    break;
                }
                tok = strtok(NULL, " ");
            }
            if (ev[0]) {
                if (has_bit(keymap, 115)) add_dev(up, upn, ev);
                if (has_bit(keymap, 114)) add_dev(down, dn, ev);
            }
        }
        free(devs);
    }
    if (up[0] || down[0]) return;

    /* 方式3：getevent -pl */
    int pfd[2];
    if (pipe(pfd) == 0) {
        pid_t c = fork();
        if (c == 0) {
            close(pfd[0]);
            dup2(pfd[1], STDOUT_FILENO);
            dup2(pfd[1], STDERR_FILENO);
            if (pfd[1] > 2) close(pfd[1]);
            execl("/system/bin/getevent", "getevent", "-pl", (char *)NULL);
            execlp("getevent", "getevent", "-pl", (char *)NULL);
            _exit(127);
        }
        close(pfd[1]);
        char *buf = NULL;
        size_t cap = 0, len = 0;
        if (c > 0) {
            char tmp[8192];
            ssize_t n;
            while ((n = read(pfd[0], tmp, sizeof tmp)) > 0) {
                if (len + (size_t)n + 1 > cap) {
                    cap = (cap ? cap * 2 : 16384);
                    while (cap < len + (size_t)n + 1) cap *= 2;
                    buf = realloc(buf, cap);
                    if (!buf) break;
                }
                memcpy(buf + len, tmp, (size_t)n);
                len += (size_t)n;
            }
            int st;
            waitpid(c, &st, 0);
        }
        close(pfd[0]);
        if (buf) {
            buf[len] = 0;
            char cur[PATH_MAX] = "";
            int u = 0, v = 0;
            char *p = buf;
            while (*p) {
                char *nl = strchr(p, '\n');
                size_t l = nl ? (size_t)(nl - p) : strlen(p);
                char line[2048];
                if (l >= sizeof line) l = sizeof line - 1;
                memcpy(line, p, l);
                line[l] = 0;
                if (!strncmp(line, "add device ", 11)) {
                    if (cur[0]) {
                        if (u) add_dev(up, upn, cur);
                        if (v) add_dev(down, dn, cur);
                    }
                    const char *colon = strrchr(line, ':');
                    snprintf(cur, sizeof cur, "%s", colon ? colon + 1 : line + 11);
                    char *t = trim(cur);
                    memmove(cur, t, strlen(t) + 1);
                    u = v = 0;
                } else if (strstr(line, "KEY_VOLUMEUP")) {
                    u = 1;
                } else if (strstr(line, "KEY_VOLUMEDOWN")) {
                    v = 1;
                }
                if (!nl) break;
                p = nl + 1;
            }
            if (cur[0]) {
                if (u) add_dev(up, upn, cur);
                if (v) add_dev(down, dn, cur);
            }
            free(buf);
        }
    }
}

/* ---------------- getevent 监听 ---------------- */

/* 起一个 getevent，fd 存进全局表。flags: 1=盯音量上 2=盯音量下 */
static int ge_add(const char *dev, int flags) {
    int pfd[2];
    if (pipe(pfd) != 0) return -1;
    pid_t c = fork();
    if (c == 0) {
        close(pfd[0]);
        dup2(pfd[1], STDOUT_FILENO);
        dup2(pfd[1], STDERR_FILENO);
        if (pfd[1] > 2) close(pfd[1]);
        setsid();
        execl("/system/bin/getevent", "getevent", "-l", dev, (char *)NULL);
        execlp("getevent", "getevent", "-l", dev, (char *)NULL);
        _exit(127);
    }
    close(pfd[1]);
    if (c < 0) { close(pfd[0]); return -1; }
    int fl = fcntl(pfd[0], F_GETFL, 0);
    fcntl(pfd[0], F_SETFL, fl | O_NONBLOCK);
    if (ge_n >= (int)(sizeof ge_pids / sizeof ge_pids[0])) {
        close(pfd[0]);
        kill(c, SIGTERM);
        return -1;
    }
    ge_pids[ge_n] = c;
    ge_fds[ge_n] = pfd[0];
    ge_flags[ge_n] = flags;
    ge_n++;
    return 0;
}

static void ge_stop_all(void) {
    for (int i = 0; i < ge_n; i++) {
        kill(ge_pids[i], SIGTERM);
        kill(-ge_pids[i], SIGTERM);
    }
    usleep(200000);
    for (int i = 0; i < ge_n; i++) {
        kill(ge_pids[i], SIGKILL);
        kill(-ge_pids[i], SIGKILL);
        waitpid(ge_pids[i], NULL, WNOHANG);
        if (ge_fds[i] >= 0) close(ge_fds[i]);
    }
    ge_n = 0;
}

/* 上下两个设备集合经常指向同一个 /dev/input/eventN（一台设备同时声明两个键）。
 * 不能分两趟读：第一趟会把管子里属于另一方向的事件一起吸走扔掉。一趟读完，
 * 按每个 fd 的标记分别判断。 */
static void ge_poll(int *up, int *down) {
    static char tmp[8192];
    for (int i = 0; i < ge_n; i++) {
        if (ge_fds[i] < 0) continue;
        for (;;) {
            ssize_t n = read(ge_fds[i], tmp, sizeof tmp - 1);
            if (n <= 0) break;
            tmp[n] = 0;
            if ((ge_flags[i] & 1) && strstr(tmp, "KEY_VOLUMEUP")) *up = 1;
            if ((ge_flags[i] & 2) && strstr(tmp, "KEY_VOLUMEDOWN")) *down = 1;
        }
    }
}

/* 首次运行：让人按一次音量上、一次音量下，确认设备找得对。
 * 用独立的进程表，不污染主监听的全局数组。 */
static int verify_key(const char *name, const char *want, const char *devs,
                      const char *other) {
    if (!*devs) return 0;
    pid_t pids[8];
    int fds[8];
    int n = 0;
    char list[1024];
    snprintf(list, sizeof list, "%s", devs);
    char *tok = strtok(list, " ");
    while (tok && n < 8) {
        int pfd[2];
        if (pipe(pfd) != 0) break;
        pid_t c = fork();
        if (c == 0) {
            close(pfd[0]);
            dup2(pfd[1], STDOUT_FILENO);
            dup2(pfd[1], STDERR_FILENO);
            if (pfd[1] > 2) close(pfd[1]);
            setsid();
            execl("/system/bin/getevent", "getevent", "-l", tok, (char *)NULL);
            execlp("getevent", "getevent", "-l", tok, (char *)NULL);
            _exit(127);
        }
        close(pfd[1]);
        if (c < 0) { close(pfd[0]); break; }
        int fl = fcntl(pfd[0], F_GETFL, 0);
        fcntl(pfd[0], F_SETFL, fl | O_NONBLOCK);
        pids[n] = c;
        fds[n] = pfd[0];
        n++;
        tok = strtok(NULL, " ");
    }
    if (n == 0) return 0;

    static char sink[65536];
    size_t slen = 0;
    sink[0] = 0;
    int found = 0, warned = 0;
    for (int round = 0; round < 100 && !found; round++) {
        for (int i = 0; i < n; i++) {
            char tmp[4096];
            for (;;) {
                ssize_t r = read(fds[i], tmp, sizeof tmp - 1);
                if (r <= 0) break;
                tmp[r] = 0;
                if (slen + (size_t)r + 1 < sizeof sink) {
                    memcpy(sink + slen, tmp, (size_t)r);
                    slen += (size_t)r;
                    sink[slen] = 0;
                }
                if (strstr(tmp, want)) found = 1;
                if (!warned && other && strstr(tmp, other)) {
                    warned = 1;
                    out("  按到的是另一个键，请按【%s】", name);
                }
            }
        }
        if (found) break;
        usleep(100000);
    }
    for (int i = 0; i < n; i++) kill(pids[i], SIGTERM);
    usleep(200000);
    for (int i = 0; i < n; i++) {
        kill(pids[i], SIGKILL);
        waitpid(pids[i], NULL, WNOHANG);
        close(fds[i]);
    }

    if (found) {
        char *p = sink;
        while (*p) {
            char *nl = strchr(p, '\n');
            size_t len = nl ? (size_t)(nl - p) : strlen(p);
            char line[4096];
            if (len >= sizeof line) len = sizeof line - 1;
            memcpy(line, p, len);
            line[len] = 0;
            if (strstr(line, want)) out("    %s", line);
            if (!nl) break;
            p = nl + 1;
        }
        return 1;
    }
    return 0;
}

/* ---------------- 覆盖待测 apk ---------------- */

static int cover_test_apk(void) {
    out("开始替换界面…");
    char tmp[PATH_MAX];
    snprintf(tmp, sizeof tmp, "/data/adb/tmp/_test_%d.apk", (int)getpid());
    mkdir_p("/data/adb/tmp");

    /* 挂载点是单文件 bind，绑的是 inode；源被换成新 inode 之后就再也写不进去，
     * 这就是「换什么包都不重启」的根因。开测前先比对，不一致就重新绑。 */
    char src[PATH_MAX];
    if (!realpath(aim_apk, src)) snprintf(src, sizeof src, "%s", aim_apk);
    struct stat sm, sa;
    if (stat(aim_apk, &sa) == 0 && stat(src, &sm) == 0) {
        if (sm.st_ino != sa.st_ino) {
            out("挂载已脱离（挂载点 %lu ≠ 源 %lu），重新绑定",
                (unsigned long)sa.st_ino, (unsigned long)sm.st_ino);
            umount2(aim_apk, 0);
            if (mount(src, aim_apk, NULL, MS_BIND, NULL) == 0) {
                out("重新绑定完成");
            } else {
                out("错误：重新绑定失败，界面无法替换");
                return -1;
            }
        }
    }

    if (copy_into(test_apk, tmp) != 0) {
        out("错误：复制到临时目录失败");
        return -1;
    }

    long long nsz = file_size(tmp);
    long long osz = file_size(aim_apk);
    out("大小: %lld -> %lld", osz, nsz);

    /* 一律走 truncate + 写：不换 inode，bind 挂载点才不会跟着失联。
     * sh 版分 dd / cat 两种，C 里 open(O_TRUNC) 两者等价。 */
    if (copy_into(tmp, aim_apk) != 0) {
        unlink(tmp);
        out("错误：写入界面失败");
        return -1;
    }
    long long actual = file_size(aim_apk);
    unlink(tmp);
    if (actual != nsz) {
        out("错误：覆盖后大小不符（期望 %lld，实际 %lld）", nsz, actual);
        return -1;
    }
    chmod(aim_apk, 0644);
    out("替换完成，等待界面重启");
    return 0;
}

/* ---------------- logcat ---------------- */

static pid_t start_logcat(void) {
    pid_t c = fork();
    if (c == 0) {
        int fd = open(logfile, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (fd >= 0) {
            dup2(fd, STDOUT_FILENO);
            dup2(fd, STDERR_FILENO);
            if (fd > STDERR_FILENO) close(fd);
        }
        setsid();
        execl("/system/bin/logcat", "logcat", "-b", "crash", (char *)NULL);
        execlp("logcat", "logcat", "-b", "crash", (char *)NULL);
        _exit(127);
    }
    return c;
}

static void stop_logcat(void) {
    if (log_pid <= 0) return;
    kill(log_pid, SIGTERM);
    kill(-log_pid, SIGTERM);
    usleep(400000);
    kill(log_pid, SIGKILL);
    kill(-log_pid, SIGKILL);
    waitpid(log_pid, NULL, WNOHANG);
    log_pid = 0;
}

static void clear_crash(void) {
    pid_t c = fork();
    if (c == 0) {
        int fd = open("/dev/null", O_WRONLY);
        if (fd >= 0) { dup2(fd, STDOUT_FILENO); dup2(fd, STDERR_FILENO); }
        execl("/system/bin/logcat", "logcat", "-c", "-b", "crash", (char *)NULL);
        execlp("logcat", "logcat", "-c", "-b", "crash", (char *)NULL);
        _exit(127);
    }
    if (c > 0) waitpid(c, NULL, 0);
}

/* 等 PID 变化期间查 ANR：进程还在但界面已废 */
static int anr_seen(const char *since) {
    int pfd[2];
    if (pipe(pfd) != 0) return 0;
    pid_t c = fork();
    if (c == 0) {
        close(pfd[0]);
        dup2(pfd[1], STDOUT_FILENO);
        dup2(pfd[1], STDERR_FILENO);
        if (pfd[1] > 2) close(pfd[1]);
        execl("/system/bin/logcat", "logcat", "-b", "events", "-d",
              "-s", "am_anr", "-T", since, (char *)NULL);
        execlp("logcat", "logcat", "-b", "events", "-d",
               "-s", "am_anr", "-T", since, (char *)NULL);
        _exit(127);
    }
    close(pfd[1]);
    int hit = 0;
    if (c > 0) {
        char buf[8192];
        ssize_t n;
        while ((n = read(pfd[0], buf, sizeof buf - 1)) > 0) {
            buf[n] = 0;
            if (strstr(buf, "com.android.systemui")) hit = 1;
        }
        close(pfd[0]);
        waitpid(c, NULL, 0);
    } else {
        close(pfd[0]);
    }
    return hit;
}

/* ---------------- 日志增量扫描 ---------------- */

static int is_fatal_line(const char *l) {
    if (strstr(l, "Process: com.android.systemui")) return 1;
    if (strstr(l, "Process com.android.systemui has died")) return 1;
    if (strstr(l, "WATCHDOG KILLING")) return 1;
    if (strstr(l, "ANR in com.android.systemui")) return 1;
    if (strstr(l, "Fatal signal") && strstr(l, "systemui")) return 1;
    if (strstr(l, "FATAL SIGNAL") && strstr(l, "systemui")) return 1;
    return 0;
}

static void handle_line(const char *l) {
    total_lines++;
    if (strstr(l, "AndroidRuntime: \tat ")) stack_lines++;
    if (is_fatal_line(l)) fatal_cnt++;
}

static long long scan_log(void) {
    int fd = open(logfile, O_RDONLY);
    if (fd < 0) return 0;
    struct stat st;
    if (fstat(fd, &st) != 0) { close(fd); return 0; }
    long long size = (long long)st.st_size;

    /* 文件被截断过（体积上限重启 logcat）→ 统计清零重来 */
    if (size < (long long)log_off) {
        log_off = 0;
        scan_plen = 0;
        total_lines = 0;
        stack_lines = 0;
    }
    if (size == (long long)log_off) { close(fd); return size; }
    if (lseek(fd, log_off, SEEK_SET) < 0) { close(fd); return size; }

    static char buf[65536];
    ssize_t n;
    while ((n = read(fd, buf, sizeof buf)) > 0) {
        log_off += n;
        for (ssize_t i = 0; i < n; i++) {
            char ch = buf[i];
            if (ch == '\n') {
                scan_partial[scan_plen] = 0;
                handle_line(scan_partial);
                scan_plen = 0;
            } else if (scan_plen < sizeof scan_partial - 1) {
                scan_partial[scan_plen++] = ch;
            }
            /* 超长行截断丢弃：日志里没有这么长的有效行 */
        }
    }
    close(fd);
    return size;
}

/* ---------------- 主流程 ---------------- */

static void finish(const char *status, int code) {
    long kb = (long)(file_size(logfile) / 1024);
    set_desc(status, kb, NULL);
    cleanup();
    exit(code);
}

/* 守护在跑吗（读 scripts/.work/watch.pid） */
static int watch_running(void) {
    long p = read_num(p_watchpid);
    if (p <= 0) return 0;
    return kill((pid_t)p, 0) == 0;
}

static void mark_self_restart(void) {
    char buf[32];
    snprintf(buf, sizeof buf, "%ld\n", (long)time(NULL));
    write_atomic(p_selfmark, buf, strlen(buf));
}

static void usage(void) {
    printf("actiond %s\n", VERSION);
    printf("用法: actiond [--dir <模块根>] [--test <apk>|--now]\n");
    printf("      actiond --version\n");
}

static int dir_of_exe(char *out, size_t n) {
    char exe[PATH_MAX];
    ssize_t r = readlink("/proc/self/exe", exe, sizeof exe - 1);
    if (r <= 0) return -1;
    exe[r] = 0;
    /* <模块根>/bin/actiond → 去掉两层 */
    char *slash = strrchr(exe, '/');
    if (!slash) return -1;
    *slash = 0;
    slash = strrchr(exe, '/');
    if (!slash) return -1;
    *slash = 0;
    snprintf(out, n, "%s", exe);
    return 0;
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IOLBF, 0);

    char dirarg[PATH_MAX] = "";
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--version")) {
            printf("actiond %s\n", VERSION);
            return 0;
        } else if (!strcmp(argv[i], "--dir") && i + 1 < argc) {
            snprintf(dirarg, sizeof dirarg, "%s", argv[++i]);
        } else if (!strcmp(argv[i], "--test")) {
            if (i + 1 < argc) snprintf(test_apk, sizeof test_apk, "%s", argv[++i]);
        } else if (!strncmp(argv[i], "--test=", 7)) {
            snprintf(test_apk, sizeof test_apk, "%s", argv[i] + 7);
        } else if (!strcmp(argv[i], "--now")) {
            now_mode = 1;
        } else if (!strcmp(argv[i], "-h") || !strcmp(argv[i], "--help")) {
            usage();
            return 0;
        }
    }

    if (dirarg[0]) {
        snprintf(moddir, sizeof moddir, "%s", dirarg);
    } else if (dir_of_exe(moddir, sizeof moddir) != 0) {
        snprintf(moddir, sizeof moddir, "%s", ".");
    }

    join(cfgdir, sizeof cfgdir, moddir, "config");
    join(workdir, sizeof workdir, moddir, "scripts/.work");
    join(p_conf, sizeof p_conf, cfgdir, "settings.conf");
    join(p_prop, sizeof p_prop, moddir, "module.prop");
    join(p_selfmark, sizeof p_selfmark, cfgdir, ".self_restart");
    join(p_watchpid, sizeof p_watchpid, workdir, "watch.pid");
    join(p_legacy, sizeof p_legacy, moddir, "action.sh");
    join(p_actiond, sizeof p_actiond, moddir, "bin/actiond");
    join(rescue_apk, sizeof rescue_apk, moddir, "MiuiSystemUI.apk");
    join(aim_apk, sizeof aim_apk, moddir, "system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk");
    join(p_pidfile, sizeof p_pidfile, cfgdir, now_mode ? "action-now.pid" : "action.pid");

    if (test_apk[0] && access(test_apk, R_OK) != 0) {
        out("错误：待测文件不存在: %s", test_apk);
        return 1;
    }

    /* 单实例：同一时刻只跑一个，--now 用独立 pid 文件（不抢用户手点的测试） */
    if (!now_mode) {
        long old = read_num(p_pidfile);
        if (old > 0 && old != (long)getpid() && kill((pid_t)old, 0) == 0) {
            out("检测到已有救砖进程 (PID %ld)，正在终止...", old);
            kill((pid_t)old, SIGTERM);
            usleep(500000);
            kill((pid_t)old, SIGKILL);
            usleep(200000);
        }
        unlink(p_pidfile);
    }
    {
        char buf[32];
        snprintf(buf, sizeof buf, "%d\n", (int)getpid());
        write_atomic(p_pidfile, buf, strlen(buf));
    }

    load_conf();

    /* 必需项：和 sh 版一样，缺一个就拒绝运行，免得半套流程跑起来 */
    {
        char missing[512] = "";
        struct { unsigned bit; const char *name; } reqs[] = {
            {HAVE_TIME, "TIME_FORMAT"},
            {HAVE_LOGDIR, "LOG_DIR"},
            {HAVE_RETENTION, "LOG_RETENTION_DAYS"},
            {HAVE_TIMEOUT, "KEY_TIMEOUT"},
            {HAVE_LINE, "LINE"},
            {HAVE_MODE, "LOG_MODE"},
            {HAVE_ASSESS, "VOLUME_CHECK_ASSESS"},
            {HAVE_PROTECT, "PROTECT_COUNT"},
            {HAVE_THRESHOLD, "FATAL_THRESHOLD"},
        };
        for (size_t i = 0; i < sizeof reqs / sizeof reqs[0]; i++) {
            if (!(cfg.have & reqs[i].bit)) {
                size_t l = strlen(missing);
                snprintf(missing + l, sizeof missing - l, " %s", reqs[i].name);
            }
        }
        if (missing[0]) {
            out("错误：模块文件不完整，缺少以下项：");
            usleep(1000000); /* 部分 root 管理器不延迟看不到输出 */
            out("  配置项:%s", missing);
            out("  → 补全 %s 后重新运行", p_conf);
            cleanup();
            return 1;
        }
    }

    signal(SIGTERM, on_signal);
    signal(SIGINT, on_signal);
    atexit(cleanup);

    /* 首次运行先校验音量键设备（和 sh 版一样在校验 LOG_MODE 之前） */
    if (!cfg.assess) {
        out("%s", cfg.line);
        out("首次运行，校验音量键设备");
        out("%s", cfg.line);

        char up[1024], down[1024];
        parse_input_devs(up, sizeof up, down, sizeof down);
        out("音量+ 识别设备: %s", up[0] ? up : "未找到");
        out("音量- 识别设备: %s", down[0] ? down : "未找到");
        out("%s", cfg.line);

        int fail = 0;
        if (up[0]) {
            out("请按一次【音量上】");
            if (verify_key("音量上", "KEY_VOLUMEUP", up, "KEY_VOLUMEDOWN"))
                out("音量+ 校验成功");
            else { out("音量+ 未检测到"); fail = 1; }
        } else {
            out("未识别到音量+设备");
            fail = 1;
        }
        if (down[0]) {
            out("请按一次【音量下】");
            if (verify_key("音量下", "KEY_VOLUMEDOWN", down, "KEY_VOLUMEUP"))
                out("音量- 校验成功");
            else { out("音量- 未检测到"); fail = 1; }
        } else {
            out("未识别到音量-设备");
            fail = 1;
        }

        if (fail) {
            out("%s", cfg.line);
            out("音量键设备不可用，降级为仅日志模式");
            out("%s", cfg.line);
            cfg.log_mode = 1;
        } else {
            set_conf("VOLUME_CHECK_ASSESS", "1", p_conf);
            out("音量键校验完成");
            usleep(1000000);
        }
    }

    if (cfg.log_mode == 0) {
        out("日志已禁用");
        usleep(1000000);
        cleanup();
        return 0;
    }

    if (!cfg.log_dir[0]) {
        out("%s", cfg.line);
        out("未设置日志目录，无法运行");
        out("请先在 App 的「设置 → 日志目录」里选一个目录，再重新运行");
        out("%s", cfg.line);
        usleep(3000000);
        cleanup();
        return 1;
    }
    mkdir_p(cfg.log_dir);

    {
        char ts[256];
        fmt_time(ts, sizeof ts);
        snprintf(logfile, sizeof logfile, "%s/%s.log", cfg.log_dir, ts);
    }

    out("%s", cfg.line);
    out("救砖模块启动");
    out("%s", cfg.line);
    usleep(1000000);

    switch (cfg.log_mode) {
        case 1: out("模式: 仅记录日志"); break;
        case 2: out("模式: 日志 + 救砖"); break;
        default:
            if (cfg.log_mode >= 3) out("模式: 日志 + 自动救砖");
            break;
    }
    out("%s", cfg.line);
    debug("救砖模块启动");

    if (access(rescue_apk, R_OK) != 0) {
        out("未找到 MiuiSystemUI.apk");
        out("请将救砖文件放入模块根目录");
        debug("救砖文件不存在");
        cleanup();
        return 1;
    }
    debug("救砖文件存在");
    out("累计救砖: %ld次", cfg.protect);
    debug("救砖: %ld", cfg.protect);

    /* 清过期日志 */
    {
        DIR *d = opendir(cfg.log_dir);
        long cnt = 0;
        if (d) {
            struct dirent *e;
            time_t now = time(NULL);
            while ((e = readdir(d)) != NULL) {
                size_t l = strlen(e->d_name);
                if (l < 5 || strcmp(e->d_name + l - 4, ".log") != 0) continue;
                char fp[PATH_MAX + 300];
                snprintf(fp, sizeof fp, "%s/%s", cfg.log_dir, e->d_name);
                struct stat st;
                if (stat(fp, &st) != 0 || !S_ISREG(st.st_mode)) continue;
                if ((now - st.st_mtime) / 86400 > cfg.retention) {
                    if (unlink(fp) == 0) cnt++;
                }
            }
            closedir(d);
        }
        if (cnt > 0) out("清理 %ld 个 %ld 天前的日志", cnt, cfg.retention);
    }

    pid_t old_pid = scan_systemui();
    if (old_pid > 0) debug("基线 PID: %d", (int)old_pid);
    else debug("基线 PID: 未运行");

    int anr_mode = 0;

    if (now_mode) {
        out("%s", cfg.line);
        out("常驻救砖：检测到界面重启，开始判定");
        out("%s", cfg.line);
    } else {
        /* 全局守护在跑：这一轮只负责把界面换进去，判定交给它 */
        if (test_apk[0] && watch_running()) {
            out("%s", cfg.line);
            out("常驻救砖已开启：本轮只替换界面");
            out("%s", cfg.line);
            if (cover_test_apk() == 0) {
                mark_self_restart();
                out("%s", cfg.line);
                out("替换完成，后续交给全局守护");
                out("界面重启后由守护继续观察，达到阈值自动救砖");
                debug("测试：仅替换，交给守护");
                cleanup();
                return 0;
            }
            out("替换失败，本轮未交给守护");
            debug("测试：替换失败");
            cleanup();
            return 1;
        }

        out("%s", cfg.line);
        if (test_apk[0]) out("测试模式：立即替换界面...");
        else out("等待替换界面（%ld秒超时）...", cfg.key_timeout);
        out("%s", cfg.line);

        pid_t cover_pid = 0;
        if (test_apk[0]) {
            cover_pid = fork();
            if (cover_pid == 0) {
                int rc = cover_test_apk();
                _exit(rc == 0 ? 0 : 1);
            }
        }

        char since[64];
        {
            time_t t = time(NULL);
            struct tm tm;
            localtime_r(&t, &tm);
            strftime(since, sizeof since, "%m-%d %H:%M:%S", &tm);
        }
        int changed = 0;
        for (long w = 0; w < cfg.key_timeout; w++) {
            sleep(1);
            pid_t np = scan_systemui();
            if (np > 0 && np != old_pid) {
                debug("PID变化: %d -> %d | %ld秒", old_pid > 0 ? (int)old_pid : 0, (int)np, w + 1);
                changed = 1;
                break;
            }
            if (anr_seen(since)) {
                debug("检测到 ANR（%ld秒），界面判定为已崩", w + 1);
                anr_mode = 1;
                changed = 1;
                break;
            }
        }
        if (!changed) {
            debug("等待替换超时（%ld秒），退出", cfg.key_timeout);
            out("等待替换界面超时（%ld秒），未检测到 SystemUI 重启", cfg.key_timeout);
            out("FAIL 界面没有重启：排查日志目录，或手动结束一次系统界面进程后重试");
            if (cover_pid > 0) { kill(cover_pid, SIGTERM); waitpid(cover_pid, NULL, WNOHANG); }
            cleanup();
            return 1;
        }
        if (cover_pid > 0) waitpid(cover_pid, NULL, WNOHANG);
    }

    /* ===== 抓日志 ===== */
    {
        int fd = open(logfile, O_WRONLY | O_CREAT | O_APPEND, 0644);
        if (fd >= 0) close(fd);
    }
    if (now_mode || anr_mode) {
        debug("保留现有 crash 缓冲");
    } else {
        clear_crash();
    }
    log_pid = start_logcat();
    debug("logcat启动 PID: %d", (int)log_pid);

    if (cfg.log_mode == 1) {
        sleep((unsigned)cfg.key_timeout);
        long kb = (long)(file_size(logfile) / 1024);
        stop_logcat();
        set_desc("仅日志", kb, NULL);
        debug("仅日志退出");
        cleanup();
        return 0;
    }

    /* ===== 等按键 / 自动救砖 ===== */
    time_t deadline = 0;
    if (now_mode) {
        deadline = time(NULL) + cfg.key_timeout;
        debug("常驻救砖：观察窗口 %lds，阈值 %ld", cfg.key_timeout, cfg.threshold);
    }

    char up[1024], down[1024];
    parse_input_devs(up, sizeof up, down, sizeof down);
    debug("音量+设备: %s", up[0] ? up : "未找到");
    debug("音量-设备: %s", down[0] ? down : "未找到");

    {
        char list[1024];
        snprintf(list, sizeof list, "%s", up);
        char *tok = strtok(list, " ");
        while (tok) {
            ge_add(tok, 1);
            debug("音量+监听: %s", tok);
            tok = strtok(NULL, " ");
        }
        snprintf(list, sizeof list, "%s", down);
        tok = strtok(list, " ");
        while (tok) {
            ge_add(tok, 2);
            debug("音量-监听: %s", tok);
            tok = strtok(NULL, " ");
        }
    }

    int key_up = 0, key_down = 0;
    last_size = (long)file_size(logfile);

    while (!want_exit) {
        if (deadline > 0 && time(NULL) >= deadline) {
            debug("常驻观察窗口 %ld 秒结束，未判定到崩溃", cfg.key_timeout);
            stop_logcat();
            finish("常驻窗口结束", 0);
        }

        long long now_size = scan_log();

        if (cfg.log_mode >= 3) {
            if (fatal_cnt > 0 && fatal_cnt != last_cnt) {
                debug("崩溃计数: %ld/%ld", fatal_cnt, cfg.threshold);
                last_cnt = fatal_cnt;
            }
            if (fatal_cnt >= cfg.threshold) {
                key_result = "AUTO_CRASH";
                debug("累计 fatal %ld 次，自动触发救砖", fatal_cnt);
                break;
            }

            /* 黑屏卡死：PID 不变但日志疯长 */
            long long growth = now_size - last_size;
            if (growth > cfg.flood) {
                flood_hits++;
                debug("日志疯长第 %ld 次: +%lld 字节", flood_hits, growth);
                if (flood_hits >= 2) {
                    key_result = "LOG_FLOOD";
                    debug("判定黑屏卡死（日志疯长），触发救砖");
                    break;
                }
            } else {
                flood_hits = 0;
            }
            last_size = (long)now_size;

            /* StackOverflow：堆栈行占八成以上 */
            if (now_size > 1048576 && total_lines > 0 && stack_lines > 5000) {
                if (stack_lines * 100 / total_lines > 80) {
                    key_result = "STACK_OVERFLOW";
                    debug("判定 StackOverflow（堆栈 %ld/%ld 行），触发救砖", stack_lines, total_lines);
                    break;
                }
            }

            /* 日志超上限：重启 logcat，防止写爆存储 */
            if (now_size > cfg.max_bytes) {
                debug("日志超过上限 %lld，重启 logcat", cfg.max_bytes);
                stop_logcat();
                int fd = open(logfile, O_WRONLY | O_CREAT | O_TRUNC, 0644);
                if (fd >= 0) close(fd);
                log_off = 0;
                scan_plen = 0;
                total_lines = 0;
                stack_lines = 0;
                log_pid = start_logcat();
                last_size = 0;
                flood_hits = 0;
            }
        }

        {
            int ku = 0, kd = 0;
            ge_poll(&ku, &kd);
            if (ku) { key_up = 1; break; }
            if (kd) { key_down = 1; break; }
        }

        usleep(POLL_MS * 1000);
    }

    if (key_up) key_result = "KEY_VOLUMEUP";
    else if (key_down) key_result = "KEY_VOLUMEDOWN";
    else if (want_exit) { stop_logcat(); cleanup(); return 0; }

    ge_stop_all();

    int do_rescue = 0;
    if (!strcmp(key_result, "AUTO_CRASH")) {
        debug("auto crash");
        do_rescue = 1;
        usleep(300000);
    } else if (!strcmp(key_result, "LOG_FLOOD")) {
        out("检测到界面卡死（日志疯长）");
        debug("日志疯长救砖");
        do_rescue = 1;
        usleep(300000);
    } else if (!strcmp(key_result, "STACK_OVERFLOW")) {
        out("检测到堆栈溢出（界面卡死）");
        debug("堆栈溢出救砖");
        do_rescue = 1;
        usleep(300000);
    } else if (!strcmp(key_result, "KEY_VOLUMEDOWN")) {
        out("已取消");
        debug("音量-");
        do_rescue = 0;
        usleep(300000);
    } else if (!strcmp(key_result, "KEY_VOLUMEUP")) {
        debug("音量+");
        do_rescue = 1;
        usleep(300000);
    }

    stop_logcat();

    if (!do_rescue) {
        debug("取消退出");
        finish("已取消", 0);
    }

    debug("执行替换");
    if (access(rescue_apk, R_OK) != 0) {
        debug("文件丢失");
        finish("文件丢失", 1);
    }

    {
        char dir[PATH_MAX];
        snprintf(dir, sizeof dir, "%s", aim_apk);
        char *slash = strrchr(dir, '/');
        if (slash) { *slash = 0; mkdir_p(dir); }
    }

    /* 先备份再写，写坏了能回滚；写用 truncate 保 inode，挂载才继续生效 */
    char bak[PATH_MAX + 16];
    snprintf(bak, sizeof bak, "%s.xk_bak", aim_apk);
    unlink(bak);
    if (access(aim_apk, F_OK) == 0) copy_into(aim_apk, bak);

    if (copy_into(rescue_apk, aim_apk) == 0) {
        chmod(aim_apk, 0644);
        unlink(bak);
        out("替换成功");
        debug("替换完成");
        if (watch_running()) mark_self_restart();
        long nb = cfg.protect + 1;
        char val[32];
        snprintf(val, sizeof val, "%ld", nb);
        set_conf("PROTECT_COUNT", val, p_conf);
        out("累计救砖: %ld次", nb);
        debug("救砖次数: %ld", nb);
        {
            char extra[64];
            snprintf(extra, sizeof extra, "次数: %ld", nb);
            long kb = (long)(file_size(logfile) / 1024);
            set_desc("已救砖", kb, extra);
        }
    } else {
        if (access(bak, F_OK) == 0) {
            copy_into(bak, aim_apk);
            chmod(aim_apk, 0644);
            debug("已回滚备份");
        }
        unlink(bak);
        out("替换失败");
        debug("替换失败");
        {
            long kb = (long)(file_size(logfile) / 1024);
            set_desc("替换失败", kb, NULL);
        }
    }

    debug("脚本结束");
    cleanup();
    return 0;
}
