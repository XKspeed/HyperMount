package com.xkspeed.HyperMount.core.mcp

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.SecureRandom

/**
 * MCP 服务配置
 *
 * 默认关闭：这个口子一旦打开 就是「局域网内任何拿到令牌的设备都能 root 装包」。
 * 打开后由 [com.xkspeed.HyperMount.core.McpService] 起前台服务，
 * 关掉开关立刻断开监听。
 */
object McpConfig {
    private const val PREFS = "mcp_config"

    const val DEFAULT_PORT = 8790

    var enabled by mutableStateOf(false)
    var port by mutableIntStateOf(DEFAULT_PORT)

    /** 是否允许局域网访问。默认关，只听 127.0.0.1 */
    var lan by mutableStateOf(false)

    /** 是否校验访问令牌。默认关，只听本机时不必多一道握手 */
    var requireToken by mutableStateOf(false)

    /** 访问令牌：密码开关打开后要求 Bearer <token> 或 ?token= */
    var token by mutableStateOf("")

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = p.getBoolean("enabled", false)
        port = p.getInt("port", DEFAULT_PORT).coerceIn(1024, 65535)
        lan = p.getBoolean("lan", false)
        requireToken = p.getBoolean("require_token", false)
        token = p.getString("token", "").orEmpty()
        // 老用户第一次打开时没有令牌：当场生成一份 之后一直复用
        if (token.isBlank()) {
            token = newToken()
            save(context)
        }
    }

    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        save(context)
    }

    fun setPort(context: Context, value: Int) {
        port = value.coerceIn(1024, 65535)
        save(context)
    }

    fun setLan(context: Context, value: Boolean) {
        lan = value
        save(context)
    }

    fun setRequireToken(context: Context, value: Boolean) {
        requireToken = value
        save(context)
    }

    fun regenerateToken(context: Context) {
        token = newToken()
        save(context)
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putBoolean("enabled", enabled)
            putInt("port", port)
            putBoolean("lan", lan)
            putBoolean("require_token", requireToken)
            putString("token", token)
        }.apply()
    }

    private fun newToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * 服务运行状态：设置页读它显示「监听中 / 未启动 / 启动失败」
 *
 * 只在内存里 不进 prefs —— 进程重启后服务本来就要重新拉起。
 */
object McpServerState {
    var running by mutableStateOf(false)
    var port by mutableIntStateOf(McpConfig.DEFAULT_PORT)
    var error by mutableStateOf<String?>(null)

    fun markRunning(ok: Boolean, onPort: Int, message: String?) {
        running = ok
        port = onPort
        error = message
    }
}
