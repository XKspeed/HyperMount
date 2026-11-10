package com.xkspeed.HyperMount.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xkspeed.HyperMount.core.McpService
import com.xkspeed.HyperMount.core.mcp.McpConfig
import com.xkspeed.HyperMount.core.mcp.McpServerState
import com.xkspeed.HyperMount.ui.component.glass.ScrollPage
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * MCP 服务设置
 *
 * 给 AI 客户端（Claude Code / Cursor 等）一个局域网入口：
 * 传 apk → 跑测试 → 取结果和日志。
 *
 * 默认关闭。打开后立刻起前台服务监听端口，关掉立刻断开；
 * 令牌必须带，任何拿到它的设备都能让这台手机装包。
 */
@Composable
fun McpPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(McpConfig.enabled) }
    var running by remember { mutableStateOf(McpServerState.running) }
    var error by remember { mutableStateOf(McpServerState.error) }
    var token by remember { mutableStateOf(McpConfig.token) }
    val ip = remember { localIpv4() }
    val host = ip ?: "手机IP"
    val url = "http://$host:${McpConfig.port}/mcp"

    ScrollPage(title = "MCP 服务", onBack = onBack) {
        item {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SwitchPreference(
                    checked = enabled,
                    onCheckedChange = { on ->
                        enabled = on
                        McpConfig.setEnabled(context, on)
                        if (on) {
                            McpService.start(context)
                            running = true
                            error = null
                        } else {
                            McpService.stop(context)
                            running = false
                            error = null
                        }
                    },
                    title = "开启 MCP 服务",
                    summary = when {
                        !enabled -> "关闭时不监听任何端口"
                        error != null -> "启动失败：$error"
                        running -> "监听中 · :${McpConfig.port}"
                        else -> "正在启动…"
                    },
                )
            }
        }

        item { Spacer(Modifier.height(12.dp)) }

        item {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                ArrowPreference(
                    title = "连接地址",
                    summary = url,
                    onClick = { copy(context, url, "地址已复制") },
                )
                ArrowPreference(
                    title = "访问令牌",
                    summary = token,
                    onClick = { copy(context, token, "令牌已复制") },
                )
                ArrowPreference(
                    title = "重新生成令牌",
                    summary = "旧令牌立刻失效",
                    onClick = {
                        McpConfig.regenerateToken(context)
                        token = McpConfig.token
                        // 服务端读的是 McpConfig.token，不用重启也能换
                        toast(context, "已重新生成")
                    },
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }

        item {
            Text(
                text = "客户端配置（Claude Code）：\n" +
                    "claude mcp add --transport http hypermount $url \\\n" +
                    "  --header \"Authorization: Bearer $token\"\n\n" +
                    "可用工具：upload_systemui（传 apk）、run_test（开始测试）、" +
                    "get_test_result（取结论）、get_logs（日志尾部 + 下载地址）。\n\n" +
                    "这项服务等于在局域网里开了一个 root 装包入口，只在需要时打开。",
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

private fun localIpv4(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        ?.hostAddress
}.getOrNull()

private fun copy(context: Context, text: String, msg: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("mcp", text))
    toast(context, msg)
}

private fun toast(context: Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
