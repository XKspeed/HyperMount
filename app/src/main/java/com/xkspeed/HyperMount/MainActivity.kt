package com.xkspeed.HyperMount

import android.Manifest
import android.content.pm.PackageManager
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

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也不影响守护 */ }

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
}
