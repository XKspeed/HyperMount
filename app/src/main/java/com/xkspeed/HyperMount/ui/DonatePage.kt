package com.xkspeed.HyperMount.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xkspeed.HyperMount.R
import com.xkspeed.HyperMount.ui.component.effect.BgEffectBackground
import com.xkspeed.HyperMount.ui.component.glass.ScrollPage
import com.xkspeed.HyperMount.ui.util.isInDarkTheme
import com.xkspeed.HyperMount.ui.util.stableNavigationBarHeight
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 向作者捐赠（二级页）
 *
 * 收款码分两层叠出来：底下是二维码本体，上面盖一张固定的头像 + 点赞图标。
 * 二维码只做了黑白两色（透明底），深色模式换白点阵那张；上面那层不变。
 */
@Composable
fun DonatePage(onBack: () -> Unit) {
    val dark = isInDarkTheme()

    ScrollPage(
        title = "向作者捐赠",
        onBack = onBack,
        // 二级页：只让开导航栏
        bottomPadding = stableNavigationBarHeight() + 24.dp,
        backgroundContent = {
            BgEffectBackground(
                dynamicBackground = true,
                isFullSize = true,
            ) {
                // 纯背景层 无内容
            }
        },
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(modifier = Modifier.size(240.dp)) {
                    // 底层：二维码本体，只有点阵 中间是空的
                    Image(
                        painter = painterResource(
                            if (dark) R.drawable.donate_qr_dark else R.drawable.donate_qr_light,
                        ),
                        contentDescription = "捐赠二维码",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // 上层：固定的头像与右下角点赞图标 与二维码同尺寸 直接对齐
                    Image(
                        painter = painterResource(R.drawable.donate_avatar),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "觉得做的不错的 可以支持下\n" +
                        "你的支持就是我开发最大的动力\n" +
                        "捐赠不会带来任何特殊权益\n" +
                        "本应用永久免费使用",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 14.sp,
                    lineHeight = 24.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
