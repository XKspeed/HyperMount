package com.xkspeed.systemuimount.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.graphics.drawable.toBitmap
import com.xkspeed.systemuimount.BuildConfig
import com.xkspeed.systemuimount.core.ModuleState
import com.xkspeed.systemuimount.core.Update
import com.xkspeed.systemuimount.core.UpdateInfo
import com.xkspeed.systemuimount.ui.component.effect.BgEffectBackground
import com.xkspeed.systemuimount.ui.component.glass.ScrollPage
import com.xkspeed.systemuimount.ui.util.isInDarkTheme
import com.xkspeed.systemuimount.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 关于页（设置页最下面的入口 点进来的二级页）
 *
 * 整体从 apkeditor(miuix) 的 AboutPage 直接移植：
 * - 背景 [BgEffectBackground]（原版 AGSL 流光） 并把自己录进 [rememberBlurBackdrop]
 *   返回的采样层 磨砂卡片 / 应用名都从这里取色；
 * - Logo 区浮动思路一致：图标 + 应用名（流光染色）+ 版本号 随滚动分层淡出缩小；
 * - 内容为两张磨砂卡（textureBlur 采样背景流光） 不分组标题
 *
 * 二级页：左上角有返回键；依赖清单再往下走一层 [onOpenThirdPartyLicenses]
 */
@Composable
fun AboutPage(
    state: ModuleState,
    latest: UpdateInfo?,
    onOpenRelease: () -> Unit,
    onOpenThirdPartyLicenses: () -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()
    val uriHandler = LocalUriHandler.current

    // 前 600px 的滚动量当作淡出进度；再往上就完全收起
    val scrollProgress by remember {
        derivedStateOf {
            when {
                listState.firstVisibleItemIndex > 0 -> 1f
                else -> (listState.firstVisibleItemScrollOffset / 600f).coerceIn(0f, 1f)
            }
        }
    }

    val backdrop = rememberBlurBackdrop()

    ScrollPage(
        title = "关于",
        onBack = onBack,
        // 二级页：只让开导航栏
        bottomPadding = WindowInsets.navigationBars.asPaddingValues()
            .calculateBottomPadding() + 24.dp,
        // 背景流光：apkeditor 原版 BgEffectBackground 并录进 backdrop 供卡片/标题采样
        backgroundContent = {
            BgEffectBackground(
                dynamicBackground = true,
                isFullSize = true,
                bgModifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier,
                alpha = { 1f - scrollProgress },
            ) {
                // 纯背景层 无内容
            }
        },
    ) {
        // ---- 图标 + 名字 + 版本 ----
        item {
            AboutLogo(
                scrollProgressProvider = { scrollProgress },
                backdrop = backdrop,
            )
        }

        // ---- 卡片 1：版本信息（对齐 apkeditor 的「应用版本 / 作者」卡）----
        item {
            FrostedCard(backdrop = backdrop) {
                ArrowPreference(
                    title = "应用版本",
                    summary = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    onClick = {
                        runCatching {
                            uriHandler.openUri("https://github.com/XKspeed/SystemUIRescue")
                        }
                    },
                )
                BasicComponent(
                    title = "模块版本",
                    summary = if (state.version.isNotBlank()) "v${state.version}" else "未读到",
                )
            }
        }

        // ---- 有更新时的变更说明 ----
        if (latest != null) {
            item {
                FrostedCard(backdrop = backdrop) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        MiuixText(
                            "v${Update.label(latest)} 更新内容",
                            color = colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        val notes = Update.changelogLines(latest)
                        if (notes.isEmpty()) {
                            MiuixText(
                                "作者没有写变更说明",
                                color = colorScheme.onSurfaceVariantSummary,
                                fontSize = 13.sp,
                            )
                        } else {
                            notes.forEach { line ->
                                MiuixText(
                                    "· $line",
                                    color = colorScheme.onSurfaceVariantSummary,
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                    modifier = Modifier.padding(bottom = 3.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onOpenRelease,
                            colors = ButtonDefaults.buttonColorsPrimary(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            MiuixText("前往下载")
                        }
                    }
                }
            }
        }

        // ---- 卡片 2：链接与许可证（对齐 apkeditor 的「GPL / 第三方许可证」卡）----
        item {
            FrostedCard(backdrop = backdrop) {
                ArrowPreference(
                    title = "GitHub 仓库",
                    summary = "github.com/XKspeed/SystemUIRescue",
                    onClick = {
                        runCatching {
                            uriHandler.openUri("https://github.com/XKspeed/SystemUIRescue")
                        }
                    },
                )
                ArrowPreference(
                    title = "GNU General Public License v3.0",
                    summary = "开源许可证",
                    onClick = {
                        runCatching { uriHandler.openUri("https://www.gnu.org/licenses/gpl-3.0.html") }
                    },
                )
                ArrowPreference(
                    title = "第三方许可证",
                    summary = "依赖库版权声明",
                    onClick = onOpenThirdPartyLicenses,
                )
            }
        }
    }
}

/**
 * Logo 区：图标 + 应用名 + 版本号
 *
 * 应用名用 [textureBlur]（contentBlendMode = DstIn）从背景采样流光染色
 * 就是 apkeditor 里"ApkEditor·Miuix" 那种随背景流动的彩色字
 */
@Composable
private fun AboutLogo(
    scrollProgressProvider: () -> Float,
    backdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop?,
) {
    val isInDark = isInDarkTheme()
    // 应用名的流光染色配色 照抄 apkeditor 的 logoBlend
    val logoBlend = remember(isInDark) {
        if (isInDark) {
            listOf(
                BlendColorEntry(Color(0xe6a1a1a1), BlurBlendMode.ColorDodge),
                BlendColorEntry(Color(0x4de6e6e6), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af500), BlurBlendMode.Lab),
            )
        } else {
            listOf(
                BlendColorEntry(Color(0xcc4a4a4a), BlurBlendMode.ColorBurn),
                BlendColorEntry(Color(0xff4f4f4f), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af200), BlurBlendMode.Lab),
            )
        }
    }

    val density = LocalDensity.current
    val ctx = LocalContext.current
    // 直接读取应用启动图标 保证与桌面图标始终一致
    val appIcon = remember(ctx, density) {
        val iconSizePx = with(density) { 100.dp.roundToPx() }
        ctx.packageManager.getApplicationIcon(ctx.applicationInfo)
            .toBitmap(iconSizePx, iconSizePx)
            .asImageBitmap()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 40.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val iconProgress = ((scrollProgressProvider() - 0.35f) / 0.15f).coerceIn(0f, 1f)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(100.dp)
                .graphicsLayer {
                    clip = true
                    alpha = 1 - iconProgress
                    scaleX = 1 - (iconProgress * 0.05f)
                    scaleY = 1 - (iconProgress * 0.05f)
                }
                .squircleClip(cornerRadius = 28.dp),
        ) {
            Image(
                modifier = Modifier.size(100.dp),
                bitmap = appIcon,
                contentDescription = null,
            )
        }
        MiuixText(
            modifier = Modifier
                .padding(top = 12.dp, bottom = 5.dp)
                .graphicsLayer {
                    val nameProgress = ((scrollProgressProvider() - 0.20f) / 0.15f).coerceIn(0f, 1f)
                    alpha = 1 - nameProgress
                    scaleX = 1 - (nameProgress * 0.05f)
                    scaleY = 1 - (nameProgress * 0.05f)
                }
                .then(
                    if (backdrop != null) {
                        Modifier.textureBlur(
                            backdrop = backdrop,
                            shape = RoundedCornerShape(16.dp),
                            blurRadius = 150f,
                            noiseCoefficient = BlurDefaults.NoiseCoefficient,
                            colors = BlurDefaults.blurColors(blendColors = logoBlend),
                            contentBlendMode = BlendMode.DstIn,
                        )
                    } else {
                        Modifier
                    },
                ),
            text = "SystemUI救援",
            color = colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            fontSize = 35.sp,
        )
        MiuixText(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val versionProgress = ((scrollProgressProvider() - 0.05f) / 0.15f).coerceIn(0f, 1f)
                    alpha = 1 - versionProgress
                    scaleX = 1 - (versionProgress * 0.05f)
                    scaleY = 1 - (versionProgress * 0.05f)
                },
            color = colorScheme.onSurfaceVariantSummary,
            text = "v${BuildConfig.VERSION_NAME}",
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 磨砂玻璃卡片：容器填充透明 + [textureBlur] 采样背后流光 参数与 apkeditor
 * 的卡片完全一致（16dp 圆角 / 60f 模糊 / ColorBlendToken 混色）
 * 不支持 runtime shader 时退回实心底色 避免卡片变透明看不见字
 */
@Composable
private fun FrostedCard(
    backdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val isInDark = isInDarkTheme()
    val cardBlend = if (isInDark) {
        listOf(
            BlendColorEntry(Color(0x4DA9A9A9), BlurBlendMode.Luminosity),
            BlendColorEntry(Color(0x1A9C9C9C), BlurBlendMode.PlusDarker),
        )
    } else {
        listOf(
            BlendColorEntry(Color(0x340034F9), BlurBlendMode.Overlay),
            BlendColorEntry(Color(0xB3FFFFFF), BlurBlendMode.HardLight),
        )
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp)
            .then(
                if (backdrop != null) {
                    Modifier.textureBlur(
                        backdrop = backdrop,
                        shape = RoundedCornerShape(16.dp),
                        blurRadius = 60f,
                        noiseCoefficient = BlurDefaults.NoiseCoefficient,
                        colors = BlurDefaults.blurColors(blendColors = cardBlend),
                    )
                } else {
                    Modifier
                },
            ),
        colors = CardDefaults.defaultColors(
            if (backdrop != null) Color.Transparent else colorScheme.surfaceContainer,
            Color.Transparent,
        ),
        content = content,
    )
}
