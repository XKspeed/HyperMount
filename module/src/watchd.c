/*
 * watchd —— 全局常驻救砖守护（单进程实现）
 *
 * 取代原来的 scripts/watch.sh + 两个 shell 子进程 + logcat + --guard 四件套：
 *   一个进程跑事件循环，读 /proc 找 systemui 的 pid，用 pidfd 盯住它，
 *   进程一退出 poll 立刻返回（内核 5.3+，Android 11 起），零轮询零 fork。
 *   老内核没有 pidfd_open 的机器自动退回每秒扫一次 /proc（1ms 左右）。
 *
 * 判定和原来一致：WATCH_WINDOW 秒内 systemui 重启 WATCH_COUNT 次，才调
 * actiond --now 去抓日志、判 fatal、走音量键。常驻只是把「什么时候开始
 * 看日志」提前，判定规则完全没改。
 *
 * 两个进程互保：主进程 fork 一个 --guard 子进程（同一份代码页，几乎不额外
 * 占内存），主进程被 kill -9 后由它重新拉起，since 和窗口内的时间戳从
 * watch.state 原样恢复，App 里显示的已运行时长不会跳变。
 *
 * 对外契约和 sh 版完全一致，App 侧零改动：
 *   scripts/.work/watch.state   running/since/pid/window/count/stamps
 *   scripts/.work/watch.out     HH:MM:SS 文本
 *   scripts/.work/watch.limit   跑多久，0 = 不限
 *   scripts/.work/watch.stop    存在即收尾
 *   scripts/.work/watch.pid     主进程 pid
 *   scripts/.work/watch.guard   保护进程 pid
 *   config/.self_restart        模块自己换界面那次重启的时间戳，消费即删
 *
 * 用法：
 *   watchd [--dir <模块根目录>]
 *   watchd --guard [--dir ...]
 *   watchd --version
 *
 * 不带 --dir 时按 /proc/self/exe 推导：<模块根>/bin/watchd → <模块根>。
 */

#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define VERSION "1.0"
#define STAMP_MAX 32

static char moddir[PATH_MAX];
static char workdir[PATH_MAX];
static char cfgdir[PATH_MAX];
static char exe_path[PATH_MAX];

static char p_state[PATH_MAX];
static char p_out[PATH_MAX];
static char p_pid[PATH_MAX];
static char p_guard[PATH_MAX];
static char p_limit[PATH_MAX];
static char p_stop[PATH_MAX];
static char p_selfmark[PATH_MAX];
static char p_action[PATH_MAX];
static char p_actiond[PATH_MAX];

static int WINDOW = 30;
static int COUNT = 3;
static int TICK = 5;

static time_t since = 0;
static time_t stamps[STAMP_MAX];
static int nstamps = 0;
static time_t last_event_ts = 0;

static pid_t watched = -1;
static int pidfd = -1;
static pid_t action_pid = 0;

static volatile sig_atomic_t quit = 0;

/* ---------------- 小工具 ---------------- */

static void logmsg(const char *fmt, ...) {
    char ts[16];
    time_t t = time(NULL);
    struct tm tm;
    localtime_r(&t, &tm);
    strftime(ts, sizeof ts, "%H:%M:%S", &tm);

    FILE *f = fopen(p_out, "a");
    if (!f) return;
    fprintf(f, "%s ", ts);
    va_list ap;
    va_start(ap, fmt);
    vfprintf(f, fmt, ap);
    va_end(ap);
    fputc('\n', f);
    fclose(f);
}

static void msleep(int ms) {
    struct timespec ts;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (long)(ms % 1000) * 1000000L;
    nanosleep(&ts, NULL);
}

static long long read_num(const char *path) {
    FILE *f = fopen(path, "r");
    if (!f) return 0;
    long long v = 0;
    if (fscanf(f, "%lld", &v) != 1) v = 0;
    fclose(f);
    return v < 0 ? 0 : v;
}

/* pid 文件里的脏值一律当没有 */
static pid_t read_pid_file(const char *path) {
    long long v = read_num(path);
    if (v <= 0 || v > 4194304) return 0;
    return (pid_t)v;
}

static void write_pid_file(const char *path, pid_t p) {
    FILE *f = fopen(path, "w");
    if (!f) return;
    fprintf(f, "%d\n", (int)p);
    fclose(f);
}

/* ---------------- 状态 ---------------- */

/* 和 App 读的那份格式逐字一致；临时文件 + rename 避免读到半截 */
static void write_state(int cnt) {
    char tmp[PATH_MAX + 8];
    snprintf(tmp, sizeof tmp, "%s.tmp", p_state);
    FILE *f = fopen(tmp, "w");
    if (!f) return;
    fprintf(f, "running=1\nsince=%lld\npid=%d\nwindow=%d\ncount=%d\nstamps=",
            (long long)since, (int)getpid(), WINDOW, cnt);
    for (int i = 0; i < nstamps; i++) fprintf(f, " %lld", (long long)stamps[i]);
    fputc('\n', f);
    fclose(f);
    rename(tmp, p_state);
}

static void write_stopped(void) {
    char tmp[PATH_MAX + 8];
    snprintf(tmp, sizeof tmp, "%s.tmp", p_state);
    FILE *f = fopen(tmp, "w");
    if (!f) return;
    fprintf(f, "running=0\n");
    fclose(f);
    rename(tmp, p_state);
}

/* 接着上一轮的窗口跑：guard 拉起来的实例不会让已运行时长清零 */
static void load_state(void) {
    FILE *f = fopen(p_state, "r");
    if (!f) return;
    char line[2048];
    int running = 0;
    long long s = 0;
    nstamps = 0;
    while (fgets(line, sizeof line, f)) {
        if (!strncmp(line, "running=", 8)) {
            running = atoi(line + 8);
        } else if (!strncmp(line, "since=", 6)) {
            s = atoll(line + 6);
        } else if (!strncmp(line, "stamps=", 7)) {
            char *p = line + 7;
            while (*p) {
                while (*p == ' ' || *p == '\t' || *p == '\n') p++;
                if (!*p) break;
                char *end = NULL;
                long long v = strtoll(p, &end, 10);
                if (end == p) break;
                p = end;
                if (v > 0 && nstamps < STAMP_MAX) stamps[nstamps++] = (time_t)v;
            }
        }
    }
    fclose(f);
    if (running != 1) {
        s = 0;
        nstamps = 0;
    }
    since = (time_t)s;
}

/* ---------------- 找 systemui ---------------- */

/* comm 会被内核截成 15 字符（ndroid.systemui），cmdline 里才是完整包名 */
static pid_t scan_systemui(void) {
    DIR *d = opendir("/proc");
    if (!d) return -1;
    struct dirent *e;
    pid_t found = -1;
    while ((e = readdir(d)) != NULL) {
        if (!isdigit((unsigned char)e->d_name[0])) continue;
        char path[288];
        snprintf(path, sizeof path, "/proc/%s/cmdline", e->d_name);
        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        char buf[128];
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

/* pidfd：内核 5.3+ / Android 11 起，进程退出时 fd 变可读，比 inotify 可靠
 * （inotify 对 /proc/<pid> 目录根本不发 IN_DELETE_SELF，实测过）。
 * 老内核返回 ENOSYS，调用方退回轮询。 */
static int open_pidfd(pid_t pid) {
#ifdef SYS_pidfd_open
    return (int)syscall(SYS_pidfd_open, pid, 0);
#else
    return (int)syscall(434, pid, 0);
#endif
}

static void detach(void) {
    if (pidfd >= 0) close(pidfd);
    pidfd = -1;
}

static int attach(pid_t pid) {
    detach();
    pidfd = open_pidfd(pid);
    if (pidfd < 0) pidfd = -1;
    return pidfd >= 0;
}

/* ---------------- 计数与触发 ---------------- */

static void count_push(time_t ts) {
    time_t lo = ts - WINDOW;
    int k = 0;
    for (int i = 0; i < nstamps; i++) {
        if (stamps[i] >= lo) stamps[k++] = stamps[i];
    }
    nstamps = k;
    if (nstamps >= STAMP_MAX) nstamps = STAMP_MAX - 1;
    stamps[nstamps++] = ts;
}

/* 模块自己换界面引起的那次重启不算崩溃：action.sh 覆盖完成后写时间戳，
 * 紧随其后的第一条事件跳过。消费即删，超过 5 分钟作废，免得吞掉真崩溃。 */
static int skip_self_restart(time_t ts) {
    if (access(p_selfmark, F_OK) != 0) return 0;
    long long v = read_num(p_selfmark);
    unlink(p_selfmark);
    if (v <= 0) return 0;
    long long age = (long long)ts - v;
    if (age < 0) age = -age;
    return age <= 300;
}

/* 回收所有已结束的子进程。不回收的话 guard 被 kill 后会留成僵尸，
 * 而 kill(zombie, 0) 仍然返回成功，主进程就会以为它活着、永远不补位。 */
static void reap_children(void) {
    pid_t p;
    while ((p = waitpid(-1, NULL, WNOHANG)) > 0) {
        if (p == action_pid) action_pid = 0;
    }
}

/* 够阈值：清计数，交给 actiond 的 --now（抓日志 + fatal 判定 + 音量键） */
static void fire(int n) {
    logmsg("%d 秒内界面重启 %d 次，开始抓日志判定", WINDOW, n);
    nstamps = 0;
    write_state(0);

    /* 上一轮救援还没收尾就不再叠加一个，等它结束 */
    if (action_pid > 0) {
        logmsg("上一轮救援还在进行，本次不重复触发");
        return;
    }

    pid_t p = fork();
    if (p < 0) {
        logmsg("拉起 action.sh 失败");
        return;
    }
    if (p == 0) {
        int fd = open(p_out, O_WRONLY | O_CREAT | O_APPEND, 0644);
        if (fd >= 0) {
            dup2(fd, STDOUT_FILENO);
            dup2(fd, STDERR_FILENO);
            if (fd > STDERR_FILENO) close(fd);
        }
        /* 优先用 C 版；它不存在或 exec 不了（权限 / 架构）再用 sh 那份 */
        if (access(p_actiond, X_OK) == 0) {
            execl(p_actiond, "actiond", "--dir", moddir, "--now", (char *)NULL);
        }
        execl("/system/bin/sh", "sh", p_action, "--now", (char *)NULL);
        execlp("sh", "sh", p_action, "--now", (char *)NULL);
        _exit(127);
    }
    action_pid = p;
}

static void on_restart(time_t ts) {
    /* 同一秒内可能连着来 IN_DELETE_SELF 和 IN_IGNORED，只算一次 */
    if (ts == last_event_ts) return;
    last_event_ts = ts;

    detach();
    watched = -1;

    if (skip_self_restart(ts)) {
        logmsg("模块自身替换引起重启 不计入崩溃次数");
        return;
    }
    count_push(ts);
    write_state(nstamps);
    logmsg("界面第 %d 次重启（%d 秒内）", nstamps, WINDOW);
    if (nstamps >= COUNT) fire(nstamps);
}

/* 对账：pidfd 拿不到（老内核）时全靠它；拿得到也留着兜底，代价是 4 秒扫一次 */
static void verify(void) {
    pid_t p = scan_systemui();
    if (p <= 0) return;
    if (p == watched && pidfd >= 0) return;
    if (watched > 0 && p != watched) {
        on_restart(time(NULL));
        return;
    }
    attach(p);
    watched = p;
}

/* ---------------- 互保 ---------------- */

static void guard_main(void) {
    detach();

    /* 用 flock 保证只有一个保护进程。光靠「读 pid 文件看有没有人」不行：
     * 主进程拉起 guard 之后马上就进循环了，两个实例可能都还没写文件，
     * 于是都认为自己才是唯一的那个，最后养出两个 guard。 */
    int lock = open(p_guard, O_RDWR | O_CREAT, 0644);
    if (lock < 0) _exit(0);
    if (flock(lock, LOCK_EX | LOCK_NB) != 0) _exit(0);
    /* 主进程被 guard 重启时会 exec 一份新的自己，别让锁跟着 fd 继承过去 */
    fcntl(lock, F_SETFD, FD_CLOEXEC);

    if (ftruncate(lock, 0) != 0) { /* 写不进去也继续，只是 App 看不到 pid */ }
    dprintf(lock, "%d\n", (int)getpid());

    time_t last = 0;
    int miss = 0;
    for (;;) {
        sleep((unsigned)TICK);
        reap_children();
        if (access(p_stop, F_OK) == 0) _exit(0);
        pid_t m = read_pid_file(p_pid);
        if (m > 0 && kill(m, 0) == 0) continue;

        time_t now = time(NULL);
        if (now - last > 30) miss = 1;
        else miss++;
        last = now;
        if (miss > 5) {
            logmsg("主进程连续 %d 次没能起来，放弃守护", miss);
            _exit(0);
        }
        logmsg("主进程失联，第 %d 次拉起", miss);

        pid_t c = fork();
        if (c == 0) {
            setsid();
            /* argv[0] 用完整路径，ps 里跟主进程长得一样，方便对 pid */
            execl(exe_path, exe_path, "--dir", moddir, (char *)NULL);
            _exit(127);
        }
        /* 等它把 pid 写出来，最多 4 秒 */
        for (int i = 0; i < 8; i++) {
            pid_t np = read_pid_file(p_pid);
            if (np > 0 && kill(np, 0) == 0) break;
            msleep(500);
        }
    }
}

static pid_t spawn_guard(void) {
    pid_t g = fork();
    if (g < 0) return 0;
    if (g == 0) {
        setsid();
        guard_main();
        _exit(0);
    }
    return g;
}

/* 保护进程死了就补一个；连续起不来就放弃，别一直 fork */
static void keep_guard(void) {
    static int g_miss = 0;
    static time_t g_last = 0;
    static int g_dead = 0;
    if (g_dead) return;

    pid_t g = read_pid_file(p_guard);
    if (g > 0 && kill(g, 0) == 0) {
        g_miss = 0;
        return;
    }
    time_t now = time(NULL);
    if (now - g_last > 30) g_miss = 1;
    else g_miss++;
    g_last = now;
    if (g_miss > 3) {
        g_dead = 1;
        logmsg("保护进程起不来，暂停互保");
        return;
    }
    spawn_guard();
}

/* ---------------- 收尾 ---------------- */

static void cleanup(void) {
    static int done = 0;
    if (done) return;
    done = 1;

    FILE *f = fopen(p_stop, "w");
    if (f) fclose(f);

    if (action_pid > 0) {
        kill(action_pid, SIGTERM);
        action_pid = 0;
    }
    pid_t g = read_pid_file(p_guard);
    if (g > 0) kill(g, SIGTERM);

    detach();

    unlink(p_pid);
    unlink(p_guard);
    write_stopped();
    logmsg("监测结束");
}

static void on_signal(int s) {
    (void)s;
    quit = 1;
}

static int limit_reached(void) {
    long long lim = read_num(p_limit);
    if (lim <= 0) return 0;
    return (long long)(time(NULL) - since) >= lim;
}

/* ---------------- 目录与配置 ---------------- */

static void join(char *dst, size_t n, const char *dir, const char *name) {
    snprintf(dst, n, "%s/%s", dir, name);
}

static void resolve_dirs(const char *opt, const char *argv0) {
    char exe[PATH_MAX];
    exe[0] = 0;
    ssize_t n = readlink("/proc/self/exe", exe, sizeof exe - 1);
    if (n > 0) {
        exe[n] = 0;
    } else if (argv0) {
        if (!realpath(argv0, exe)) snprintf(exe, sizeof exe, "%s", argv0);
    }
    snprintf(exe_path, sizeof exe_path, "%s", exe);

    if (opt && *opt) {
        snprintf(moddir, sizeof moddir, "%s", opt);
    } else {
        char tmp[PATH_MAX];
        snprintf(tmp, sizeof tmp, "%s", exe);
        char *slash = strrchr(tmp, '/');
        if (slash) *slash = 0; /* bin */
        slash = strrchr(tmp, '/');
        if (slash) *slash = 0; /* 模块根 */
        snprintf(moddir, sizeof moddir, "%s", tmp);
    }
    join(workdir, sizeof workdir, moddir, "scripts/.work");
    join(cfgdir, sizeof cfgdir, moddir, "config");

    join(p_state, sizeof p_state, workdir, "watch.state");
    join(p_out, sizeof p_out, workdir, "watch.out");
    join(p_pid, sizeof p_pid, workdir, "watch.pid");
    join(p_guard, sizeof p_guard, workdir, "watch.guard");
    join(p_limit, sizeof p_limit, workdir, "watch.limit");
    join(p_stop, sizeof p_stop, workdir, "watch.stop");
    join(p_selfmark, sizeof p_selfmark, cfgdir, ".self_restart");
    join(p_action, sizeof p_action, moddir, "action.sh");
    join(p_actiond, sizeof p_actiond, moddir, "bin/actiond");
}

static void load_config(void) {
    char path[PATH_MAX];
    join(path, sizeof path, cfgdir, "settings.conf");
    FILE *f = fopen(path, "r");
    if (!f) return;
    char line[512];
    while (fgets(line, sizeof line, f)) {
        char *p = line;
        while (*p == ' ' || *p == '\t') p++;
        if (*p == '#' || *p == '\n' || !*p) continue;
        int v = 0;
        if (sscanf(p, "WATCH_WINDOW=%d", &v) == 1) WINDOW = v;
        else if (sscanf(p, "WATCH_COUNT=%d", &v) == 1) COUNT = v;
        else if (sscanf(p, "WATCH_GUARD_TICK=%d", &v) == 1) TICK = v;
    }
    fclose(f);

    if (WINDOW < 5 || WINDOW > 600) WINDOW = 30;
    if (COUNT < 1 || COUNT > 20) COUNT = 3;
    if (TICK < 1 || TICK > 60) TICK = 5;
}

/* ---------------- 主进程 ---------------- */

static int supervisor_main(void) {
    /* 防重入：同一时间只跑一个 */
    pid_t old = read_pid_file(p_pid);
    if (old > 0 && old != getpid() && kill(old, 0) == 0) {
        logmsg("已有守护在跑（%d），本实例退出", (int)old);
        return 0;
    }

    load_state();
    if (since <= 0) since = time(NULL);

    /* 已经过了设定时长 直接收摊（guard 重启时可能走到这儿） */
    if (limit_reached()) {
        logmsg("已到设定时长，不再启动");
        write_stopped();
        return 0;
    }

    write_pid_file(p_pid, getpid());

    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_signal;
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGHUP, &sa, NULL);
    signal(SIGPIPE, SIG_IGN);

    write_state(nstamps);
    logmsg("开始监测 窗口=%ds 阈值=%d次 互保=%ds", WINDOW, COUNT, TICK);

    /* 上一轮的 guard 还活着就接着用它，不要每次被拉起都再养一个 */
    pid_t g = read_pid_file(p_guard);
    if (g > 0 && kill(g, 0) == 0) logmsg("沿用保护进程 %d", (int)g);
    else spawn_guard();

    time_t last_verify = 0;
    while (!quit) {
        if (access(p_stop, F_OK) == 0) break;
        if (limit_reached()) {
            logmsg("到达设定时长，结束监测");
            break;
        }
        keep_guard();
        reap_children();

        if (watched <= 0) {
            pid_t p = scan_systemui();
            if (p > 0) {
                if (!attach(p)) {
                    /* 老内核没有 pidfd_open，退回每秒扫一次 /proc */
                    static int warned = 0;
                    if (!warned) {
                        warned = 1;
                        logmsg("内核不支持 pidfd，退回 PID 对账");
                    }
                }
                watched = p;
                last_verify = time(NULL);
            } else {
                /* 界面正在重启 稍后再找 */
                msleep(500);
                continue;
            }
        }

        if (pidfd >= 0) {
            struct pollfd pf;
            pf.fd = pidfd;
            pf.events = POLLIN;
            pf.revents = 0;
            int r = poll(&pf, 1, 2000);
            if (r > 0 && (pf.revents & POLLIN)) on_restart(time(NULL));
        } else {
            msleep(1000);
        }

        time_t now = time(NULL);
        /* 有 pidfd 就 4 秒对一次账；没有就每秒对，全靠它发现 */
        if (now - last_verify >= (pidfd >= 0 ? 4 : 1)) {
            verify();
            last_verify = now;
        }
    }

    cleanup();
    return 0;
}

int main(int argc, char **argv) {
    int is_guard = 0;
    const char *opt_dir = NULL;

    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--guard")) {
            is_guard = 1;
        } else if (!strcmp(argv[i], "--dir") && i + 1 < argc) {
            opt_dir = argv[++i];
        } else if (!strncmp(argv[i], "--dir=", 6)) {
            opt_dir = argv[i] + 6;
        } else if (!strcmp(argv[i], "--version") || !strcmp(argv[i], "-v")) {
            printf("watchd %s\n", VERSION);
            return 0;
        } else if (!strcmp(argv[i], "--help") || !strcmp(argv[i], "-h")) {
            printf("用法: watchd [--dir <模块根目录>] [--guard]\n");
            return 0;
        }
    }

    resolve_dirs(opt_dir, argv[0]);
    load_config();
    mkdir(workdir, 0755);

    if (is_guard) {
        guard_main();
        return 0;
    }
    return supervisor_main();
}
