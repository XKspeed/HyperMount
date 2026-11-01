package com.xkspeed.systemuimount.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.xkspeed.systemuimount.core.AppLog
import java.io.File

/**
 * RESCUE / TARGET 属于「界面」页 直接写进模块
 * MAIN_RESCUE / MAIN_TARGET 属于「主页」 只存本地 两页互不干扰
 */
enum class PickerTarget { RESCUE, TARGET, MAIN_RESCUE, MAIN_TARGET }

private const val APK_MIME = "application/vnd.android.package-archive"

/**
 * 下载器把 apk 存成 apk.1 时系统给不出 apk 的 MIME 只会退回这个
 * 一起塞进 EXTRA_MIME_TYPES 才能让这类文件在选择器里露出来
 */
private const val UNKNOWN_MIME = "application/octet-stream"

/** 系统界面固定包名 选到别的一律不放行 */
const val SYSTEMUI_PACKAGE = "com.android.systemui"

/** 设备上能直接拉起的第三方文件管理器（MT 一类） */
data class PickSource(val label: String, val component: ComponentName)

/** MT 管理器 与官方共存版 */
private const val MT_PACKAGE = "bin.mt.plus"
private const val MT_CANARY_PACKAGE = "bin.mt.plus.canary"

/**
 * 只认 MT 的两个包名 不再枚举所有能处理 GET_CONTENT 的应用
 *
 * 枚举会把装过的别的管理器 甚至相册一类也拉进来 名单不可控
 * 查询入口用通配类型 启动时再换成具体 apk 类型（见 buildSourcePickIntent）
 */
fun queryMtPickers(context: Context): List<PickSource> {
    val pm = context.packageManager
    val out = mutableListOf<PickSource>()
    listOf(MT_PACKAGE, MT_CANARY_PACKAGE).forEach { pkg ->
        val probe = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            setPackage(pkg)
        }
        val info = runCatching { pm.queryIntentActivities(probe, 0).firstOrNull() }
            .getOrNull() ?: return@forEach
        val raw = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
        val label = when {
            raw.isBlank() && pkg == MT_CANARY_PACKAGE -> "MT管理器 共存版"
            raw.isBlank() -> "MT管理器"
            pkg == MT_CANARY_PACKAGE -> "$raw 共存版"
            else -> raw
        }
        out += PickSource(label, ComponentName(pkg, info.activityInfo.name))
    }
    // 排查用：这里返回 0 多半是被包可见性挡了 不是设备上真没装
    AppLog.log("选文件", "MT 来源 ${out.size} 个 " + out.joinToString(" ") { it.label })
    return out
}

/**
 * 用指定组件拉起第三方文件管理器
 *
 * 这里不能给通配类型
 * MT 收到通配类型会当成"任意内容" 直接进到选文件夹模式 底部只剩选择当前文件夹
 * 给具体类型它才按"选文件"处理 列表里只留安装包
 *
 * EXTRA_MIME_TYPES 额外带上未知类型 部分管理器会把它并进过滤条件
 * apk.1 这种没有标准 MIME 的文件才有机会显示出来
 */
fun buildSourcePickIntent(component: ComponentName): Intent =
    Intent(Intent.ACTION_GET_CONTENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = APK_MIME
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(APK_MIME, UNKNOWN_MIME))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        this.component = component
    }

/**
 * 拉起 MT 的文件夹选择器
 *
 * 来源探测与选 apk 完全同一套（queryMtPickers 只认 MT 的两个包名），
 * 不装就直接返回 null，由调用方 toast，不弹任何系统选择器。
 *
 * 必须发 ACTION_OPEN_DOCUMENT_TREE 并显式指定 MT 的 activity：
 * MT 的 manifest 只声明了 GET_CONTENT 加通配 mimeType 这一个 filter，走那条路进的是选文件；
 * 用显式 component 启动不做 filter 匹配，MT 收到 TREE action 才会切到「选择文件夹」。
 * 这两条路的界面完全不同（一个选文件 一个选文件夹），别再换回去。
 */
fun buildFolderPickIntent(context: Context): Intent? {
    val source = queryMtPickers(context).firstOrNull() ?: return null
    return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        component = source.component
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/**
 * 把选中的目录还原成 shell 能用的真实路径
 *
 * MT 在 root 模式下一般直接给 file:// 或 /storage/... 的 path；
 * 系统文档树给的是 primary:Download/xxx 这种 document id 需要手动拼。
 * 拼不出来就返回 null —— 别猜一个路径写进配置 抓日志时会发现根本没写到那。
 */
fun folderPathOf(context: Context, uri: Uri): String? {
    if (uri.scheme.equals("file", true)) return uri.path?.takeIf { it.startsWith("/") }
    // 有的 provider 直接把真实路径塞在 path 里
    uri.path?.let { p ->
        if (p.startsWith("/storage/") || p.startsWith("/data/") || p.startsWith("/mnt/")) return p
    }
    val docId = runCatching {
        if (DocumentsContract.isTreeUri(uri)) DocumentsContract.getTreeDocumentId(uri)
        else DocumentsContract.getDocumentId(uri)
    }.getOrNull()
    docId?.let { externalStoragePath(it) }?.let { return it }
    // 最后一招：provider 自己可能认 _data 列
    return runCatching {
        context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.startsWith("/") }
}

/** document id → 挂载路径：primary:Download/x → /storage/emulated/0/Download/x */
private fun externalStoragePath(docId: String): String? {
    val idx = docId.indexOf(':')
    if (idx <= 0) return null
    val volume = docId.substring(0, idx)
    val rel = docId.substring(idx + 1).trim('/')
    val root = when {
        volume.equals("primary", true) -> "/storage/emulated/0"
        // home 是「文档」入口 对应 Documents 目录
        volume.equals("home", true) -> "/storage/emulated/0/Documents"
        // 外置存储用卷 ID（XXXX-XXXX）
        volume.length >= 4 -> "/storage/$volume"
        else -> return null
    }
    return if (rel.isEmpty()) root else "$root/$rel"
}

/**
 * content:// 脚本读不了 先落成真实文件
 *
 * 放在 App 的外部私有目录 root 同样可读 比 cacheDir 稳
 * 文件名里的空格与路径分隔符一律换成下划线 否则配置里那行会被截断
 * 下载器给 apk.1 这类名字在这里统一还原成 apk 后面就不用再判一次后缀
 */
fun copyPickedFile(context: Context, uri: Uri): File? = runCatching {
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    val dir = File(base, "picked").apply { mkdirs() }
    val raw = queryDisplayName(context, uri) ?: "picked.apk"
    val name = normalizeName(raw)
    val out = File(dir, name)
    val input = context.contentResolver.openInputStream(uri) ?: return@runCatching null
    input.use { src -> out.outputStream().use { src.copyTo(it) } }
    out
}.getOrNull()

/** 空格与分隔符换下划线 apk.1 / apk.2 这类尾巴去掉 只留 .apk */
private fun normalizeName(raw: String): String {
    val cleaned = raw.replace('/', '_').replace('\\', '_').replace(' ', '_')
    val tail = Regex("""\.apk\.\d+$""", RegexOption.IGNORE_CASE)
    return if (tail.containsMatchIn(cleaned)) tail.replace(cleaned, ".apk") else cleaned
}

/** 落地之后文件必须还是安装包 */
fun File.looksLikeApk(): Boolean = name.endsWith(".apk", ignoreCase = true)

/**
 * 读安装包清单里的包名
 *
 * 只解析清单不去安装 拿不到就说明这个文件根本不是合法 apk
 */
fun packageNameOf(context: Context, file: File): String? = runCatching {
    context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.packageName
}.getOrNull()

private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
}.getOrNull()
