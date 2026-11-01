package com.xkspeed.HyperMount.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.xkspeed.HyperMount.ui.components.SectionCard
import com.xkspeed.HyperMount.ui.component.glass.ScrollPage
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * 第三方开源依赖清单（二级界面）
 *
 * 从关于页的「第三方许可证」进入 每个库一行 点击跳官网
 * 清单内容对齐 apkeditormiuix 的致谢页写法（框架 → 工具） 并补上本项目实际用到、
 * 但 apkeditor 没有的库：kyant 系列、libsu 以及**移植来源 Sonify（GPL-3.0）**
 *
 * 说明：玻璃组件（顶栏/底栏液态玻璃）移植自 Sonify Sonify 自身为 GPL-3.0 派生作品
 * 故本应用整体以 GPL-3.0 发布（见仓库根目录 LICENSE）
 */
private data class OssLibrary(
    val name: String,
    val version: String,
    val license: String,
    val website: String,
)

@Composable
fun ThirdPartyLicensesPage(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val libraries = listOf(
        OssLibrary(
            name = "Jetpack Compose",
            version = "1.11.x（由 Miuix 传递）",
            license = "Apache-2.0",
            website = "https://developer.android.com/jetpack/androidx/releases/compose",
        ),
        OssLibrary(
            name = "Kotlin Coroutines",
            version = "1.11.0",
            license = "Apache-2.0",
            website = "https://github.com/Kotlin/kotlinx.coroutines",
        ),
        OssLibrary(
            name = "KotlinX Serialization",
            version = "1.7.3",
            license = "Apache-2.0",
            website = "https://github.com/Kotlin/kotlinx.serialization",
        ),
        OssLibrary(
            name = "AndroidX Activity Compose",
            version = "1.13.0",
            license = "Apache-2.0",
            website = "https://developer.android.com/jetpack/androidx/releases/activity",
        ),
        OssLibrary(
            name = "Navigation Event",
            version = "1.1.2",
            license = "Apache-2.0",
            website = "https://developer.android.com/jetpack/androidx/releases/navigationevent",
        ),
        OssLibrary(
            name = "Miuix",
            version = "0.9.4",
            license = "Apache-2.0",
            website = "https://github.com/compose-miuix-ui/miuix",
        ),
        OssLibrary(
            name = "miuix-nav（内嵌导航）",
            version = "0.9.4",
            license = "Apache-2.0",
            website = "https://github.com/compose-miuix-ui/miuix",
        ),
        OssLibrary(
            name = "kyant backdrop",
            version = "1.0.5",
            license = "Apache-2.0",
            website = "https://github.com/Kyant0/backdrop",
        ),
        OssLibrary(
            name = "kyant shapes",
            version = "1.0.0",
            license = "Apache-2.0",
            website = "https://github.com/Kyant0/shapes",
        ),
        OssLibrary(
            name = "libsu",
            version = "6.0.0",
            license = "Apache-2.0",
            website = "https://github.com/topjohnwu/libsu",
        ),
        OssLibrary(
            name = "Sonify（玻璃组件移植来源）",
            version = "—",
            license = "GPL-3.0",
            website = "https://github.com/XiaoZhi0729/Sonify",
        ),
    )

    ScrollPage(
        title = "第三方许可证",
        onBack = onBack,
        bottomPadding = 16.dp,
    ) {
        item { SmallTitle("开源依赖") }
        libraries.forEach { lib ->
            item {
                SectionCard {
                    ArrowPreference(
                        title = lib.name,
                        summary = "${lib.version} · ${lib.license}",
                        onClick = {
                            runCatching { uriHandler.openUri(lib.website) }
                        },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}
