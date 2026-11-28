package com.xkspeed.HyperMount.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xkspeed.HyperMount.core.McpService
import com.xkspeed.HyperMount.core.mcp.McpConfig
import com.xkspeed.HyperMount.core.mcp.McpServerState
import com.xkspeed.HyperMount.i18n.t
import com.xkspeed.HyperMount.ui.component.SteppedBottomSheet
import com.xkspeed.HyperMount.ui.component.glass.ScrollPage
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * MCP 服务设置
 *
 * 给 AI 客户端（Claude Code / Cursor 等）一个入口：
 * 传 apk → 跑测试 → 取结果和日志。
 *
 * 默认关闭。打开后立刻起前台服务监听端口，关掉立刻断开。
 * 地址 / 令牌两行都是「点按改配置、长按复制」：
 * 地址点开改端口，令牌点开改自定义令牌（访问密码开着才出现这张卡）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun McpPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(McpConfig.enabled) }
    var running by remember { mutableStateOf(McpServerState.running) }
    var error by remember { mutableStateOf(McpServerState.error) }
    var token by remember { mutableStateOf(McpConfig.token) }
    var lan by remember { mutableStateOf(McpConfig.lan) }
    var requireToken by remember { mutableStateOf(McpConfig.requireToken) }
    var port by remember { mutableIntStateOf(McpConfig.port) }
    val ip = remember { localIpv4() }
    val host = if (lan) (ip ?: t("手机IP", "phone-IP")) else "127.0.0.1"
    val url = "http://$host:$port/mcp"

    // 两个底部弹层的开关与草稿：关掉弹层不清草稿 下次打开会重新填当前值
    var showPortSheet by remember { mutableStateOf(false) }
    var showTokenSheet by remember { mutableStateOf(false) }
    var portDraft by remember { mutableStateOf("") }
    var tokenDraft by remember { mutableStateOf("") }

    ScrollPage(title = t("MCP 服务", "MCP service"), onBack = onBack) {
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
                    title = t("开启 MCP 服务", "Enable MCP service"),
                    summary = when {
                        !enabled -> t("关闭时不监听任何端口", "No ports are listened on while off")
                        error != null -> t("启动失败：$error", "Failed to start: $error")
                        running -> t("监听中 · :$port", "Listening · :$port")
                        else -> t("正在启动…", "Starting…")
                    },
                )
            }
        }

        item { Spacer(Modifier.height(12.dp)) }

        item {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SwitchPreference(
                    checked = lan,
                    onCheckedChange = { on ->
                        lan = on
                        McpConfig.setLan(context, on)
                        if (enabled) McpService.restart(context)
                    },
                    title = t("局域网访问", "LAN access"),
                    summary = if (lan) t("其他设备可通过 $host:$port 连接", "Other devices can connect via $host:$port")
                    else t("关闭时只有本机能连（127.0.0.1）", "Off: only this device can connect (127.0.0.1)"),
                )
                SwitchPreference(
                    checked = requireToken,
                    onCheckedChange = { on ->
                        requireToken = on
                        McpConfig.setRequireToken(context, on)
                        if (enabled) McpService.restart(context)
                    },
                    title = t("访问密码", "Access password"),
                    summary = if (requireToken) t("请求需带 Bearer 令牌", "Requests must carry a Bearer token") else t("关闭后任何请求直接放行", "Off: every request is allowed through"),
                )
            }
        }

        item { Spacer(Modifier.height(12.dp)) }

        // 地址：点按改端口 长按复制
        item {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                ArrowPreference(
                    title = t("连接地址", "Endpoint"),
                    summary = url,
                    onClick = null,
                    modifier = Modifier.combinedClickable(
                        onClickLabel = t("自定义端口", "Custom port"),
                        onLongClickLabel = t("复制地址", "Copy address"),
                        onClick = {
                            portDraft = port.toString()
                            showPortSheet = true
                        },
                        onLongClick = { copy(context, url, t("地址已复制", "Address copied")) },
                    ),
                )
            }
        }

        // 令牌整卡跟着「访问密码」走：关着就不必占地方 也就没有「未启用」那种副标题
        item {
            AnimatedVisibility(
                visible = requireToken,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        ArrowPreference(
                            title = t("访问令牌", "Access token"),
                            summary = token,
                            onClick = null,
                            modifier = Modifier.combinedClickable(
                                onClickLabel = t("自定义令牌", "Custom token"),
                                onLongClickLabel = t("复制令牌", "Copy token"),
                                onClick = {
                                    tokenDraft = token
                                    showTokenSheet = true
                                },
                                onLongClick = { copy(context, token, t("令牌已复制", "Token copied")) },
                            ),
                        )
                        ArrowPreference(
                            title = t("重新生成令牌", "Regenerate token"),
                            summary = t("旧令牌立刻失效", "The old token is revoked immediately"),
                            onClick = {
                                McpConfig.regenerateToken(context)
                                token = McpConfig.token
                                // 服务端读的是 McpConfig.token，不用重启也能换
                                toast(context, t("已重新生成", "Regenerated"))
                            },
                        )
                    }
                }
            }
        }
    }

    // 自定义端口：端口变了要重新 bind，开着服务就热重启一次
    SteppedBottomSheet(
        show = showPortSheet,
        title = t("自定义端口", "Custom port"),
        onDismissRequest = { showPortSheet = false },
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            TextField(
                value = portDraft,
                onValueChange = { input ->
                    portDraft = input.filter { it.isDigit() }.take(5)
                },
                label = t("端口（1024 - 65535）", "Port (1024 - 65535)"),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = t("取消", "Cancel"),
                    onClick = { showPortSheet = false },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = t("保存", "Save"),
                    onClick = {
                        val target = portDraft.toIntOrNull()
                        if (target == null || target !in 1024..65535) {
                            toast(context, t("端口要在 1024 - 65535 之间", "Port must be between 1024 and 65535"))
                            return@TextButton
                        }
                        showPortSheet = false
                        McpConfig.setPort(context, target)
                        port = McpConfig.port
                        if (enabled) McpService.restart(context)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }

    // 自定义令牌：服务端每次请求现读 McpConfig.token，改完不用重启
    SteppedBottomSheet(
        show = showTokenSheet,
        title = t("自定义令牌", "Custom token"),
        onDismissRequest = { showTokenSheet = false },
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            TextField(
                value = tokenDraft,
                onValueChange = { tokenDraft = it },
                label = t("令牌", "Token"),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = t("取消", "Cancel"),
                    onClick = { showTokenSheet = false },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = t("保存", "Save"),
                    onClick = {
                        val value = tokenDraft.trim()
                        if (value.isEmpty()) {
                            toast(context, t("令牌不能为空", "Token can't be empty"))
                            return@TextButton
                        }
                        showTokenSheet = false
                        McpConfig.token = value
                        McpConfig.save(context)
                        token = McpConfig.token
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
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
