package com.xkspeed.HyperMount.ui

import android.content.Context
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.xkspeed.HyperMount.KeyColors
import com.xkspeed.HyperMount.ThemeState
import com.xkspeed.HyperMount.core.AppLog
import com.xkspeed.HyperMount.core.UpdateChannel
import com.xkspeed.HyperMount.core.SystemVersion
import com.xkspeed.HyperMount.ui.component.glass.GlassEffectState
import com.xkspeed.HyperMount.ui.component.glass.ScrollPage
import com.xkspeed.HyperMount.ui.components.BackIcon
import com.xkspeed.HyperMount.ui.components.SectionCard
import com.xkspeed.HyperMount.ui.components.SliderInputPreference
import com.xkspeed.HyperMount.ui.util.pageContentPadding
import com.xkspeed.HyperMount.ui.util.pageScrollModifiers
import com.xkspeed.HyperMount.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference
import com.xkspeed.HyperMount.ui.components.LiquidSelectHost
import com.xkspeed.HyperMount.ui.components.LiquidSelectPreference
import com.xkspeed.HyperMount.ui.components.rememberLiquidSelectState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * 界面偏好（和主题分开存 重启都能恢复）
 */
object UiConfigState {
    var enableBlur by mutableStateOf(true)

    /** 预测性返回预览：打开时手势拖动页面跟着走 关闭时手指拖动页面不动 松手才播放返回动画 */
    var predictiveBack by mutableStateOf(true)

    /** 启动时自动查一次更新 关掉就只在关于页手动查 */
    var autoCheckUpdate by mutableStateOf(true)

    /**
     * 更新来源：正式版读 update.json 内测版读 update-internal.json。
     *
     * 内测包只在稳定后发 版本号跟正式版一样（装完还能装回正式版），
     * 所以内测通道判断有没有新版本看的是构建号 不是版本号。
     */
    var updateChannel by mutableStateOf(UpdateChannel.RELEASE)

    /** 首次启动引导是否走完（走完或跳过都算） */
    var onboarded by mutableStateOf(false)

    /**
     * 上次校验音量键时的系统版本（见 [SystemVersion]：ROM 的 OTA 版本串，如 4.0.24.0.WOCCNXM）
     *
     * 系统升级后 /dev/input/eventN 与按键能力位都可能变 旧的校验结果不能再用，
     * 启动时发现版本对不上就作废模块里的 VOLUME_CHECK_ASSESS 并重新拉起校验面板。
     * 空串 = 没记录过（老用户升级上来）不打扰。
     */
    var volCheckedVersion by mutableStateOf("")

    /**
     * 安全模式：连续崩溃过（多半是液态玻璃这块的绘制炸了）
     * 此时关掉所有玻璃效果 让 App 至少能打开 能进设置
     */
    var safeMode by mutableStateOf(false)

    fun load(context: Context) {
        val p = context.getSharedPreferences("ui_config", Context.MODE_PRIVATE)
        enableBlur = p.getBoolean("enable_blur", true)
        predictiveBack = p.getBoolean("predictive_back", true)
        autoCheckUpdate = p.getBoolean("auto_check_update", true)
        updateChannel = UpdateChannel.entries
            .getOrElse(p.getInt("update_channel", 0)) { UpdateChannel.RELEASE }
        // 老用户升级上来时 ui_config 里已经有键 不再弹一次引导
        // 全新安装时 prefs 是空的 才会走引导
        onboarded = p.getBoolean("onboarded", p.contains("auto_check_update"))
        volCheckedVersion = p.getString("vol_checked_version", "").orEmpty()
        // 上一版只记了 SDK + Android 版本：能对上就按当前 ROM 版本认账，
        // 对不上就拿旧值当指纹（必然 != 当前）-> 升级上来照常触发一次重验
        if (volCheckedVersion.isBlank()) {
            val oldSdk = p.getInt("vol_checked_sdk", -1)
            if (oldSdk >= 0) {
                val sameAndroid = oldSdk == Build.VERSION.SDK_INT &&
                    p.getString("vol_checked_release", "").orEmpty() == Build.VERSION.RELEASE
                volCheckedVersion = if (sameAndroid) {
                    SystemVersion.fingerprint
                } else {
                    "android-$oldSdk"
                }
            }
        }
        safeMode = AppLog.safeMode()
        GlassEffectState.load(context)
    }

    fun save(context: Context) {
        context.getSharedPreferences("ui_config", Context.MODE_PRIVATE).edit().apply {
            putBoolean("enable_blur", enableBlur)
            putBoolean("predictive_back", predictiveBack)
            putBoolean("auto_check_update", autoCheckUpdate)
            putInt("update_channel", updateChannel.ordinal)
            putBoolean("onboarded", onboarded)
            putString("vol_checked_version", volCheckedVersion)
        }.apply()
    }

    /** 系统版本（ROM 的 OTA 版本串）与上次校验时不一致：按键设备号可能全变了 需要重新校验 */
    fun needsVolumeRecheck(): Boolean {
        if (volCheckedVersion.isBlank()) return false
        return volCheckedVersion != SystemVersion.fingerprint
    }

    /** 记下这次校验用的系统版本 之后不再重复弹 */
    fun markVolumeChecked(context: Context) {
        volCheckedVersion = SystemVersion.fingerprint
        save(context)
    }
}

private val NAV_STYLES = listOf("普通悬浮", "液态玻璃")

/** 主题弹窗里的第一组：明暗三档 */
private val THEME_BRIGHTNESS_ITEMS = listOf("跟随系统", "浅色模式", "深色模式")

/**
 * 调色风格 / 色彩规范：与 miuix 示例 App 的 ThemePaletteStyle / ThemeColorSpec 一一对应
 * 顺序必须与枚举声明顺序相同（靠下标索引切换）
 */
private val PALETTE_STYLES = listOf(
    "TonalSpot", "Neutral", "Vibrant", "Expressive", "Rainbow",
    "FruitSalad", "Monochrome", "Fidelity", "Content",
)
private val PALETTE_STYLE_SUMMARIES = listOf(
    "经典色调（默认）",
    "低饱和中性",
    "高饱和鲜艳",
    "夸张表达",
    "彩虹渐变",
    "水果拼盘",
    "单色",
    "忠于原色",
    "跟随内容取色",
)

private val COLOR_SPECS = listOf("Spec2021", "Spec2025")
private val COLOR_SPEC_SUMMARIES = listOf(
    "Material You 2021",
    "Material You 2025（部分风格支持）",
)

@Composable
fun UiSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val listState = rememberLazyListState()

    val backdrop = rememberBlurBackdrop()

    val seedItems = remember { listOf("跟随壁纸") + KeyColors.map { it.first } }
    val mode = ThemeState.mode
    val monet = mode >= ThemeState.MODE_MONET_SYSTEM
    val brightnessIndex = mode % 3

    fun saveMode(value: Int) {
        ThemeState.mode = value
        ThemeState.save(context)
    }

    val select = rememberLiquidSelectState()

    ScrollPage(
        title = "界面",
        onBack = onBack,
        bottomPadding = WindowInsets.navigationBars.asPaddingValues()
            .calculateBottomPadding() + 24.dp,
        overlay = { bd -> LiquidSelectHost(select, bd) },
    ) {
                item { SmallTitle("主题") }
                // 三样都在一张卡里：明暗三档 / 莫奈取色 / 种子色
                item {
                    SectionCard {
                        // 一：明暗三档
                        LiquidSelectPreference(
                            title = "主题模式",
                            items = THEME_BRIGHTNESS_ITEMS,
                            selectedIndex = brightnessIndex,
                            onSelectedIndexChange = {
                                saveMode(
                                    if (monet) ThemeState.MODE_MONET_SYSTEM + it else it,
                                )
                            },
                            state = select,
                        )
                        // 二：莫奈取色
                        SwitchPreference(
                            title = "莫奈取色",
                            checked = monet,
                            onCheckedChange = { on ->
                                saveMode(
                                    if (on) ThemeState.MODE_MONET_SYSTEM + brightnessIndex
                                    else brightnessIndex,
                                )
                            },
                        )
                        // 三：种子色 跟调色风格 / 色彩规范同一张卡 莫奈关掉就一起收起
                        AnimatedVisibility(
                            visible = monet,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically(),
                        ) {
                            Column {
                                LiquidSelectPreference(
                                    title = "种子色",
                                    items = seedItems,
                                    selectedIndex = ThemeState.seedIndex
                                        .coerceIn(0, seedItems.size - 1),
                                    onSelectedIndexChange = {
                                        ThemeState.seedIndex = it
                                        ThemeState.save(context)
                                    },
                                    state = select,
                                )
                                // 调色风格 / 色彩规范只在固定取色时才有意义
                                AnimatedVisibility(
                                    visible = ThemeState.seedIndex > 0,
                                    enter = fadeIn() + expandVertically(),
                                    exit = fadeOut() + shrinkVertically(),
                                ) {
                                    Column {
                                        LiquidSelectPreference(
                                            title = "调色风格",
                                            summary = PALETTE_STYLE_SUMMARIES[
                                                ThemeState.paletteStyle.coerceIn(
                                                    0,
                                                    PALETTE_STYLES.size - 1,
                                                )
                                            ],
                                            items = PALETTE_STYLES,
                                            selectedIndex = ThemeState.paletteStyle.coerceIn(
                                                0,
                                                PALETTE_STYLES.size - 1,
                                            ),
                                            onSelectedIndexChange = {
                                                ThemeState.paletteStyle = it
                                                ThemeState.save(context)
                                            },
                                            state = select,
                                        )
                                        LiquidSelectPreference(
                                            title = "色彩规范",
                                            summary = COLOR_SPEC_SUMMARIES[
                                                ThemeState.colorSpec.coerceIn(
                                                    0,
                                                    COLOR_SPECS.size - 1,
                                                )
                                            ],
                                            items = COLOR_SPECS,
                                            selectedIndex = ThemeState.colorSpec.coerceIn(
                                                0,
                                                COLOR_SPECS.size - 1,
                                            ),
                                            onSelectedIndexChange = {
                                                ThemeState.colorSpec = it
                                                ThemeState.save(context)
                                            },
                                            state = select,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 底栏紧跟主题：都是外观总开关 排在玻璃效果前面
                item { SmallTitle("底栏") }
                item {
                    SectionCard {
                        SwitchPreference(
                            title = "底栏模糊",
                            checked = UiConfigState.enableBlur,
                            onCheckedChange = {
                                UiConfigState.enableBlur = it
                                UiConfigState.save(context)
                            },
                        )
                    }
                }

                item { SmallTitle("玻璃效果") }
                item {
                    SectionCard {
                        SliderInputPreference(
                            title = "模糊强度",
                            value = GlassEffectState.blurScale,
                            onValueChange = {
                                GlassEffectState.blurScale = it
                                GlassEffectState.save(context)
                            },
                            valueRange = 0f..2f,
                            valueText = "${(GlassEffectState.blurScale * 100f).roundToInt()}%",
                            inputRange = 0f..200f,
                            toInput = { it * 100f },
                            fromInput = { it / 100f },
                            inputUnit = "%",
                        )
                        BasicComponent(
                            title = "重置",
                            onClick = {
                                GlassEffectState.blurScale = 1f
                                GlassEffectState.save(context)
                            },
                        )
                    }
                }

                item { SmallTitle("交互") }
                item {
                    SectionCard {
                        SwitchPreference(
                            title = "预测性返回手势",
                            summary = "根据手势预览上一级",
                            checked = UiConfigState.predictiveBack,
                            onCheckedChange = {
                                UiConfigState.predictiveBack = it
                                UiConfigState.save(context)
                            },
                        )
                    }
                }
            }

}
