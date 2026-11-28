package com.xkspeed.HyperMount.core

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.xkspeed.HyperMount.i18n.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一份界面 / 组件快照
 *
 * 文件本身放在下载目录（用户看得见、能直接拷走），这里只记元数据。
 * 卸载 App 后文件还在，只是列表里看不到了 —— 比藏进私有目录更不容易丢。
 */
data class Snapshot(
    val id: String,
    /** 显示名 可改；改的时候文件名也跟着改 */
    val name: String,
    /** [Snapshots.KIND_SYSTEMUI] / [Snapshots.KIND_PLUGIN] */
    val kind: String,
    val pkg: String,
    val version: String,
    val size: Long,
    val time: Long,
    val path: String,
    /** 下载目录里找不到这份文件（用户自己删了 / 换了存储） */
    val missing: Boolean = false,
) {
    val exists: Boolean get() = !missing
    val kindLabel: String
        get() = if (kind == Snapshots.KIND_PLUGIN) t("组件", "Component") else t("界面", "Interface")
    val timeLabel: String
        get() = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))
    val sizeLabel: String
        get() = when {
            size >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", size / 1024f / 1024f)
            size > 0L -> "${size / 1024} KB"
            else -> "—"
        }
}

/**
 * 快照仓库
 *
 * 抓取：`pm path` 找到设备上正在生效的那份 apk，root 直接复制到下载目录。
 * 界面是模块挂载点（被模板替换过就是替换后的内容），组件是 /data 更新层或预装版，
 * 抓哪份完全由 pm 自己决定，不用我们猜路径。
 */
object Snapshots {

    const val KIND_SYSTEMUI = "systemui"
    const val KIND_PLUGIN = "plugin"

    /** 默认落在下载文件夹 用户自己也能看到、能拷走 */
    const val DIR = "/storage/emulated/0/Download/HyperMount快照"

    private const val META = "snapshots.json"

    /** 列表状态：两个入口（界面 / 组件）共用一个仓库，各按 kind 过滤 */
    var items by mutableStateOf<List<Snapshot>>(emptyList())
        private set

    private fun metaFile(context: Context) = File(context.filesDir, META)

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        // 有「所有文件访问」就直接列目录；没给权限才退回 root 列一次
        val present = if (storageOk()) {
            File(DIR).listFiles()
                ?.filter { it.isFile }
                ?.map { it.absolutePath }
                ?.toSet()
                .orEmpty()
        } else {
            Root.exec("ls -1 ${Root.q(DIR)} 2>/dev/null")
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.endsWith(":") }
                .map { "$DIR/$it" }
                .toSet()
        }

        items = runCatching {
            val f = metaFile(context)
            if (!f.isFile) return@runCatching emptyList()
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Snapshot(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    kind = o.optString("kind", KIND_SYSTEMUI),
                    pkg = o.optString("pkg"),
                    version = o.optString("version"),
                    size = o.optLong("size"),
                    time = o.optLong("time"),
                    path = o.optString("path"),
                )
            }.filter { it.id.isNotBlank() && it.path.isNotBlank() }
        }.getOrDefault(emptyList())
            // 一条都没列出来多半是 root 还没就绪 别把整页标成「文件已丢失」
            .map { if (present.isEmpty()) it else it.copy(missing = it.path !in present) }
            .sortedByDescending { it.time }
    }

    private fun persist(context: Context) {
        runCatching {
            val arr = JSONArray()
            items.forEach { s ->
                arr.put(JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("kind", s.kind)
                    put("pkg", s.pkg)
                    put("version", s.version)
                    put("size", s.size)
                    put("time", s.time)
                    put("path", s.path)
                })
            }
            metaFile(context).writeText(arr.toString())
        }
    }

    /** 某个来源的快照 新的在上 */
    fun of(kind: String): List<Snapshot> = items.filter { it.kind == kind }

    /**
     * 记录设备上正在生效的那份
     *
     * @return null 成功，否则是一句能直接显示的原因
     */
    suspend fun capture(context: Context, kind: String): String? = withContext(Dispatchers.IO) {
        val pkg = if (kind == KIND_PLUGIN) Logcat.PLUGIN_PKG else Logcat.DEFAULT_PKG
        val prefix = if (kind == KIND_PLUGIN) "组件" else "界面"

        // pm path 给的是当前真正被加载的那条路径：模块挂载生效时就是挂载点
        val src = Root.exec("pm path $pkg")
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("package:") }
            ?.removePrefix("package:")
            ?.takeIf { it.isNotBlank() }
            ?: return@withContext t("设备上没有安装 $pkg", "$pkg is not installed")

        val version = Root.exec("dumpsys package $pkg 2>/dev/null | grep -m1 versionName")
            .substringAfter("versionName=", "")
            .trim()
            .ifBlank { "unknown" }
        val safeVer = version.replace(Regex("[^0-9A-Za-z._-]"), "_")

        val ts = Root.exec("date +%Y%m%d-%H%M%S")
            .trim()
            .ifBlank { System.currentTimeMillis().toString() }
        val fileName = "$prefix-$safeVer-$ts.apk"
        val dst = "$DIR/$fileName"

        Root.exec("mkdir -p ${Root.q(DIR)}")
        val out = Root.exec(
            "cp -f ${Root.q(src)} ${Root.q(dst)} 2>&1; " +
                "stat -c%s ${Root.q(dst)} 2>/dev/null",
        )
        val size = out.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.toLongOrNull() != null }
            ?.toLongOrNull() ?: 0L
        if (size <= 0L) {
            return@withContext t("复制失败 检查存储空间", "Copy failed — check free space")
        }

        val now = System.currentTimeMillis()
        val snap = Snapshot(
            id = "$now-${(1000..9999).random()}",
            name = "$prefix $version",
            kind = kind,
            pkg = pkg,
            version = version,
            size = size,
            time = now,
            path = dst,
        )
        items = (listOf(snap) + items).sortedByDescending { it.time }
        persist(context)
        AppLog.i("快照", "已记录 $prefix $version ($size 字节) → $dst")
        null
    }

    /** 重命名：显示名和文件名一起改 重名自动加序号 */
    suspend fun rename(context: Context, snap: Snapshot, raw: String): String? = withContext(Dispatchers.IO) {
        val name = raw.trim().replace('/', '_').replace('\\', '_').trim()
        if (name.isBlank()) return@withContext t("名字不能为空", "Name can't be empty")

        var candidate = name
        var dst = "$DIR/$candidate.apk"
        var n = 2
        // 文件可能被用户删掉 用 root 探一下再决定要不要加序号
        while (dst != snap.path && exists(dst)) {
            candidate = "$name ($n)"
            dst = "$DIR/$candidate.apk"
            n++
        }
        Root.exec("mv -f ${Root.q(snap.path)} ${Root.q(dst)} 2>&1")
        if (!exists(dst)) return@withContext t("改名失败 文件不在了", "Rename failed — file is gone")

        val edited = snap.copy(name = candidate, path = dst, missing = false)
        items = items.map { if (it.id == snap.id) edited else it }
        persist(context)
        null
    }

    suspend fun remove(context: Context, snap: Snapshot): String? = withContext(Dispatchers.IO) {
        Root.exec("rm -f ${Root.q(snap.path)}")
        items = items.filterNot { it.id == snap.id }
        persist(context)
        null
    }

    /**
     * 设为救砖包
     *
     * 界面：复制到模块根 MiuiSystemUI.apk（模块守护和 action.sh 只认这个名字）
     * 组件：只记路径，崩溃时脚本优先装它；同时把自动恢复打开
     */
    suspend fun setAsRescue(context: Context, snap: Snapshot): String? = withContext(Dispatchers.IO) {
        if (!exists(snap.path)) return@withContext t("文件已不在原位置", "The file is no longer there")
        if (snap.kind == KIND_PLUGIN) {
            LogConfig.componentRescue = snap.path
            LogConfig.autoReplace = true
            LogConfig.save(context)
            null
        } else {
            val out = Module.prepare(snap.path)
            if (out.lineSequence().any { it.trim() == "OK" }) {
                AppLog.i("快照", "已设为界面救砖 ${snap.path}")
                // 界面页要重新读一次模块根的救援文件大小
                ConfigBus.bump()
                null
            } else {
                t("复制到模块失败：$out", "Copy to module failed: $out")
            }
        }
    }

    /** 走系统分享面板导出：用户自己挑发到哪、存到哪 */
    suspend fun share(context: Context, snap: Snapshot): String? = withContext(Dispatchers.IO) {
        if (!exists(snap.path)) return@withContext t("文件已不在原位置", "The file is no longer there")

        // 有所有文件权限就直接分享原件；没有才在私有缓存里放一份
        val tmp = if (storageOk()) {
            File(snap.path)
        } else {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            File(dir, File(snap.path).name).also { out ->
                out.delete()
                Root.exec(
                    "cp -f ${Root.q(snap.path)} ${Root.q(out.absolutePath)} && " +
                        "chmod 666 ${Root.q(out.absolutePath)} 2>/dev/null",
                )
            }
        }
        if (!tmp.isFile || tmp.length() <= 0L) {
            return@withContext t("导出失败 复制不出来", "Export failed — could not copy")
        }
        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                tmp,
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, snap.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(send, t("导出快照", "Export snapshot"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.getOrElse {
            return@withContext t("没有能接收安装包的应用", "No app can receive the apk")
        }
        null
    }

    /** 有「所有文件访问」就不用再为一条存在性判断开一次 root */
    fun storageOk(): Boolean = runCatching {
        android.os.Environment.isExternalStorageManager()
    }.getOrDefault(false)

    private fun exists(path: String): Boolean =
        if (storageOk()) File(path).isFile
        else Root.exec("[ -f ${Root.q(path)} ] && echo 1 || echo 0").trim() == "1"
}
