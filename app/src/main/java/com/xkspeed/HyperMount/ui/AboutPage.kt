package com.xkspeed.HyperMount.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.graphics.drawable.toBitmap
import com.xkspeed.HyperMount.R
import com.xkspeed.HyperMount.core.AppVersion
import com.xkspeed.HyperMount.core.Update
import com.xkspeed.HyperMount.core.UpdateChannel
import com.xkspeed.HyperMount.core.UpdateInfo
import com.xkspeed.HyperMount.ui.component.effect.BgEffectBackground
import com.xkspeed.HyperMount.ui.component.glass.ScrollPage
import com.xkspeed.HyperMount.ui.util.isInDarkTheme
import com.xkspeed.HyperMount.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog
import com.xkspeed.HyperMount.ui.util.stableNavigationBarHeight

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
    // 已安装的模块版本：state 还没读回来时调用方会用 module.prop 里的值顶上
    moduleVersion: String,
    latest: UpdateInfo?,
    // 这份清单来自哪个来源：内测包版本号可能跟手上装的一样 卡片上得写清楚
    channel: UpdateChannel = UpdateChannel.RELEASE,
    moduleLatest: UpdateInfo?,
    // 更新卡当前形态：下载与安装全程都在这张卡里原地切换
    appStage: UpdateStage = UpdateStage.Idle,
    moduleStage: UpdateStage = UpdateStage.Idle,
    onInstallApp: () -> Unit,
    /** 静默安装失败后点安装卡：用真实路径把系统安装器叫起来 */
    onOpenInstaller: (String) -> Unit = {},
    onDownloadModule: () -> Unit,
    onOpenThirdPartyLicenses: () -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    // 开发者那一行点开后选平台（GitHub / 酷安）
    var showPlatforms by remember { mutableStateOf(false) }

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
        bottomPadding = stableNavigationBarHeight() + 24.dp,
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

        // ---- 卡片 0：开发者（点开选平台，GitHub / 酷安）----
        item {
            FrostedCard(backdrop = backdrop) {
                ArrowPreference(
                    title = "XKspeed",
                    summary = "开发者",
                    startAction = { DeveloperAvatar(R.drawable.avatar_xkspeed) },
                    onClick = { showPlatforms = true },
                )
                BasicComponent(
                    title = "DeepSeek",
                    summary = "代码与文档协助",
                    startAction = { DeveloperAvatar(R.drawable.avatar_deepseek) },
                )
            }
        }

        // ---- 卡片 1：版本信息（对齐 apkeditor 的「应用版本 / 作者」卡）----
        item {
            FrostedCard(backdrop = backdrop) {
                ArrowPreference(
                    title = "应用版本",
                    // 装的是内测包就标出来：版本号跟正式版一样 不标分不清手上是哪条线
                    summary = "v${AppVersion.name} (${AppVersion.code})" +
                        if (Update.isInternalBuild) " · 内测" else "",
                    onClick = {
                        runCatching {
                            uriHandler.openUri("https://github.com/XKspeed/HyperMount")
                        }
                    },
                )
                BasicComponent(
                    title = "模块版本",
                    summary = when {
                        moduleVersion.isBlank() -> "未读到"
                        moduleLatest != null ->
                            "v$moduleVersion → v${Update.moduleLabel(moduleLatest)}"
                        else -> "v$moduleVersion"
                    },
                )
            }
        }

        // ---- 有更新时的变更说明：点下载后整张卡原地变进度条 下完变安装卡 ----
        if (latest != null) {
            item {
                UpdateCard(
                    backdrop = backdrop,
                    headline = "v${Update.label(latest)}",
                    subline = if (channel == UpdateChannel.INTERNAL) {
                        val built = Update.buildTimeLabel(latest)
                        if (built.isEmpty()) "内测版" else "内测版 · 发布于 $built"
                    } else {
                        null
                    },
                    notes = Update.changelogLines(latest),
                    emptyNote = "作者没有写变更说明",
                    stage = appStage,
                    idleAction = "下载并安装",
                    onAction = onInstallApp,
                    onOpenInstaller = onOpenInstaller,
                    failedAction = "点击重试",
                )
            }
        }

        // ---- 模块有新版：和 App 更新卡同款 但只给 zip 下载 不代装 ----
        if (moduleLatest != null) {
            item {
                UpdateCard(
                    backdrop = backdrop,
                    headline = "模块 v${Update.moduleLabel(moduleLatest)}",
                    subline = null,
                    notes = Update.moduleChangelogLines(moduleLatest),
                    emptyNote = "模块 zip 与 App 同一个 Release 发布 详细说明见 Release 页面",
                    stage = moduleStage,
                    idleAction = "下载模块压缩包",
                    onAction = onDownloadModule,
                    failedAction = "点击重试",
                )
            }
        }

        // ---- 卡片 2：链接与许可证（对齐 apkeditor 的「GPL / 第三方许可证」卡）----
        item {
            FrostedCard(backdrop = backdrop) {
                // 官网放最上面：安装教程 / 常见问题 / 模块说明都在那边
                ArrowPreference(
                    title = "官网",
                    onClick = {
                        runCatching {
                            uriHandler.openUri("https://xkspeed.github.io/HyperMount/")
                        }
                    },
                )
                ArrowPreference(
                    title = "GitHub 仓库",
                    onClick = {
                        runCatching {
                            uriHandler.openUri("https://github.com/XKspeed/HyperMount")
                        }
                    },
                )
                ArrowPreference(
                    title = "GPL v3.0",
                    onClick = {
                        runCatching { uriHandler.openUri("https://www.gnu.org/licenses/gpl-3.0.html") }
                    },
                )
                ArrowPreference(
                    title = "第三方许可证",
                    onClick = onOpenThirdPartyLicenses,
                )
            }
        }
    }

    // 选平台：Miuix 的 WindowDialog（竖屏就是从底部滑出的那个宽对话框）
    WindowDialog(
        show = showPlatforms,
        title = "选择平台",
        maxWidth = 560.dp,
        onDismissRequest = { showPlatforms = false },
    ) {
        val dismissState = LocalDismissState.current
        Column(modifier = Modifier.fillMaxWidth()) {
            PlatformAction("打开 GitHub") {
                dismissState?.invoke()
                runCatching { uriHandler.openUri("https://github.com/XKspeed") }
            }
            PlatformAction("打开酷安") {
                dismissState?.invoke()
                openCoolapk(context)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                text = "取消",
                onClick = { dismissState?.invoke() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.textButtonColors(textColor = colorScheme.error),
            )
        }
    }
}

/**
 * 更新卡片：一张卡走完「更新说明 → 下载进度 → 安装」。
 *
 * 下载时不收起更新说明：只把底部按钮换成进度条，整卡底色同时按比例铺。
 * 下完卡片原地变安装卡，点了直接拉起系统安装器。
 */
@Composable
private fun UpdateCard(
    backdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop?,
    headline: String,
    subline: String?,
    notes: List<String>,
    emptyNote: String,
    stage: UpdateStage,
    idleAction: String,
    onAction: () -> Unit,
    onOpenInstaller: (String) -> Unit = {},
    failedAction: String,
) {
    val shape = RoundedCornerShape(16.dp)

    // 目标填充量：下载中按实时进度，下好一次铺满
    val target = when (stage) {
        is UpdateStage.Downloading -> stage.progress.coerceIn(0f, 1f)
        is UpdateStage.ReadyToInstall,
        is UpdateStage.Saved -> 1f
        else -> 0f
    }
    // 分段回调是跳着来的：补一层线性动画 填充不一顿一顿 状态切换也不会突然跳满
    val fill by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 420, easing = LinearEasing),
        label = "updateFill",
    )

    // 只有「失败」和「已下载」整卡可点，其余状态点了会误触
    val tappable = stage is UpdateStage.Failed || stage is UpdateStage.ReadyToInstall

    FrostedCard(backdrop = backdrop) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .then(
                    if (tappable) {
                        Modifier.clickable {
                            // 已下载：点卡片是把系统安装器再叫起来
                            if (stage is UpdateStage.ReadyToInstall) {
                                onOpenInstaller(stage.path)
                            } else {
                                onAction()
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                MiuixText(
                    "$headline 更新内容",
                    color = colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                if (subline != null) {
                    Spacer(Modifier.height(4.dp))
                    MiuixText(subline, color = colorScheme.primary, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                if (notes.isEmpty()) {
                    MiuixText(
                        emptyNote,
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

                when (stage) {
                    UpdateStage.Idle -> {
                        Button(
                            onClick = onAction,
                            colors = ButtonDefaults.buttonColorsPrimary(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            MiuixText(idleAction)
                        }
                    }

                    // 下载前筛源：还没进度可言 只报探到第几条
                    is UpdateStage.Probing -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(colorScheme.primary.copy(alpha = 0.10f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            MiuixText(
                                "查询镜像中 ${stage.done}/${stage.total}",
                                color = colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                            )
                        }
                    }

                    // 下载中：按钮原地换成进度条 百分比落在条的中心
                    is UpdateStage.Downloading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(colorScheme.primary.copy(alpha = 0.10f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .align(Alignment.CenterStart)
                                    .fillMaxHeight()
                                    .fillMaxWidth(fill)
                                    .background(colorScheme.primary.copy(alpha = 0.38f)),
                            )
                            MiuixText(
                                "${(fill * 100).toInt()}%",
                                color = colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                            )
                        }
                    }

                    // 下好：进度条原地变成安装按钮 点了拉起系统安装器
                    is UpdateStage.ReadyToInstall -> {
                        Button(
                            onClick = { onOpenInstaller(stage.path) },
                            colors = ButtonDefaults.buttonColorsPrimary(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            MiuixText("安装")
                        }
                    }

                    is UpdateStage.Failed -> {
                        Button(
                            onClick = onAction,
                            colors = ButtonDefaults.buttonColorsPrimary(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            MiuixText(failedAction)
                        }
                    }

                    is UpdateStage.Saved -> {
                        Button(
                            onClick = {},
                            enabled = false,
                            colors = ButtonDefaults.buttonColorsPrimary(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            MiuixText("已保存")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 开发者头像：40dp 圆角方图，和 miuix 示例里的头像观感一致
 */
@Composable
private fun DeveloperAvatar(@DrawableRes res: Int) {
    Image(
        painter = painterResource(res),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(40.dp)
            .squircleClip(cornerRadius = 13.dp),
    )
}

/**
 * 对话框里的一行平台入口：铺满宽度、文字居中、底色透明
 * 排布与 miuix 示例 dialog 里的 TextButton 一致
 */
@Composable
private fun PlatformAction(text: String, onClick: () -> Unit) {
    TextButton(
        text = text,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        colors = ButtonDefaults.textButtonColors(
            color = Color.Transparent,
            disabledColor = Color.Transparent,
            textColor = colorScheme.onSurface,
        ),
    )
}

/**
 * 直接拉起酷安 App：coolmarket:// 由酷安本体接管，不经过浏览器
 * 先指名包名 避免系统弹「用哪个应用打开」；没装就退回网页版
 */
private fun openCoolapk(context: Context) {
    val schemeUri = Uri.parse("coolmarket://www.coolapk.com/u/34717033")
    val webUri = Uri.parse("https://www.coolapk.com/u/34717033")

    val named = Intent(Intent.ACTION_VIEW, schemeUri)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .setPackage("com.coolapk.market")
    if (runCatching { context.startActivity(named) }.isSuccess) return

    val anyApp = Intent(Intent.ACTION_VIEW, schemeUri)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(anyApp) }.isSuccess) return

    Toast.makeText(context, "没有安装酷安 先打开网页版", Toast.LENGTH_SHORT).show()
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, webUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
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
            text = "HyperMount",
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
            text = "v${AppVersion.name}",
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
