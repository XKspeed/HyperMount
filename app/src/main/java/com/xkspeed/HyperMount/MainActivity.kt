package com.xkspeed.HyperMount

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.xkspeed.HyperMount.ui.App
import com.xkspeed.HyperMount.ui.UiConfigState
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也不影响守护 */ }

    /** 通知点进来要看日志：Activity 已经在跑时不重建 走这里把请求递进去 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_LOGS, false)) {
            intent.removeExtra(EXTRA_OPEN_LOGS)
            _openLogs.tryEmit(Unit)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 前台服务通知需要权限（Android 13+）；不给也能跑 只是通知看不到
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 先恢复设置 再进 setContent 否则重启永远回到"跟随系统"
        ThemeState.load(this)
        UiConfigState.load(this)
        // MCP 开关是常驻的：上次开着 这次启动直接恢复监听
        com.xkspeed.HyperMount.core.mcp.McpConfig.load(this)
        if (com.xkspeed.HyperMount.core.mcp.McpConfig.enabled) {
            com.xkspeed.HyperMount.core.McpService.start(this)
        }

        setContent {
            val controller = remember(
                ThemeState.mode,
                ThemeState.seedIndex,
                ThemeState.paletteStyle,
                ThemeState.colorSpec,
            ) {
                val keyColor = keyColorFor(ThemeState.seedIndex)
                val spec = ThemeColorSpec.entries.getOrNull(ThemeState.colorSpec)
                    ?: ThemeColorSpec.Spec2021
                val style = ThemePaletteStyle.entries.getOrNull(ThemeState.paletteStyle)
                    ?: ThemePaletteStyle.Content
                when (ThemeState.mode) {
                    ThemeState.MODE_LIGHT -> ThemeController(ColorSchemeMode.Light)
                    ThemeState.MODE_DARK -> ThemeController(ColorSchemeMode.Dark)
                    ThemeState.MODE_MONET_SYSTEM -> ThemeController(
                        ColorSchemeMode.MonetSystem,
                        keyColor = keyColor,
                        colorSpec = spec,
                        paletteStyle = style,
                    )

                    ThemeState.MODE_MONET_LIGHT -> ThemeController(
                        ColorSchemeMode.MonetLight,
                        keyColor = keyColor,
                        colorSpec = spec,
                        paletteStyle = style,
                    )

                    ThemeState.MODE_MONET_DARK -> ThemeController(
                        ColorSchemeMode.MonetDark,
                        keyColor = keyColor,
                        colorSpec = spec,
                        paletteStyle = style,
                    )

                    else -> ThemeController(ColorSchemeMode.System)
                }
            }
            MiuixTheme(controller = controller) {
                Box(Modifier.fillMaxSize().imePadding()) {
                    App()
                }
            }
        }
    }

    companion object {
        /** 守护通知带过来的标记：进 App 直接翻到应用日志 */
        const val EXTRA_OPEN_LOGS = "open_logs"

        private val _openLogs = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val openLogs: SharedFlow<Unit> = _openLogs
    }
}
