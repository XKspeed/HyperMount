package com.xkspeed.HyperMount.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * 仓库根目录的 update.json
 *
 * 字段都给了默认值 少写几个也能解析 不至于因为仓库里改格式就崩
 */
@Serializable
data class UpdateInfo(
    val versionCode: Int = 0,
    val versionName: String = "",
    val url: String = "",
    val changelog: String = "",
    // 模块跟着同一个 Release 发 清单里一起带这三项
    // 老清单没有这些字段 默认空 此时不能判定成"模块有更新"
    val moduleVersionCode: Int = 0,
    val moduleVersionName: String = "",
    val moduleUrl: String = "",
    val moduleChangelog: String = "",
)

/**
 * 更新检查
 *
 * 只读仓库里的 update.json 不碰 release API：一份静态文件就够。
 * 直连 GitHub 常常连不上（尤其移动网络），所以按「直连 → 加速镜像 → CDN」的顺序逐条试，
 * 哪条先回就用哪条，并把这条的代理前缀记住：下载链接也套同一个前缀，
 * 免得「能查到更新、点下载却打不开」。
 *
 * 启动时后台查一次 超时就当没更新 不能让网络拖住启动
 */
object Update {

    private const val REPO = "XKspeed/SystemUIRescue"
    private const val BRANCH = "main"

    // ---------------- 清单缓存 ----------------
    // 查到更新之后网络就断了 / 用户重启了应用：之前查到的结果不能凭空消失
    // 所以每次拉到清单都落一份本地副本 启动时先拿它垫上 再后台去查
    private const val PREFS = "update_cache"
    private const val KEY_MANIFEST = "manifest"
    private const val KEY_PROXY = "proxy"

    /** 原始清单（走 GitHub raw） */
    private const val RAW_MANIFEST =
        "https://raw.githubusercontent.com/$REPO/$BRANCH/update.json"

    /**
     * 代理前缀：空串是直连，其余是国内常用的 GitHub 加速站
     * 这些站同时转发 raw 与 releases/download，下载链接能直接套
     */
    private val PROXIES = listOf(
        "",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://ghfast.top/",
        "https://github.moeyy.xyz/",
    )

    /** 不走代理也能拿到的镜像/CDN：直连被墙时这几条往往还活着（只用于清单，不能转发下载） */
    private val MIRRORS = listOf(
        "https://cdn.jsdelivr.net/gh/$REPO@$BRANCH/update.json",
        "https://raw.gitmirror.com/$REPO/$BRANCH/update.json",
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 本次启动扫通的代理前缀（空串=直连，null=还没查过） */
    @Volatile
    private var liveProxy: String? = null

    private var appContext: Context? = null

    /**
     * 装上下文：清单缓存要用它。
     *
     * 顺带把上次扫通的代理前缀恢复回来 —— 缓存里的下载链接也走它，
     * 否则重启后「能查到更新、点下载却打不开」又会重演一次。
     */
    fun install(context: Context) {
        appContext = context.applicationContext
        liveProxy = prefs()?.getString(KEY_PROXY, null)
    }

    /**
     * 上次成功拉到的清单（没有就返回 null）。
     *
     * 这里只负责把缓存原样给出来 判不判定有更新交给调用方拿当前版本比：
     * 用户装上更新后 旧缓存里的版本号自然不再高于本地 提示会自己消失。
     */
    fun cached(): UpdateInfo? {
        val raw = prefs()?.getString(KEY_MANIFEST, null) ?: return null
        return runCatching { json.decodeFromString<UpdateInfo>(raw) }
            .getOrNull()
            ?.takeIf { it.versionCode > 0 || it.versionName.isNotBlank() }
    }

    /**
     * 拉到清单就立刻落盘 失败也不影响本次结果。
     *
     * 缓存里那份比这次拉到的还新（镜像没刷新）时不动缓存 返回 false 由调用方决定用哪份。
     */
    private fun save(info: UpdateInfo): Boolean {
        val p = prefs() ?: return false
        // 不同镜像的新鲜度不一样 偶尔会拿到比本地缓存还旧的副本（CDN 没刷新）
        // 这种不覆盖：否则刚查到的更新会被一份旧清单抹掉
        if ((cached()?.versionCode ?: 0) > info.versionCode) return false
        return runCatching {
            p.edit()
                .putString(KEY_MANIFEST, json.encodeToString(UpdateInfo.serializer(), info))
                .putString(KEY_PROXY, liveProxy.orEmpty())
                .apply()
        }.isSuccess
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // 本地版本走 PackageManager：用 MT 改过 AndroidManifest 的包装上来 这里读到的就是改后的值
    val currentCode: Int get() = AppVersion.code.toInt()
    val currentName: String get() = AppVersion.name

    /** 超时返回 null 与「没有更新」区分开：调用方可以区分"没查到"和"已最新" */
    suspend fun check(timeoutMs: Long = 15_000): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching { fetch(timeoutMs) }.getOrNull()
    }

    fun isNewer(info: UpdateInfo?): Boolean = info != null && info.versionCode > currentCode

    /** 变更按行拆开 空行丢掉 列表里好排版 */
    fun changelogLines(info: UpdateInfo): List<String> =
        info.changelog.lines()
            .map { it.trim().removePrefix("-").trim() }
            .filter { it.isNotEmpty() }

    /** 显示用的版本号：清单里没写 name 就退回 code */
    fun label(info: UpdateInfo): String =
        info.versionName.ifBlank { info.versionCode.toString() }

    // ---------------- 模块 ----------------
    // 模块和应用同仓库、同 Release、同一份清单 判定逻辑也照上面那套搬

    /** 清单里带不带模块信息：老清单没有这一项 不能凭空提示"模块更新" */
    fun hasModule(info: UpdateInfo?): Boolean =
        info != null && (info.moduleVersionCode > 0 || info.moduleVersionName.isNotBlank())

    /**
     * 模块有没有新版。
     *
     * 优先比 versionCode（模块 versionCode 会随发版自动 +1）；
     * 清单里只写了名字时才退回版本名比较，比如 1.5.4 → 1.5.10。
     */
    fun isModuleNewer(info: UpdateInfo?, localCode: Int, localName: String): Boolean {
        if (!hasModule(info) || info == null) return false
        if (info.moduleVersionCode > 0 && localCode > 0) return info.moduleVersionCode > localCode
        return compareVersion(info.moduleVersionName, localName) > 0
    }

    /** 模块版本号显示：没写 name 就退回 code */
    fun moduleLabel(info: UpdateInfo): String =
        info.moduleVersionName.ifBlank { info.moduleVersionCode.toString() }

    /**
     * 模块 zip 的下载地址：同样跟着本次扫通的镜像走。
     * 老清单没给 moduleUrl 就退回 Release 页（不只是给死链）。
     */
    fun moduleDownloadUrl(info: UpdateInfo?): String {
        val raw = info?.moduleUrl.orEmpty().ifBlank { "https://github.com/$REPO/releases" }
        return downloadUrl(raw)
    }

    /** 清单里有没有模块 zip 的直链：没有时只能打开 Release 页 文案要区分 */
    fun hasModuleFile(info: UpdateInfo?): Boolean = !info?.moduleUrl.isNullOrBlank()

    /** 模块更新说明按行拆开 与 App 的 changelog 同样处理 */
    fun moduleChangelogLines(info: UpdateInfo): List<String> =
        info.moduleChangelog.lines()
            .map { it.trim().removePrefix("-").trim() }
            .filter { it.isNotEmpty() }

    /**
     * 版本名比较：1.5.4 < 1.5.10（按数字段比，不是字符串比）。
     * 解析不出数字时退回字符串比较。
     */
    fun compareVersion(a: String, b: String): Int {
        val x = numbers(a)
        val y = numbers(b)
        if (x.isEmpty() && y.isEmpty()) return a.compareTo(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val l = x.getOrElse(i) { 0 }
            val r = y.getOrElse(i) { 0 }
            if (l != r) return if (l > r) 1 else -1
        }
        return 0
    }

    private fun numbers(s: String): List<Int> =
        Regex("""\d+""").findAll(s).map { it.value.toIntOrNull() ?: 0 }.toList()

    /** 仓库地址的 releases 页（清单里没给下载地址时的兜底） */
    fun releasesPage(): String = downloadUrl("")

    /**
     * 给下载地址套上本次扫通的代理。
     * GitHub 直连能通就不加前缀；通了镜像才查到的更新 这里同样走镜像。
     */
    fun downloadUrl(raw: String): String {
        val url = raw.trim().ifEmpty { "https://github.com/$REPO/releases" }
        val proxy = liveProxy.orEmpty()
        if (proxy.isEmpty()) return url
        // 已经是代理/镜像地址就不重复加前缀
        if (PROXIES.any { it.isNotEmpty() && url.startsWith(it) }) return url
        return proxy + url
    }

    private suspend fun fetch(timeoutMs: Long): UpdateInfo? = withContext(Dispatchers.IO) {
        val candidates = buildList {
            PROXIES.forEach { proxy -> add(proxy to proxy + RAW_MANIFEST) }
            MIRRORS.forEach { add("" to it) }
        }.let { list ->
            // 本次已经扫通过一条 就把它排到最前：手动重查时不用再从死链开始试
            val known = liveProxy ?: return@let list
            list.sortedByDescending { if (it.first == known) 1 else 0 }
        }
        // 阻塞式 HttpURLConnection 不响应协程取消，超时只能自己在每条之前掐：
        // 预算用完就停，免得一串死链把启动后的后台任务拖到半分钟
        val deadline = System.currentTimeMillis() + timeoutMs
        for ((proxy, url) in candidates) {
            if (System.currentTimeMillis() >= deadline) break
            val info = fetchOne(url) ?: continue
            liveProxy = proxy
            // 拿到就落盘：之后断网 / 重启 这次的结果都还在
            // 写入被拒说明缓存里那份更新（镜像没刷新）那就用它 不让更新提示倒退
            val result = if (save(info)) info else cached() ?: info
            AppLog.log(
                "更新",
                "命中镜像 ${proxy.ifEmpty { "直连/CDN" }} 远端 ${result.versionName}(${result.versionCode}) " +
                    "本地 ${currentName}($currentCode)",
            )
            return@withContext result
        }
        AppLog.log("更新", "所有镜像都没拿到 update.json")
        null
    }

    /** 单条地址拉一次：解析失败（被劫持成 HTML 之类）也算失败 继续试下一条 */
    private fun fetchOne(url: String): UpdateInfo? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            // 每条只给 3 秒：挂了就赶紧换下一条，别让一条死链吃掉整个预算
            connectTimeout = 3_000
            readTimeout = 3_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            // 走 CDN 不能吃到旧清单 否则刚发的版本永远查不到
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            // 代理挂了常返回一段 HTML，decode 会抛 正好当失败处理
            val info = json.decodeFromString<UpdateInfo>(text)
            if (info.versionCode <= 0 && info.versionName.isBlank()) return@runCatching null
            info
        } finally {
            runCatching { conn.disconnect() }
        }
    }.getOrNull()
}
