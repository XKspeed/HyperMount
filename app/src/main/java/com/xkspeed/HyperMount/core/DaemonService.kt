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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台服务：守护运行期间把进程留在前台 避免被系统冻结
 *
 * 这是这个 App 存在的核心理由 —— WebUI 里 ksu.exec 派生的 action.sh
 * 会跟着管理器进程一起进 cached/frozen cgroup 切后台就停摆；
 * App 挂着前台服务时进程是 top-app su 子进程也不会被冻
 *
 * 本服务不承载业务逻辑 只负责"让进程活着 + 给用户一个可见的入口"
 */
class DaemonService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch { runCatching { Module.stop() } }
            stopSelf()
            return START_NOT_STICKY
        }
        // 守护已经结束就自己退 别留一个空通知
        scope.launch {
            val running = runCatching { Module.readState().daemonRunning }.getOrDefault(false)
            if (!running) stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "救砖守护",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "守护进程运行期间常驻"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("救砖守护运行中")
            .setContentText("切走或锁屏不会中断 回到应用可查看日志")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "rescue_daemon"
        private const val NOTIF_ID = 1001
        private const val ACTION_STOP = "com.xkspeed.HyperMount.STOP_DAEMON"

        fun start(context: Context) {
            val i = Intent(context, DaemonService::class.java)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DaemonService::class.java))
        }
    }
}
