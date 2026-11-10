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
import com.xkspeed.HyperMount.core.mcp.McpConfig
import com.xkspeed.HyperMount.core.mcp.McpServer
import com.xkspeed.HyperMount.core.mcp.McpServerState

/**
 * MCP 服务的前台壳
 *
 * 只做两件事：把进程钉住（用户切走后 socket 还在）、给一个能看的状态通知。
 * 真正的监听在 [McpServer]，开关一关就 stopSelf。
 */
class McpService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        McpConfig.load(this)
        val error = McpServer.startServer(this)
        startForeground(NOTIF_ID, buildNotification(error))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESTART) {
            // 改端口 / 局域网 / 密码后按新配置重新绑一次，不用退出应用
            McpServer.stopServer()
            McpServer.startServer(this)
            return START_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        McpServer.stopServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(error: String?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(if (error == null) "MCP 服务运行中" else "MCP 服务启动失败")
            .setContentText(
                if (error == null) "监听 :${McpServerState.port} · 等待 AI 连接"
                else error,
            )
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "MCP 服务",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "向局域网提供 MCP 接口期间常驻"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    companion object {
        private const val CHANNEL_ID = "mcp_service"
        private const val NOTIF_ID = 1002
        private const val ACTION_STOP = "com.xkspeed.HyperMount.STOP_MCP"
        private const val ACTION_RESTART = "com.xkspeed.HyperMount.RESTART_MCP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, McpService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, McpService::class.java))
        }

        fun restart(context: Context) {
            runCatching {
                context.startService(Intent(context, McpService::class.java).setAction(ACTION_RESTART))
            }
        }
    }
}
