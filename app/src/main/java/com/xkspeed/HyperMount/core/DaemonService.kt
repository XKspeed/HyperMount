package com.xkspeed.HyperMount.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.xkspeed.HyperMount.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.xkspeed.HyperMount.i18n.t

/**
 * 前台服务：守护运行期间把进程留在前台 避免被系统冻结
 *
 * 这是这个 App 存在的核心理由 —— 由 root shell 派生的 action.sh
 * 会跟着派发它的进程一起进 cached/frozen cgroup 切后台就停摆；
 * App 挂着前台服务时进程是 top-app su 子进程也不会被冻
 *
 * 本服务不承载业务逻辑 只负责"让进程活着 + 给用户一个能看进度的通知"。
 * 通知标题按来源分：全局常驻守护是「SystemUI守护中」，界面页点测试是「守护中」；
 * 副标题报累计救砖次数与这一轮的剩余时长（只到分钟）。
 *
 * 刷新策略：每 10 秒取一次状态，但只有文案真的变了才推通知。
 * 文案不含秒 一分钟最多变一次，所以常驻期间通知一分钟刷一次，
 * 而通道量从每秒一次降到一分钟一次（原来每秒一次 一天下来近十万次）。
 */
class DaemonService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ticker: Job? = null

    /** 服务自己起来的时刻：模块那边读不到起始时间时用它兜底 */
    private var startedAt = 0L
    private var status = DaemonStatus()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startedAt = nowSec()
        // 5 秒内必须把前台通知挂上 先把占位版挂出去 真实状态下一轮再填
        val (t0, x0) = contentOf(DaemonStatus(testSince = startedAt), startedAt)
        startForeground(NOTIF_ID, buildNotification(t0, x0))
        ticker = scope.launch { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch { runCatching { Module.stop() } }
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ticker?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun loop() {
        var misses = 0
        var lastTitle = ""
        var lastText = ""
        while (currentCoroutineContext().isActive) {
            // 次数与存活状态每 10 秒对一次：一条 sh 里取完 不每秒 fork
            status = runCatching { Module.daemonStatus() }.getOrDefault(DaemonStatus())
            if (status.active) {
                misses = 0
            } else {
                // 刚起来时脚本还没来得及写 pid 文件 连空三次才收摊
                misses++
                if (misses >= 3) {
                    stopSelf()
                    return
                }
            }
            val (title, text) = contentOf(status, nowSec())
            // 正文只到分钟 一分钟才变一次：变了才推
            // 原来每秒刷一次 常驻一天就是近十万次通知
            if (title != lastTitle || text != lastText) {
                lastTitle = title
                lastText = text
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, buildNotification(title, text))
            }
            delay(POLL_MS)
        }
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    /** 标题与正文：只剩分钟粒度 所以一分钟最多变一次 */
    private fun contentOf(st: DaemonStatus, now: Long): Pair<String, String> {
        val since = st.since.takeIf { it > 0L } ?: startedAt
        val elapsed = (now - since).coerceAtLeast(0L)
        val title = if (st.active) st.title else t("守护中", "Rescue daemon")
        // 报剩余时长而不是已经跑了多久：用户关心的是还剩多少时间
        // 设了上限才倒计时 无限制（或点测试那一轮）直接写「不限」
        val remain = if (st.limit > 0L) (st.limit - elapsed).coerceAtLeast(0L) else -1L
        val left = if (remain < 0L) t("不限", "unlimited") else fmtRemain(remain)
        val text = t("已救砖：${st.protectCount}次｜剩余时长：", "Rescued ${st.protectCount} times ｜ Time left: ") + left
        return title to text
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_LOGS, true)
                // App 已经在跑就复用 不要叠一层 Activity
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()
    }

    /** 1小时23分 / 23分：进位到分钟，59 秒算 1 分（写 0 分像是已经结束） */
    private fun fmtRemain(sec: Long): String {
        val min = (sec + 59) / 60
        val h = min / 60
        val m = min % 60
        return if (h > 0) t("${h}小时${m}分", "${h}h ${m}m") else t("${m}分", "${m}m")
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            t("救砖守护", "Rescue daemon"),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = t("守护进程运行期间常驻", "Stays on while the rescue daemon is running")
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    companion object {
        private const val CHANNEL_ID = "rescue_daemon"
        private const val NOTIF_ID = 1001
        private const val ACTION_STOP = "com.xkspeed.HyperMount.STOP_DAEMON"

        /** 取状态的间隔：正文只到分钟 一分钟才变一次 10 秒足够跟上 */
        private const val POLL_MS = 10_000L

        fun start(context: Context) {
            val i = Intent(context, DaemonService::class.java)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DaemonService::class.java))
        }
    }
}
