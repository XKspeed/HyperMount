package com.xkspeed.HyperMount.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 官方仓库地址：整个提示里只有这一处需要跳转 */
private const val REPO_URL = "https://github.com/XKspeed/HyperMount"

/**
 * 改包重签才会出现的红卡：贴在内容最顶端，跟着列表一起滚。
 * 只有 GitHub 四个字是链接，其余区域不响应点击。
 */
@Composable
fun UnofficialBanner(modifier: Modifier = Modifier) {
    val text = buildAnnotatedString {
        append("非官方版本 请前往")
        withLink(
            LinkAnnotation.Url(
                url = REPO_URL,
                styles = TextLinkStyles(
                    style = SpanStyle(
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = TextDecoration.Underline,
                    ),
                ),
            ),
        ) { append("GitHub") }
        append("下载官方版本")
    }

    Box(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFD93025))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                color = Color.White,
                fontSize = 15.sp,
                lineHeight = 21.sp,
            ),
        )
    }
}
