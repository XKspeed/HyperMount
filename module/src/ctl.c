/*
 * ctl —— 模块控制面（webctl.sh + check.sh 的 C 版）
 *
 * 为什么重写：App 每 1.5 秒调一次 `webctl boot`，一次要 fork 二十多个进程
 * （7 次 getprop、6 次 stat、一串 grep/cut/awk/pidof）。这里把这些全换成
 * 系统调用与 __system_property_get，只有 dumpsys 和 settings 仍要 fork。
 *
 * 接口保持和脚本版一致：同样的子命令、同样的输出行、同样的 OK/FAIL 前缀，
 * App 侧一行都不用改。
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <errno.h>
#include <time.h>
#include <ctype.h>
#include <signal.h>
#include <limits.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#if defined(__ANDROID__)
#include <sys/system_properties.h>
#else
/* 本机（x86）编译测试用：NDK 下走真头文件，这里给个空实现 */
#define PROP_VALUE_MAX 92
static int __system_property_get(const char *name, char *value) {
    (void)name;
    value[0] = 0;
    return 0;
}
#endif

#define VER "1.0"

static char moddir[PATH_MAX];
static char cfgdir[PATH_MAX];
static char workdir[PATH_MAX];
static char p_conf[PATH_MAX];
static char p_prop[PATH_MAX];
static char p_rescue[PATH_MAX];
static char p_aim[PATH_MAX];
static char p_action_pid[PATH_MAX];
static char p_action_out[PATH_MAX];
static char p_watch_pid[PATH_MAX];
static char p_watch_guard[PATH_MAX];
static char p_watch_state[PATH_MAX];
static char p_watch_out[PATH_MAX];
static char p_watch_limit[PATH_MAX];
static char p_watch_stop[PATH_MAX];
static char p_watchd[PATH_MAX];
static char p_actiond[PATH_MAX];
static char p_limit_cache[PATH_MAX];

/* ---------------- 小工具 ---------------- */

static void join(char *dst, size_t cap, const char *a, const char *b) {
    snprintf(dst, cap, "%s/%s", a, b);
}

static int is_dir(const char *p) {
    struct stat st;
    return stat(p, &st) == 0 && S_ISDIR(st.st_mode);
}

static int is_file(const char *p) {
    struct stat st;
    return stat(p, &st) == 0 && S_ISREG(st.st_mode);
}

static long long file_size(const char *p) {
    struct stat st;
    if (stat(p, &st) != 0) return -1;
    return (long long)st.st_size;
}

static void mkdirs(const char *p) {
    char tmp[PATH_MAX];
    snprintf(tmp, sizeof tmp, "%s", p);
    for (char *q = tmp + 1; *q; q++) {
        if (*q == '/') {
            *q = 0;
            mkdir(tmp, 0755);
            *q = '/';
        }
    }
    mkdir(tmp, 0755);
}

static char *trim(char *s) {
    while (*s && isspace((unsigned char)*s)) s++;
    if (!*s) return s;
    char *e = s + strlen(s) - 1;
    while (e > s && isspace((unsigned char)*e)) *e-- = 0;
    return s;
}

/* 读整个文件，返回 malloc 的串（调用方 free）；失败返回 NULL */
static char *slurp(const char *p) {
    FILE *f = fopen(p, "r");
    if (!f) return NULL;
    if (fseek(f, 0, SEEK_END) != 0) { fclose(f); return NULL; }
    long n = ftell(f);
    if (n < 0) { fclose(f); return NULL; }
    rewind(f);
    char *buf = malloc((size_t)n + 1);
    if (!buf) { fclose(f); return NULL; }
    size_t got = fread(buf, 1, (size_t)n, f);
    buf[got] = 0;
    fclose(f);
    return buf;
}

/* 读文件第一行的整数，读不到返回 -1 */
static long read_pidfile(const char *p) {
    char buf[64];
    int fd = open(p, O_RDONLY);
    if (fd < 0) return -1;
    ssize_t n = read(fd, buf, sizeof buf - 1);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = 0;
    char *s = trim(buf);
    if (!*s) return -1;
    for (char *q = s; *q; q++) if (!isdigit((unsigned char)*q)) return -1;
    return strtol(s, NULL, 10);
}

/* ---------------- 配置 ---------------- */

static void conf_get(const char *key, char *out, size_t cap, const char *def) {
    if (def) snprintf(out, cap, "%s", def); else out[0] = 0;
    FILE *f = fopen(p_conf, "r");
    if (!f) return;
    char line[1024];
    size_t kl = strlen(key);
    while (fgets(line, sizeof line, f)) {
        char *p = trim(line);
        if (*p == '#') continue;
        if (strncmp(p, key, kl) != 0) continue;
        char *q = p + kl;
        while (*q == ' ' || *q == '\t') q++;
        if (*q != '=') continue;
        q++;
        while (*q == ' ' || *q == '\t') q++;
        char *e = q + strlen(q);
        while (e > q && (e[-1] == '\n' || e[-1] == '\r' || e[-1] == ' ' || e[-1] == '\t')) *--e = 0;
        if (*q == '"' && e > q + 1 && e[-1] == '"') { q++; e[-1] = 0; }
        snprintf(out, cap, "%s", q);
        break;
    }
    fclose(f);
}

/*
 * 写一项配置：有这行就替换，没有就追加。
 * 先写临时文件再 rename，写到一半断电也不会留下半截配置。
 */
static int conf_set(const char *key, const char *val) {
    char *src = slurp(p_conf);
    if (!src) return -1;

    size_t need = strlen(src) + strlen(key) + strlen(val) + 512;
    char *out = malloc(need);
    if (!out) { free(src); return -1; }

    int quoted = strchr(val, ' ') != NULL;
    char *p = src;
    int replaced = 0;
    size_t used = 0;
    size_t kl = strlen(key);

    while (*p) {
        char *nl = strchr(p, '\n');
        size_t len = nl ? (size_t)(nl - p) + 1 : strlen(p);

        if (!replaced && strncmp(p, key, kl) == 0 && p[kl] == '=') {
            used += (size_t)snprintf(out + used, need - used, "%s=%s%s%s\n",
                                     key, quoted ? "\"" : "", val, quoted ? "\"" : "");
            replaced = 1;
        } else {
            memcpy(out + used, p, len);
            used += len;
        }
        p += len;
    }
    if (!replaced) {
        if (used && out[used - 1] != '\n') out[used++] = '\n';
        used += (size_t)snprintf(out + used, need - used, "%s=%s%s%s\n",
                                 key, quoted ? "\"" : "", val, quoted ? "\"" : "");
    }
    out[used] = 0;

    char tmp[PATH_MAX];
    snprintf(tmp, sizeof tmp, "%s.tmp", p_conf);
    FILE *f = fopen(tmp, "w");
    if (!f) { free(src); free(out); return -1; }
    fwrite(out, 1, used, f);
    fclose(f);
    chmod(tmp, 0644);
    int rc = rename(tmp, p_conf);
    free(src);
    free(out);
    return rc;
}

/* ---------------- 进程与属性 ---------------- */

/*
 * 探活必须看进程状态，不能只看 /proc/<pid> 在不在。
 * 自己 fork 出来的子进程没 wait 之前是僵尸，目录照样存在，
 * 会把「exec 失败立刻退出」误判成还在跑。
 */
static int proc_alive(long pid) {
    if (pid <= 0) return 0;
    char p[64], buf[512];
    snprintf(p, sizeof p, "/proc/%ld/stat", pid);
    FILE *f = fopen(p, "r");
    if (!f) return 0;
    char *got = fgets(buf, sizeof buf, f);
    fclose(f);
    if (!got) return 0;
    /* 形如 "123 (comm) S ..."：comm 里可能有空格和括号，从最后一个 ')' 往后取 */
    char *rp = strrchr(buf, ')');
    if (!rp || rp[1] != ' ' || !rp[2]) return 0;
    char st = rp[2];
    return st != 'Z' && st != 'X' && st != 'x';
}

/* 按 cmdline 找进程。comm 只有 15 字符会截断，必须读 cmdline。 */
static long find_by_cmdline(const char *needle) {
    DIR *d = opendir("/proc");
    if (!d) return -1;
    struct dirent *e;
    long found = -1;
    while ((e = readdir(d))) {
        if (!isdigit((unsigned char)e->d_name[0])) continue;
        long pid = strtol(e->d_name, NULL, 10);
        if (pid == (long)getpid()) continue;
        char p[64], buf[2048];
        snprintf(p, sizeof p, "/proc/%ld/cmdline", pid);
        int fd = open(p, O_RDONLY);
        if (fd < 0) continue;
        ssize_t n = read(fd, buf, sizeof buf - 1);
        close(fd);
        if (n <= 0) continue;
        buf[n] = 0;
        for (ssize_t i = 0; i < n - 1; i++) if (buf[i] == 0) buf[i] = ' ';
        if (strstr(buf, needle)) { found = pid; break; }
    }
    closedir(d);
    return found;
}

static long systemui_pid(void) {
    DIR *d = opendir("/proc");
    if (!d) return -1;
    struct dirent *e;
    long found = -1;
    const char *want = "com.android.systemui";
    while ((e = readdir(d))) {
        if (!isdigit((unsigned char)e->d_name[0])) continue;
        long pid = strtol(e->d_name, NULL, 10);
        char p[64], buf[256];
        snprintf(p, sizeof p, "/proc/%ld/cmdline", pid);
        int fd = open(p, O_RDONLY);
        if (fd < 0) continue;
        ssize_t n = read(fd, buf, sizeof buf - 1);
        close(fd);
        if (n <= 0) continue;
        buf[n] = 0;
        /* argv[0] 可能是 /system/bin/com.android.systemui，取 basename 比 */
        char *base = strrchr(buf, '/');
        base = base ? base + 1 : buf;
        if (strcmp(base, want) == 0) { found = pid; break; }
    }
    closedir(d);
    return found;
}

static void prop(const char *name, char *out, size_t cap) {
    char buf[PROP_VALUE_MAX];
    buf[0] = 0;
    if (__system_property_get(name, buf) <= 0) buf[0] = 0;
    snprintf(out, cap, "%s", buf);
}

/* 起一个子进程并收集 stdout（最多 cap-1 字节），返回退出码；失败返回 -1 */
static int run_capture(char *const argv[], char *out, size_t cap) {
    int pfd[2];
    if (pipe(pfd) != 0) return -1;
    pid_t c = fork();
    if (c == 0) {
        close(pfd[0]);
        dup2(pfd[1], STDOUT_FILENO);
        dup2(pfd[1], STDERR_FILENO);
        if (pfd[1] > 2) close(pfd[1]);
        execv(argv[0], argv);
        _exit(127);
    }
    close(pfd[1]);
    if (c < 0) { close(pfd[0]); return -1; }
    size_t len = 0;
    if (out && cap) {
        ssize_t n;
        while (len + 1 < cap && (n = read(pfd[0], out + len, cap - 1 - len)) > 0) len += (size_t)n;
        out[len] = 0;
    }
    /* 把剩余输出读干净，避免子进程写满管道卡死 */
    char sink[4096];
    while (read(pfd[0], sink, sizeof sink) > 0) {}
    close(pfd[0]);
    int st = 0;
    waitpid(c, &st, 0);
    return WIFEXITED(st) ? WEXITSTATUS(st) : -1;
}

/* 起一个脱离会话的后台进程，日志追加到 logfile（NULL 则丢弃）。返回子进程 pid。 */
static pid_t spawn_daemon(char *const argv[], const char *logfile) {
    pid_t p = fork();
    if (p != 0) return p;
    setsid();
    int fd = -1;
    if (logfile && *logfile)
        fd = open(logfile, O_WRONLY | O_CREAT | O_APPEND, 0644);
    else
        fd = open("/dev/null", O_WRONLY);
    if (fd >= 0) {
        dup2(fd, STDOUT_FILENO);
        dup2(fd, STDERR_FILENO);
        if (fd > STDERR_FILENO) close(fd);
    }
    int nul = open("/dev/null", O_RDONLY);
    if (nul >= 0) { dup2(nul, STDIN_FILENO); close(nul); }
    /* execvp 而不是 execv：调起 "sh" 这种不带路径的名字时，execv 直接失败 */
    execvp(argv[0], argv);
    _exit(127);
}

static void reap(pid_t p) {
    if (p <= 0) return;
    for (int k = 0; k < 20 && waitpid(p, NULL, WNOHANG) == 0; k++) usleep(10000);
}

/* tail：把文件最后 n 行打到 stdout */
static void tail_file(const char *path, int n) {
    if (n <= 0) return;
    FILE *f = fopen(path, "r");
    if (!f) return;
    char **ring = calloc((size_t)n, sizeof(char *));
    if (!ring) { fclose(f); return; }
    char line[4096];
    int idx = 0, cnt = 0;
    while (fgets(line, sizeof line, f)) {
        free(ring[idx]);
        ring[idx] = strdup(line);
        idx = (idx + 1) % n;
        if (cnt < n) cnt++;
    }
    fclose(f);
    for (int i = 0; i < cnt; i++) {
        int at = (idx - cnt + i + n) % n;
        if (ring[at]) fputs(ring[at], stdout);
    }
    for (int i = 0; i < n; i++) free(ring[i]);
    free(ring);
}

/* ---------------- 输入设备 ---------------- */

/*
 * capabilities/key 是一串十六进制字，每个字按定长打印，bit 0 在最右边。
 * 判某一位：把所有字左补零到统一宽度拼起来，再从右数 bit/4 个字符。
 */
static int hexbit(const char *bm, int bit) {
    char words[64][32];
    int nw = 0, maxw = 0;
    const char *p = bm;
    while (*p && nw < 64) {
        while (*p == ' ' || *p == '\t' || *p == '\n') p++;
        if (!*p) break;
        int len = 0;
        while (p[len] && p[len] != ' ' && p[len] != '\t' && p[len] != '\n' && len < 31) len++;
        memcpy(words[nw], p, (size_t)len);
        words[nw][len] = 0;
        if (len > maxw) maxw = len;
        nw++;
        p += len;
    }
    if (!nw) return 0;
    int wl = maxw > 8 ? 16 : 8;

    char body[1024];
    size_t used = 0;
    for (int i = 0; i < nw; i++) {
        int len = (int)strlen(words[i]);
        for (int k = 0; k < wl - len; k++) body[used++] = '0';
        memcpy(body + used, words[i], (size_t)len);
        used += (size_t)len;
    }
    body[used] = 0;

    int idx = (int)used - 1 - bit / 4;
    if (idx < 0) return 0;
    int ch = (unsigned char)body[idx];
    int v;
    if (ch >= '0' && ch <= '9') v = ch - '0';
    else if (ch >= 'a' && ch <= 'f') v = ch - 'a' + 10;
    else if (ch >= 'A' && ch <= 'F') v = ch - 'A' + 10;
    else return 0;
    return (v >> (bit % 4)) & 1;
}

/* 扫 /sys/class/input/event*，把带 115/114 的设备分别拼进 up/down */
static void scan_devices(char *up, size_t upcap, char *down, size_t downcap, char *all, size_t allcap) {
    up[0] = down[0] = all[0] = 0;
    DIR *d = opendir("/sys/class/input");
    if (!d) return;
    struct dirent *e;
    char list[256][32];
    int n = 0;
    while ((e = readdir(d)) && n < 256) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        snprintf(list[n], sizeof list[n], "%.31s", e->d_name);
        n++;
    }
    closedir(d);
    /* readdir 顺序不固定，按名字排一下，保证 App 看到的顺序稳定 */
    for (int i = 0; i < n; i++)
        for (int j = i + 1; j < n; j++)
            if (strcmp(list[i], list[j]) > 0) {
                char t[32];
                memcpy(t, list[i], sizeof t);
                memcpy(list[i], list[j], sizeof t);
                memcpy(list[j], t, sizeof t);
            }

    for (int i = 0; i < n; i++) {
        char path[PATH_MAX], sysfs[PATH_MAX], ev[64], buf[1024];
        snprintf(sysfs, sizeof sysfs, "/sys/class/input/%s", list[i]);
        snprintf(ev, sizeof ev, "/dev/input/%s", list[i]);
        if (all[0]) strncat(all, " ", allcap - strlen(all) - 1);
        strncat(all, ev, allcap - strlen(all) - 1);

        snprintf(path, sizeof path, "%s/device/capabilities/key", sysfs);
        int fd = open(path, O_RDONLY);
        if (fd < 0) {
            snprintf(path, sizeof path, "%s/capabilities/key", sysfs);
            fd = open(path, O_RDONLY);
        }
        if (fd < 0) continue;
        ssize_t got = read(fd, buf, sizeof buf - 1);
        close(fd);
        if (got <= 0) continue;
        buf[got] = 0;
        if (hexbit(buf, 115)) {
            if (up[0]) strncat(up, " ", upcap - strlen(up) - 1);
            strncat(up, ev, upcap - strlen(up) - 1);
        }
        if (hexbit(buf, 114)) {
            if (down[0]) strncat(down, " ", downcap - strlen(down) - 1);
            strncat(down, ev, downcap - strlen(down) - 1);
        }
    }
}

/* 上一次校验测出来的设备优先用，没有才现扫 */
static void detect_keys(char *up, size_t upcap, char *down, size_t downcap) {
    char u[PATH_MAX], dn[PATH_MAX];
    conf_get("VOL_UP_DEV", u, sizeof u, NULL);
    conf_get("VOL_DOWN_DEV", dn, sizeof dn, NULL);
    if (u[0] || dn[0]) {
        snprintf(up, upcap, "%s", u);
        snprintf(down, downcap, "%s", dn);
        return;
    }
    char all[PATH_MAX];
    scan_devices(up, upcap, down, downcap, all, sizeof all);
    if (!up[0] && !down[0]) {
        /* 位图读不到时退回 getevent -pl，解析 add device 段落 */
        char *out = malloc(65536);
        if (!out) return;
        char *argv[] = {"/system/bin/getevent", "getevent", "-pl", NULL};
        if (run_capture(argv, out, 65536) == 0) {
            char cur[64] = "";
            int hu = 0, hd = 0;
            char *save = NULL;
            char *line = strtok_r(out, "\n", &save);
            while (line) {
                if (strncmp(line, "add device", 10) == 0) {
                    if (cur[0]) {
                        if (hu) { if (up[0]) strncat(up, " ", upcap - strlen(up) - 1); strncat(up, cur, upcap - strlen(up) - 1); }
                        if (hd) { if (down[0]) strncat(down, " ", downcap - strlen(down) - 1); strncat(down, cur, downcap - strlen(down) - 1); }
                    }
                    char *colon = strrchr(line, ':');
                    snprintf(cur, sizeof cur, "%s", colon ? colon + 1 : "");
                    cur[strcspn(cur, " \t\r")] = 0;
                    hu = hd = 0;
                } else if (strstr(line, "KEY_VOLUMEUP")) hu = 1;
                else if (strstr(line, "KEY_VOLUMEDOWN")) hd = 1;
                line = strtok_r(NULL, "\n", &save);
            }
            if (cur[0]) {
                if (hu) { if (up[0]) strncat(up, " ", upcap - strlen(up) - 1); strncat(up, cur, upcap - strlen(up) - 1); }
                if (hd) { if (down[0]) strncat(down, " ", downcap - strlen(down) - 1); strncat(down, cur, downcap - strlen(down) - 1); }
            }
        }
        free(out);
    }
}

static void dev_name(const char *ev, char *out, size_t cap) {
    char path[PATH_MAX], buf[256];
    const char *base = strrchr(ev, '/');
    base = base ? base + 1 : ev;
    snprintf(path, sizeof path, "/sys/class/input/%s/device/name", base);
    int fd = open(path, O_RDONLY);
    if (fd < 0) { snprintf(out, cap, "未知"); return; }
    ssize_t n = read(fd, buf, sizeof buf - 1);
    close(fd);
    if (n <= 0) { snprintf(out, cap, "未知"); return; }
    buf[n] = 0;
    char *s = trim(buf);
    snprintf(out, cap, "%s", *s ? s : "未知");
}

/*
 * 盯一组设备等某个按键。每个设备起一个 getevent，读到含 key 的行就算命中。
 * 超时秒数到点全杀。返回命中的设备列表（空格分隔），没命中返回空串。
 */
static int probe_key(const char *devs, const char *key, int sec,
                     char *hit, size_t hitcap) {
    hit[0] = 0;
    char list[PATH_MAX];
    snprintf(list, sizeof list, "%s", devs);

    char evs[32][64];
    int np = 0;
    char *save = NULL;
    char *tok = strtok_r(list, " ", &save);
    while (tok && np < 32) {
        snprintf(evs[np], sizeof evs[np], "%s", tok);
        np++;
        tok = strtok_r(NULL, " ", &save);
    }
    if (!np) return 0;

    pid_t pids[32];
    int fds[32];
    for (int i = 0; i < np; i++) {
        pids[i] = 0;
        fds[i] = -1;
        int pfd[2];
        if (pipe(pfd) != 0) continue;
        pid_t c = fork();
        if (c == 0) {
            close(pfd[0]);
            dup2(pfd[1], STDOUT_FILENO);
            dup2(pfd[1], STDERR_FILENO);
            if (pfd[1] > 2) close(pfd[1]);
            setsid();
            execl("/system/bin/getevent", "getevent", "-l", evs[i], (char *)NULL);
            execlp("getevent", "getevent", "-l", evs[i], (char *)NULL);
            _exit(127);
        }
        close(pfd[1]);
        if (c < 0) { close(pfd[0]); continue; }
        pids[i] = c;
        fds[i] = pfd[0];
        int fl = fcntl(pfd[0], F_GETFL, 0);
        fcntl(pfd[0], F_SETFL, fl | O_NONBLOCK);
    }

    static char buf[8192];
    int rounds = sec * 10;
    int alive = np;
    int found[32] = {0};
    for (int r = 0; r < rounds && alive > 0; r++) {
        int any = 0;
        for (int i = 0; i < np; i++) {
            if (fds[i] < 0) continue;
            for (;;) {
                ssize_t n = read(fds[i], buf, sizeof buf - 1);
                if (n < 0) break;
                if (n == 0) { close(fds[i]); fds[i] = -1; reap(pids[i]); alive--; break; }
                buf[n] = 0;
                if (strstr(buf, key)) { found[i] = 1; any = 1; }
            }
        }
        if (any) break;
        usleep(100000);
    }

    for (int i = 0; i < np; i++) {
        if (pids[i] > 0) { kill(pids[i], SIGTERM); kill(-pids[i], SIGTERM); }
    }
    usleep(150000);
    for (int i = 0; i < np; i++) {
        if (pids[i] > 0) { kill(pids[i], SIGKILL); kill(-pids[i], SIGKILL); reap(pids[i]); }
        if (fds[i] >= 0) close(fds[i]);
    }

    for (int i = 0; i < np; i++) {
        if (!found[i]) continue;
        if (hit[0]) strncat(hit, " ", hitcap - strlen(hit) - 1);
        strncat(hit, evs[i], hitcap - strlen(hit) - 1);
    }
    return hit[0] ? 1 : 0;
}

/* ---------------- dumpsys 崩溃上限 ---------------- */

static int read_limit(void) {
    char *out = malloc(1 << 20);
    if (!out) return -1;
    char *argv[] = {"/system/bin/dumpsys", "dumpsys", "activity", "settings", NULL};
    int rc = run_capture(argv, out, 1 << 20);
    int val = -1;
    if (rc == 0) {
        char *p = strstr(out, "process_crash_count_limit=");
        if (p) val = (int)strtol(p + strlen("process_crash_count_limit="), NULL, 10);
    }
    free(out);
    return val;
}

/*
 * 崩溃上限只在两种情况会变：用户点了开关，或者系统自己重置。
 * 每次都跑 dumpsys 是 100~200 ms 的 Java 进程，而 App 每 1.5 秒问一次，
 * 所以这里缓存 30 秒；写配置时（rescue on/off）直接把新值写进缓存。
 */
static int limit_cached(void) {
    struct stat st;
    if (stat(p_limit_cache, &st) == 0 && time(NULL) - st.st_mtime < 30) {
        char *s = slurp(p_limit_cache);
        if (s) {
            int v = (int)strtol(s, NULL, 10);
            free(s);
            if (v > 0) return v;
        }
    }
    int v = read_limit();
    if (v > 0) {
        FILE *f = fopen(p_limit_cache, "w");
        if (f) { fprintf(f, "%d\n", v); fclose(f); }
    }
    return v;
}

static void limit_store(int v) {
    FILE *f = fopen(p_limit_cache, "w");
    if (f) { fprintf(f, "%d\n", v); fclose(f); }
}
/* ================= 命令实现 ================= */

static int copy_file(const char *src, const char *dst) {
    FILE *in = fopen(src, "rb");
    if (!in) return -1;
    FILE *out = fopen(dst, "wb");
    if (!out) { fclose(in); return -1; }
    char buf[65536];
    size_t n;
    int ok = 0;
    while ((n = fread(buf, 1, sizeof buf, in)) > 0) {
        if (fwrite(buf, 1, n, out) != n) { ok = -1; break; }
    }
    fclose(in);
    if (fclose(out) != 0) ok = -1;
    return ok;
}

static int is_apk(const char *p) {
    const char *dot = strrchr(p, '.');
    if (!dot) return 0;
    return strcasecmp(dot, ".apk") == 0;
}

/* APK 完整性就看 zip 头，不用起 unzip */
static int apk_ok(const char *p) {
    unsigned char h[4];
    int fd = open(p, O_RDONLY);
    if (fd < 0) return 0;
    ssize_t n = read(fd, h, 4);
    close(fd);
    if (n != 4) return 0;
    return h[0] == 'P' && h[1] == 'K' && (h[2] == 3 || h[2] == 5) && (h[3] == 4 || h[3] == 6);
}

static int cmd_ls(const char *dir) {
    if (!is_dir(dir)) {
        printf("FAIL 目录不存在\n");
        return 1;
    }
    struct dirent **list = NULL;
    int n = scandir(dir, &list, NULL, alphasort);
    if (n < 0) {
        printf("FAIL 目录不可读\n");
        return 1;
    }
    for (int i = 0; i < n; i++) {
        const char *name = list[i]->d_name;
        if (strcmp(name, ".") && strcmp(name, "..") && name[0]) {
            char full[PATH_MAX];
            snprintf(full, sizeof full, "%s/%s", dir, name);
            struct stat st;
            if (stat(full, &st) == 0) {
                if (S_ISDIR(st.st_mode)) {
                    printf("%s|d|0\n", name);
                } else if (is_apk(name)) {
                    printf("%s|a|%lld\n", name, (long long)st.st_size);
                }
            }
        }
        free(list[i]);
    }
    free(list);
    return 0;
}

static int cmd_prepare(const char *src) {
    if (!src || !*src) { printf("FAIL 缺少 APK 路径\n"); return 1; }
    if (!is_file(src)) { printf("FAIL 文件不存在: %s\n", src); return 1; }
    if (!is_apk(src)) { printf("FAIL 不是 APK: %s\n", src); return 1; }
    if (!apk_ok(src)) { printf("FAIL APK 已损坏\n"); return 1; }
    long long sz = file_size(src);
    if (copy_file(src, p_rescue) != 0) { printf("FAIL 复制失败\n"); return 1; }
    chmod(p_rescue, 0644);
    printf("OK\n");
    const char *base = strrchr(src, '/');
    base = base ? base + 1 : src;
    printf("救砖文件已就绪: %s (%lld 字节)\n", base, sz);
    return 0;
}

static int cmd_test_run(const char *rescue, const char *target) {
    if (!rescue || !*rescue) { printf("FAIL 缺少救砖文件\n"); return 1; }
    if (!target || !*target) { printf("FAIL 缺少待测文件\n"); return 1; }
    const char *files[2] = {rescue, target};
    for (int i = 0; i < 2; i++) {
        if (!is_file(files[i])) { printf("FAIL 文件不存在: %s\n", files[i]); return 1; }
        if (!is_apk(files[i])) { printf("FAIL 不是 APK: %s\n", files[i]); return 1; }
    }

    mkdirs(workdir);
    long ap = read_pidfile(p_action_pid);
    if (ap > 0 && proc_alive(ap)) {
        printf("FAIL 已有救砖守护在运行 (PID %ld)\n", ap);
        printf("如需重来请先执行 stop\n");
        return 1;
    }
    unlink(p_action_pid);

    if (!is_file(p_rescue)) {
        if (copy_file(rescue, p_rescue) != 0) {
            printf("FAIL 救砖文件未就绪且复制失败\n");
            return 1;
        }
        chmod(p_rescue, 0644);
        printf("STEP 1/2 救砖文件已复制\n");
    } else {
        printf("STEP 1/2 救砖文件已就绪\n");
    }

    /* 日志清空：actiond 与 App 都靠它区分「这一轮的输出」 */
    int lf = open(p_action_out, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (lf >= 0) close(lf);

    char ash[PATH_MAX];
    join(ash, sizeof ash, moddir, "action-shell.sh");

    pid_t pid = -1;
    if (access(p_actiond, X_OK) == 0) {
        char *argvd[] = {p_actiond, "--dir", moddir, "--test", (char *)target, NULL};
        pid = spawn_daemon(argvd, p_action_out);
        if (pid > 0) {
            FILE *pf = fopen(p_action_pid, "w");
            if (pf) { fprintf(pf, "%d\n", (int)pid); fclose(pf); }
            usleep(1000000);
            /* 立刻消失且没留下「交给全局守护」= exec 没成功（架构不符 / SELinux） */
            if (!proc_alive(pid)) {
                char *log = slurp(p_action_out);
                int handed = log && strstr(log, "交给全局守护");
                free(log);
                if (!handed) pid = -1;
            }
        } else {
            pid = -1;
        }
    }

    /* 和脚本版一样留一手：actiond 起不来就让 shell 版接着上 */
    if (pid < 0) {
        FILE *lf2 = fopen(p_action_out, "a");
        if (lf2) { fprintf(lf2, "actiond 起不来，改用 shell 版\n"); fclose(lf2); }
        char *argvs[] = {"sh", ash, "--test", (char *)target, NULL};
        pid = spawn_daemon(argvs, p_action_out);
        if (pid <= 0) {
            printf("FAIL 救砖进程启动失败\n");
            return 1;
        }
        FILE *pf = fopen(p_action_pid, "w");
        if (pf) { fprintf(pf, "%d\n", (int)pid); fclose(pf); }
        usleep(1000000);
    }

    if (!proc_alive(pid)) {
        /* 守护在跑时只做替换就退出 这是正常收尾 不是启动失败 */
        char *log = slurp(p_action_out);
        int handed = log && strstr(log, "交给全局守护");
        free(log);
        if (handed) {
            printf("STEP 2/2 界面已替换 交给全局守护\n");
            printf("OK 测试已启动\n");
            return 0;
        }
        printf("FAIL 救砖进程启动失败\n");
        tail_file(p_action_out, 10);
        unlink(p_action_pid);
        return 1;
    }
    printf("STEP 2/2 救砖进程已启动 (PID %d)\n", (int)pid);
    printf("OK 测试已启动\n");
    return 0;
}

/* running= 与三个文件大小，status 和 boot 共用 */
static void print_status_block(void) {
    long ap = read_pidfile(p_action_pid);
    if (ap > 0 && proc_alive(ap)) {
        printf("running=%ld\n", ap);
    } else {
        printf("running=\n");
        if (ap > 0) unlink(p_action_pid);
    }

    /* 文件不存在时脚本版输出的是空值（stat 没结果），这里照做，别写成 0 */
    long long rs = file_size(p_rescue);
    long long tg = file_size(p_aim);
    if (rs < 0) printf("rescue=\n"); else printf("rescue=%lld\n", rs);
    if (tg < 0) printf("target=\n"); else printf("target=%lld\n", tg);

    char tp[PATH_MAX];
    conf_get("TARGET_APK_PATH", tp, sizeof tp, NULL);
    if (tp[0] && is_file(tp)) {
        struct stat st;
        stat(tp, &st);
        char ts[64] = "";
        struct tm tm;
        localtime_r(&st.st_mtime, &tm);
        strftime(ts, sizeof ts, "%Y-%m-%d %H:%M:%S", &tm);
        printf("targetok=1\n");
        printf("targetsize=%lld\n", (long long)st.st_size);
        printf("targettime=%s\n", ts);
    } else {
        printf("targetok=0\n");
        printf("targetsize=0\n");
        printf("targettime=\n");
    }
}

static int cmd_status(void) {
    print_status_block();
    return 0;
}

static int cmd_output(const char *arg) {
    int n = arg && *arg ? atoi(arg) : 50;
    if (n <= 0) n = 50;
    if (n > 400) n = 400;
    if (is_file(p_action_out)) tail_file(p_action_out, n);
    return 0;
}

static int cmd_stop(void) {
    int st = 0;
    long ap = read_pidfile(p_action_pid);
    if (ap > 0 && proc_alive(ap)) {
        kill((pid_t)ap, SIGTERM);
        usleep(300000);
        kill((pid_t)ap, SIGKILL);
        st = 1;
    }
    unlink(p_action_pid);

    /* 救砖进程里的 logcat 是 setsid 起的 得按命令行找 */
    for (;;) {
        long p = find_by_cmdline("logcat -b crash");
        if (p <= 0) break;
        kill((pid_t)p, SIGKILL);
        usleep(50000);
        if (!proc_alive(p)) break;
    }
    char ready[PATH_MAX];
    join(ready, sizeof ready, workdir, "action.ready");
    unlink(ready);

    printf("%s\n", st ? "已停止" : "未在运行");
    return 0;
}

static int cmd_rescue(const char *action) {
    if (!action || strcmp(action, "get") == 0) {
        int v = limit_cached();
        if (v < 0) printf("limit=unknown\n"); else printf("limit=%d\n", v);
        printf("state=%s\n", (v > 100) ? "off" : "on");
        return 0;
    }
    if (strcmp(action, "off") == 0) {
        char *argv[] = {"/system/bin/settings", "settings", "put", "global",
                        "activity_manager_constants",
                        "process_crash_count_limit=999999,min_crash_interval=120000,process_crash_count_reset_interval=43200000",
                        NULL};
        run_capture(argv, NULL, 0);
        limit_store(999999);
        printf("OK 已屏蔽系统救援\n");
        return 0;
    }
    if (strcmp(action, "on") == 0) {
        char *argv[] = {"/system/bin/settings", "settings", "put", "global",
                        "activity_manager_constants",
                        "process_crash_count_limit=12,min_crash_interval=120000,process_crash_count_reset_interval=43200000",
                        NULL};
        run_capture(argv, NULL, 0);
        limit_store(12);
        printf("OK 已恢复系统救援\n");
        return 0;
    }
    printf("FAIL 未知操作: %s\n", action);
    return 1;
}

/* 读 watch.state 里的 since/count */
static void read_watch_state(long *since, long *count) {
    *since = *count = 0;
    char *s = slurp(p_watch_state);
    if (!s) return;
    char *line = strtok(s, "\n");
    while (line) {
        if (strncmp(line, "since=", 6) == 0) *since = strtol(line + 6, NULL, 10);
        else if (strncmp(line, "count=", 6) == 0) *count = strtol(line + 6, NULL, 10);
        line = strtok(NULL, "\n");
    }
    free(s);
}

static int cmd_watch(const char *action, const char *arg) {
    if (!action || strcmp(action, "status") == 0) {
        long wp = read_pidfile(p_watch_pid);
        int run = 0;
        if (wp > 0 && proc_alive(wp)) run = 1;
        else if (wp > 0) unlink(p_watch_pid);

        long since = 0, count = 0;
        read_watch_state(&since, &count);
        long limit = read_pidfile(p_watch_limit);
        if (limit < 0) limit = 0;
        if (!run) since = 0;

        printf("support=1\n");
        printf("running=%d\n", run);
        printf("since=%ld\n", since);
        printf("limit=%ld\n", limit);
        printf("count=%ld\n", count);
        return 0;
    }

    if (strcmp(action, "start") == 0) {
        long sec = arg && *arg ? strtol(arg, NULL, 10) : 0;
        if (sec < 0) sec = 0;
        long wp = read_pidfile(p_watch_pid);
        if (wp > 0 && proc_alive(wp)) {
            printf("OK 常驻救砖已在运行 (PID %ld)\n", wp);
            return 0;
        }
        unlink(p_watch_pid);
        unlink(p_watch_guard);
        unlink(p_watch_stop);
        unlink(p_watch_state);
        FILE *lf = fopen(p_watch_limit, "w");
        if (lf) { fprintf(lf, "%ld\n", sec); fclose(lf); }

        if (access(p_watchd, X_OK) != 0) {
            FILE *w = fopen(p_watch_out, "a");
            if (w) fprintf(w, "bin/watchd 不存在或不可执行（架构不是 arm64？）\n");
            if (w) fclose(w);
            printf("FAIL 常驻救砖没能启动\n");
            tail_file(p_watch_out, 5);
            return 1;
        }

        char *argv[] = {p_watchd, "--dir", moddir, NULL};
        pid_t pid = spawn_daemon(argv, NULL);
        if (pid > 0) {
            /* 等它把 pid 写出来 最多 3 秒 */
            for (int i = 0; i < 30; i++) {
                wp = read_pidfile(p_watch_pid);
                if (wp > 0 && proc_alive(wp)) break;
                usleep(100000);
            }
        }
        if (wp > 0 && proc_alive(wp)) {
            printf("OK 常驻救砖已启动 (PID %ld)\n", wp);
            return 0;
        }
        printf("FAIL 常驻救砖没能启动\n");
        tail_file(p_watch_out, 5);
        return 1;
    }

    if (strcmp(action, "stop") == 0) {
        /* 先打停止标记：主进程和保护进程互相监护 不打标记会被拉回来 */
        int fd = open(p_watch_stop, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (fd >= 0) close(fd);

        long gp = read_pidfile(p_watch_guard);
        if (gp > 0) kill((pid_t)gp, SIGTERM);

        long wp = read_pidfile(p_watch_pid);
        if (wp > 0 && proc_alive(wp)) {
            kill((pid_t)wp, SIGTERM);
            usleep(400000);
            kill((pid_t)wp, SIGKILL);
        }
        unlink(p_watch_pid);
        unlink(p_watch_guard);

        for (;;) {
            long p = find_by_cmdline("bin/watchd");
            if (p <= 0) break;
            kill((pid_t)p, SIGKILL);
            usleep(50000);
            if (!proc_alive(p)) break;
        }
        /* 从旧版升级上来的机器 可能还留着 sh 版起的探测进程 */
        for (;;) {
            long p = find_by_cmdline("am_proc_start -T 1");
            if (p <= 0) break;
            kill((pid_t)p, SIGKILL);
            usleep(50000);
            if (!proc_alive(p)) break;
        }

        FILE *sf = fopen(p_watch_state, "w");
        if (sf) { fprintf(sf, "running=0\n"); fclose(sf); }
        usleep(200000);
        printf("OK 已停止\n");
        return 0;
    }

    printf("FAIL 未知操作: %s\n", action);
    return 1;
}

/* ---------------- 模块卡片状态 ---------------- */

/* settings.conf 的 TIME_FORMAT 是给 date 用的（带 + 前缀），strftime 直接吃后半截 */
static void get_time_str(char *out, size_t cap) {
    char fmt[64];
    conf_get("TIME_FORMAT", fmt, sizeof fmt, "+%Y-%m-%d %H:%M:%S");
    const char *f = fmt[0] == '+' ? fmt + 1 : fmt;
    time_t now = time(NULL);
    struct tm tmv;
    localtime_r(&now, &tmv);
    if (strftime(out, cap, f, &tmv) == 0) snprintf(out, cap, "%s", "-");
}

/* 写 module.prop 的 description：<时间> | 状态: xxx [| 附加段] */
static void set_desc(const char *state, const char *extra) {
    char *src = slurp(p_prop);
    if (!src) return;
    char when[96], line[512];
    get_time_str(when, sizeof when);
    if (extra && *extra)
        snprintf(line, sizeof line, "%s | 状态: %s | %s", when, state, extra);
    else
        snprintf(line, sizeof line, "%s | 状态: %s", when, state);

    size_t need = strlen(src) + strlen(line) + 64;
    char *out = malloc(need);
    if (!out) { free(src); return; }
    size_t used = 0;
    int done = 0;
    for (char *p = src; *p; ) {
        char *nl = strchr(p, '\n');
        size_t len = nl ? (size_t)(nl - p) + 1 : strlen(p);
        if (!done && strncmp(p, "description=", 12) == 0) {
            used += (size_t)snprintf(out + used, need - used, "description=%s\n", line);
            done = 1;
        } else {
            memcpy(out + used, p, len);
            used += len;
        }
        p += len;
    }
    free(src);
    /* module.prop 里没有 description 行就原样不动，不硬塞一行进去 */
    if (!done) { free(out); return; }

    char tmp[PATH_MAX];
    snprintf(tmp, sizeof tmp, "%s.tmp", p_prop);
    FILE *f = fopen(tmp, "w");
    if (!f) { free(out); return; }
    fwrite(out, 1, used, f);
    fclose(f);
    chmod(tmp, 0644);
    rename(tmp, p_prop);
    free(out);
}

/* 删掉日志目录里超过保留天数的 *.log（等价 find -maxdepth 1 -mtime +N -delete） */
static void purge_old_logs(const char *dir, int days) {
    if (days < 0) days = 0;
    DIR *d = opendir(dir);
    if (!d) return;
    time_t now = time(NULL);
    struct dirent *e;
    while ((e = readdir(d))) {
        if (e->d_name[0] == '.') continue;
        const char *dot = strrchr(e->d_name, '.');
        if (!dot || strcmp(dot, ".log") != 0) continue;
        char p[PATH_MAX];
        snprintf(p, sizeof p, "%s/%s", dir, e->d_name);
        struct stat st;
        if (stat(p, &st) != 0 || !S_ISREG(st.st_mode)) continue;
        if (now - st.st_mtime > (time_t)days * 86400) unlink(p);
    }
    closedir(d);
}

/* fork 出来的子进程用：脱离会话 + 三个标准流全丢 /dev/null */
static void detach_self(void) {
    setsid();
    int nul = open("/dev/null", O_RDWR);
    if (nul >= 0) {
        dup2(nul, STDIN_FILENO);
        dup2(nul, STDOUT_FILENO);
        dup2(nul, STDERR_FILENO);
        if (nul > STDERR_FILENO) close(nul);
    }
}

/* 跑一条命令，输出丢弃，等它结束 */
static void run_quiet(char *const argv[]) {
    pid_t p = fork();
    if (p == 0) {
        int nul = open("/dev/null", O_RDWR);
        if (nul >= 0) {
            dup2(nul, STDIN_FILENO);
            dup2(nul, STDOUT_FILENO);
            dup2(nul, STDERR_FILENO);
            if (nul > STDERR_FILENO) close(nul);
        }
        execvp(argv[0], argv);
        _exit(127);
    }
    if (p > 0) { int st; waitpid(p, &st, 0); }
}

/*
 * logcat -b crash 写进 logfile，最多 sec 秒。
 * logcat 是持续输出、不会自己退出，所以计时必须由我们来收。
 */
static void capture_logcat(const char *logfile, int sec) {
    pid_t p = fork();
    if (p == 0) {
        int fd = open(logfile, O_WRONLY | O_CREAT | O_APPEND, 0644);
        if (fd >= 0) {
            dup2(fd, STDOUT_FILENO);
            dup2(fd, STDERR_FILENO);
            if (fd > STDERR_FILENO) close(fd);
        }
        int nul = open("/dev/null", O_RDONLY);
        if (nul >= 0) { dup2(nul, STDIN_FILENO); close(nul); }
        execlp("logcat", "logcat", "-b", "crash", (char *)NULL);
        _exit(127);
    }
    if (p <= 0) return;
    for (int i = 0; i < sec * 10; i++) {
        int st;
        if (waitpid(p, &st, WNOHANG) != 0) return;   /* 已退出（含 exec 失败） */
        usleep(100000);
    }
    kill(p, SIGTERM);
    for (int i = 0; i < 20; i++) {
        if (waitpid(p, NULL, WNOHANG) != 0) return;
        usleep(100000);
    }
    kill(p, SIGKILL);
    reap(p);
}

/* 开机日志：等开机完成 → 清旧日志 → 抓 logcat → 更新模块卡片状态 */
static void cmd_log_run(void) {
    char v[32];
    conf_get("LOG_MODE", v, sizeof v, "0");
    if (atoi(v) == 0) return;

    char logdir[PATH_MAX];
    conf_get("LOG_DIR", logdir, sizeof logdir, "");
    /* 目录留空就当不记日志：不改描述的话，「等待开机完成」会一直挂在卡片上 */
    if (!logdir[0]) {
        set_desc("未设置日志目录 请先在 App 里选一个", NULL);
        return;
    }

    /* 最多等 10 分钟 */
    for (int i = 0; i < 600; i++) {
        char b[PROP_VALUE_MAX] = "";
        prop("sys.boot_completed", b, sizeof b);
        if (strcmp(b, "1") == 0) break;
        sleep(1);
    }
    sleep(5);

    mkdirs(logdir);
    chmod(logdir, 0755);

    conf_get("LOG_RETENTION_DAYS", v, sizeof v, "1");
    purge_old_logs(logdir, atoi(v));

    char *clearcmd[] = {"logcat", "-c", "-b", "crash", NULL};
    run_quiet(clearcmd);

    set_desc("日志记录中", NULL);

    char when[128], file[PATH_MAX];
    get_time_str(when, sizeof when);
    snprintf(file, sizeof file, "%s/%s.log", logdir, when);

    conf_get("LOG_TIMEOUT", v, sizeof v, "60");
    int sec = atoi(v);
    if (sec <= 0) sec = 60;
    capture_logcat(file, sec);

    struct stat st;
    if (stat(file, &st) != 0 || st.st_size == 0) unlink(file);

    /* 开机自启的守护这会儿可能已经在跑了，别把卡片写成「日志已停止」 */
    long wp = read_pidfile(p_watch_pid);
    if (wp > 0 && proc_alive(wp)) set_desc("全局守护中", NULL);
    else set_desc("日志已停止", NULL);
}

/*
 * 开机阶段：起开机日志、按需起全局救砖等待器。
 * 由 service.sh 调用，必须立刻返回 —— Magisk 会等 service.sh 结束。
 */static int cmd_boot_start(void) {
    char v[32];
    conf_get("LOG_MODE", v, sizeof v, "0");
    int mode = atoi(v);
    conf_get("BOOT_LOG", v, sizeof v, "1");
    int bootlog = atoi(v);
    conf_get("BOOT_WATCH", v, sizeof v, "0");
    int bootwatch = atoi(v);
    char logdir[PATH_MAX];
    conf_get("LOG_DIR", logdir, sizeof logdir, "");

    /* 运行模式禁用、或没选日志目录都不起（cmd_log_run 里还会再判一次） */
    if (bootlog && mode != 0 && logdir[0]) {
        set_desc("等待开机完成", NULL);
        pid_t lp = fork();
        if (lp == 0) {
            detach_self();
            cmd_log_run();
            _exit(0);
        }
    } else {
        set_desc("未记录开机日志", NULL);
    }

    /* 开机启动全局救砖：运行模式不到「日志 + 救砖」时 watchd 起来也会退，先拦一道 */
    if (bootwatch && mode >= 2 && logdir[0]) {
        pid_t p = fork();
        if (p == 0) {
            detach_self();
            /* 等开机完成，最多 10 分钟 */
            int ok = 0;
            for (int i = 0; i < 300; i++) {
                char b[PROP_VALUE_MAX] = "";
                prop("sys.boot_completed", b, sizeof b);
                if (strcmp(b, "1") == 0) { ok = 1; break; }
                sleep(2);
            }
            /* 再等一会儿：开机后 SystemUI 自己会重启一两次，太早起会被算进崩溃窗口 */
            if (ok) {
                sleep(15);
                cmd_watch("start", "0");
            }
            _exit(0);
        }
    }
    return 0;
}

/* boot：App 每 1.5 秒拉一次，格式必须和脚本版逐字一致 */
static int cmd_boot(void) {
    char buf[PROP_VALUE_MAX];

    printf("##VERSION\n");
    char ver[128] = "";
    FILE *f = fopen(p_prop, "r");
    if (f) {
        char line[512];
        while (fgets(line, sizeof line, f)) {
            if (strncmp(line, "version=", 8) == 0) {
                snprintf(ver, sizeof ver, "%s", trim(line + 8));
                break;
            }
        }
        fclose(f);
    }
    printf("version=%s\n", ver);

    printf("##PID\n");
    long sp = systemui_pid();
    if (sp > 0) printf("pid=%ld\n", sp); else printf("pid=\n");

    printf("##STATUS\n");
    print_status_block();

    printf("##RESCUE\n");
    int lim = limit_cached();
    if (lim < 0) printf("limit=unknown\n"); else printf("limit=%d\n", lim);
    printf("state=%s\n", (lim > 100) ? "off" : "on");

    printf("##ENV\n");
    prop("ro.build.version.release", buf, sizeof buf);
    printf("android_ver=%s\n", buf);
    prop("ro.build.version.sdk", buf, sizeof buf);
    printf("android_sdk=%s\n", buf);
    prop("ro.mi.os.version.name", buf, sizeof buf);
    if (!buf[0]) prop("ro.miui.ui.version.name", buf, sizeof buf);
    printf("os_name=%s\n", buf);
    prop("ro.product.device", buf, sizeof buf);
    printf("device=%s\n", buf);
    prop("ro.product.model", buf, sizeof buf);
    printf("model=%s\n", buf);
    prop("ro.build.version.incremental", buf, sizeof buf);
    printf("build=%s\n", buf);

    char saved[PATH_MAX];
    conf_get("CHECK_ANDROID_VER", saved, sizeof saved, NULL);
    printf("saved_android=%s\n", saved);
    conf_get("CHECK_OS_NAME", saved, sizeof saved, NULL);
    printf("saved_os=%s\n", saved);
    conf_get("VOLUME_CHECK_ASSESS", saved, sizeof saved, "0");
    printf("saved_assess=%s\n", saved[0] ? saved : "0");
    conf_get("CHECK_TIME", saved, sizeof saved, NULL);
    printf("saved_time=%s\n", saved);

    printf("##KEYS\n");
    char up[PATH_MAX], down[PATH_MAX];
    detect_keys(up, sizeof up, down, sizeof down);
    printf("up=%s\n", up);
    printf("down=%s\n", down);
    return 0;
}

/* ---------------- check：音量键校验 ---------------- */

static void print_env(void) {
    char b[PROP_VALUE_MAX], s[PATH_MAX];
    prop("ro.build.version.release", b, sizeof b);
    printf("android_ver=%s\n", b);
    prop("ro.build.version.sdk", b, sizeof b);
    printf("android_sdk=%s\n", b);
    prop("ro.mi.os.version.name", b, sizeof b);
    if (!b[0]) prop("ro.miui.ui.version.name", b, sizeof b);
    printf("os_name=%s\n", b);
    prop("ro.product.device", b, sizeof b);
    printf("device=%s\n", b);
    prop("ro.product.model", b, sizeof b);
    printf("model=%s\n", b);
    prop("ro.build.version.incremental", b, sizeof b);
    printf("build=%s\n", b);
    conf_get("CHECK_ANDROID_VER", s, sizeof s, NULL);
    printf("saved_android=%s\n", s);
    conf_get("CHECK_OS_NAME", s, sizeof s, NULL);
    printf("saved_os=%s\n", s);
    conf_get("VOLUME_CHECK_ASSESS", s, sizeof s, "0");
    printf("saved_assess=%s\n", s[0] ? s : "0");
    conf_get("CHECK_TIME", s, sizeof s, NULL);
    printf("saved_time=%s\n", s);
}

static int cmd_check(const char *sub, const char *a2, const char *a3) {
    if (!sub || strcmp(sub, "env") == 0) {
        print_env();
        return 0;
    }
    if (strcmp(sub, "keys") == 0) {
        char up[PATH_MAX], down[PATH_MAX];
        detect_keys(up, sizeof up, down, sizeof down);
        printf("up=%s\n", up);
        printf("down=%s\n", down);
        return 0;
    }
    if (strcmp(sub, "scan") == 0) {
        char up[PATH_MAX], down[PATH_MAX], all[PATH_MAX];
        scan_devices(up, sizeof up, down, sizeof down, all, sizeof all);
        char list[PATH_MAX];
        snprintf(list, sizeof list, "%s", all);
        /* 必须 strtok_r：内层再探 up/down 时，普通的 strtok 会把外层游标冲掉 */
        char *save1 = NULL;
        for (char *tok = strtok_r(list, " ", &save1); tok; tok = strtok_r(NULL, " ", &save1)) {
            int u = 0, v = 0;
            char copy[PATH_MAX], *save2;
            snprintf(copy, sizeof copy, "%s", up);
            save2 = NULL;
            for (char *t = strtok_r(copy, " ", &save2); t; t = strtok_r(NULL, " ", &save2))
                if (strcmp(t, tok) == 0) { u = 1; break; }
            snprintf(copy, sizeof copy, "%s", down);
            save2 = NULL;
            for (char *t = strtok_r(copy, " ", &save2); t; t = strtok_r(NULL, " ", &save2))
                if (strcmp(t, tok) == 0) { v = 1; break; }
            if (!u && !v) continue;
            char name[256];
            dev_name(tok, name, sizeof name);
            printf("%s|%s|%s%s\n", tok, name, u ? "UP" : "", v ? (u ? " DOWN" : "DOWN") : "");
        }
        return 0;
    }
    if (strcmp(sub, "up") == 0 || strcmp(sub, "down") == 0) {
        int is_up = sub[0] == 'u';
        const char *key = is_up ? "KEY_VOLUMEUP" : "KEY_VOLUMEDOWN";
        char up[PATH_MAX], down[PATH_MAX], all[PATH_MAX];
        detect_keys(up, sizeof up, down, sizeof down);
        char *devs = is_up ? up : down;
        if (!devs[0]) {
            scan_devices(up, sizeof up, down, sizeof down, all, sizeof all);
            devs = all;
        }
        if (!devs[0]) { printf("ERR 没有可用设备\n"); return 1; }

        char hit[PATH_MAX];
        int sec = 10;
        if (probe_key(devs, key, sec, hit, sizeof hit)) {
            printf("OK %s\n", hit);
            return 0;
        }
        /* 配置里的设备可能过期了 再全量扫一遍 */
        scan_devices(up, sizeof up, down, sizeof down, all, sizeof all);
        if (strcmp(devs, all) != 0 && all[0]) {
            if (probe_key(all, key, sec, hit, sizeof hit)) {
                printf("OK %s\n", hit);
                return 0;
            }
        }
        printf("ERR 未检测到音量%s\n", is_up ? "+" : "-");
        return 1;
    }
    if (strcmp(sub, "save") == 0) {
        char b[PROP_VALUE_MAX], os[PROP_VALUE_MAX], tm[64], ts[64];
        prop("ro.build.version.release", b, sizeof b);
        char android_ver[PROP_VALUE_MAX];
        snprintf(android_ver, sizeof android_ver, "%s", b);
        prop("ro.build.version.sdk", b, sizeof b);
        char android_sdk[PROP_VALUE_MAX];
        snprintf(android_sdk, sizeof android_sdk, "%s", b);
        prop("ro.mi.os.version.name", os, sizeof os);
        if (!os[0]) prop("ro.miui.ui.version.name", os, sizeof os);
        prop("ro.product.device", b, sizeof b);
        char device[PROP_VALUE_MAX];
        snprintf(device, sizeof device, "%s", b);
        time_t t = time(NULL);
        struct tm tmv;
        localtime_r(&t, &tmv);
        strftime(tm, sizeof tm, "%Y-%m-%d %H:%M", &tmv);
        (void)ts;

        conf_set("VOLUME_CHECK_ASSESS", "1");
        conf_set("CHECK_ANDROID_VER", android_ver);
        conf_set("CHECK_ANDROID_SDK", android_sdk);
        conf_set("CHECK_OS_NAME", os);
        conf_set("CHECK_DEVICE", device);
        conf_set("CHECK_TIME", tm);
        if (a2 && *a2) conf_set("VOL_UP_DEV", a2);
        if (a3 && *a3) conf_set("VOL_DOWN_DEV", a3);
        printf("OK 已保存\n");
        return 0;
    }
    if (strcmp(sub, "verify") == 0) {
        char b[PROP_VALUE_MAX], os[PROP_VALUE_MAX], dev[PROP_VALUE_MAX], model[PROP_VALUE_MAX];
        prop("ro.build.version.release", b, sizeof b);
        char android_ver[PROP_VALUE_MAX];
        snprintf(android_ver, sizeof android_ver, "%s", b);
        prop("ro.build.version.sdk", b, sizeof b);
        char android_sdk[PROP_VALUE_MAX];
        snprintf(android_sdk, sizeof android_sdk, "%s", b);
        prop("ro.mi.os.version.name", os, sizeof os);
        if (!os[0]) prop("ro.miui.ui.version.name", os, sizeof os);
        prop("ro.product.device", dev, sizeof dev);
        prop("ro.product.model", model, sizeof model);

        printf("INFO 安卓 %s (SDK %s)\n", android_ver, android_sdk);
        printf("INFO 系统 %s\n", os);
        printf("INFO 机型 %s (%s)\n\n", model, dev);

        char saved[PATH_MAX];
        conf_get("CHECK_OS_NAME", saved, sizeof saved, NULL);
        if (saved[0] && strcmp(saved, os) != 0) printf("WARN 系统版本已变化：%s -> %s\n", saved, os);
        conf_get("CHECK_ANDROID_VER", saved, sizeof saved, NULL);
        if (saved[0] && strcmp(saved, android_ver) != 0)
            printf("WARN 安卓版本已变化：%s -> %s\n", saved, android_ver);

        char up[PATH_MAX], down[PATH_MAX];
        detect_keys(up, sizeof up, down, sizeof down);
        int fail = 0;
        if (!up[0]) { printf("FAIL 未识别到音量+设备\n"); fail = 1; }
        if (!down[0]) { printf("FAIL 未识别到音量-设备\n"); fail = 1; }

        char hit[PATH_MAX];
        if (up[0]) {
            printf("WAIT 请按一次【音量上】\n");
            if (probe_key(up, "KEY_VOLUMEUP", 10, hit, sizeof hit)) printf("PASS 音量+ 校验成功\n");
            else { printf("FAIL 音量+ 未检测到\n"); fail = 1; }
        }
        if (down[0]) {
            printf("WAIT 请按一次【音量下】\n");
            if (probe_key(down, "KEY_VOLUMEDOWN", 10, hit, sizeof hit)) printf("PASS 音量- 校验成功\n");
            else { printf("FAIL 音量- 未检测到\n"); fail = 1; }
        }
        if (fail) { printf("RESULT 校验失败\n"); return 1; }

        char tm[64];
        time_t t = time(NULL);
        struct tm tmv;
        localtime_r(&t, &tmv);
        strftime(tm, sizeof tm, "%Y-%m-%d %H:%M", &tmv);
        conf_set("VOLUME_CHECK_ASSESS", "1");
        conf_set("CHECK_ANDROID_VER", android_ver);
        conf_set("CHECK_ANDROID_SDK", android_sdk);
        conf_set("CHECK_OS_NAME", os);
        conf_set("CHECK_DEVICE", dev);
        conf_set("CHECK_TIME", tm);
        printf("RESULT 校验通过\n");
        return 0;
    }
    printf("用法: ctl check env|keys|scan|up|down|save|verify\n");
    return 1;
}

/* ---------------- main ---------------- */

static void usage(void) {
    printf("ctl %s\n", VER);
    printf("用法: ctl [--dir <模块根>] <命令> [参数]\n");
    printf("  ls <dir> | prepare <apk> | test-run <救砖> <待测>\n");
    printf("  status | output [行数] | stop\n");
    printf("  rescue get|on|off | watch start <秒>|stop|status | boot | boot-start\n");
    printf("  check env|keys|scan|up|down|save [up] [down]|verify\n");
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IOLBF, 0);

    char dirarg[PATH_MAX] = "";
    int i = 1;
    for (; i < argc; i++) {
        if (strcmp(argv[i], "--version") == 0) { printf("ctl %s\n", VER); return 0; }
        if (strcmp(argv[i], "--help") == 0 || strcmp(argv[i], "-h") == 0) { usage(); return 0; }
        if (strcmp(argv[i], "--dir") == 0 && i + 1 < argc) {
            snprintf(dirarg, sizeof dirarg, "%s", argv[++i]);
            continue;
        }
        break;
    }

    if (dirarg[0]) {
        snprintf(moddir, sizeof moddir, "%s", dirarg);
    } else {
        /* bin/ctl → 模块根是上两级 */
        char self[PATH_MAX];
        ssize_t n = readlink("/proc/self/exe", self, sizeof self - 1);
        if (n <= 0) snprintf(self, sizeof self, "%s", argv[0]);
        else self[n] = 0;
        char *slash = strrchr(self, '/');
        if (slash) *slash = 0;
        slash = strrchr(self, '/');
        if (slash) *slash = 0;
        snprintf(moddir, sizeof moddir, "%s", self);
    }

    join(cfgdir, sizeof cfgdir, moddir, "config");
    join(workdir, sizeof workdir, moddir, "scripts/.work");
    join(p_conf, sizeof p_conf, cfgdir, "settings.conf");
    join(p_prop, sizeof p_prop, moddir, "module.prop");
    join(p_rescue, sizeof p_rescue, moddir, "MiuiSystemUI.apk");
    join(p_aim, sizeof p_aim, moddir, "system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk");
    join(p_action_pid, sizeof p_action_pid, workdir, "action.pid");
    join(p_action_out, sizeof p_action_out, workdir, "action.out");
    join(p_watch_pid, sizeof p_watch_pid, workdir, "watch.pid");
    join(p_watch_guard, sizeof p_watch_guard, workdir, "watch.guard");
    join(p_watch_state, sizeof p_watch_state, workdir, "watch.state");
    join(p_watch_out, sizeof p_watch_out, workdir, "watch.out");
    join(p_watch_limit, sizeof p_watch_limit, workdir, "watch.limit");
    join(p_watch_stop, sizeof p_watch_stop, workdir, "watch.stop");
    join(p_watchd, sizeof p_watchd, moddir, "bin/watchd");
    join(p_actiond, sizeof p_actiond, moddir, "bin/actiond");
    join(p_limit_cache, sizeof p_limit_cache, workdir, "limit.cache");

    if (i >= argc) { usage(); return 1; }

    const char *cmd = argv[i++];
    const char *a1 = i < argc ? argv[i++] : NULL;
    const char *a2 = i < argc ? argv[i++] : NULL;
    const char *a3 = i < argc ? argv[i++] : NULL;

    if (strcmp(cmd, "ls") == 0) return cmd_ls(a1 ? a1 : "/storage/emulated/0");
    if (strcmp(cmd, "prepare") == 0) return cmd_prepare(a1);
    if (strcmp(cmd, "test-run") == 0) return cmd_test_run(a1, a2);
    if (strcmp(cmd, "status") == 0) return cmd_status();
    if (strcmp(cmd, "output") == 0) return cmd_output(a1);
    if (strcmp(cmd, "stop") == 0) return cmd_stop();
    if (strcmp(cmd, "rescue") == 0) return cmd_rescue(a1);
    if (strcmp(cmd, "watch") == 0) return cmd_watch(a1, a2);
    if (strcmp(cmd, "boot") == 0) return cmd_boot();
    if (strcmp(cmd, "boot-start") == 0) return cmd_boot_start();
    if (strcmp(cmd, "log-run") == 0) { cmd_log_run(); return 0; }
    if (strcmp(cmd, "check") == 0) return cmd_check(a1, a2, a3);

    usage();
    return 1;
}
