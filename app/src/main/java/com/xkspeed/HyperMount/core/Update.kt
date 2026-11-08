package com.xkspeed.HyperMount.core

import android.content.Context
import com.xkspeed.HyperMount.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 更新来源。
 *
 * 正式版读仓库根的 update.json（发版工作流维护 一个版本一份）；
 * 内测版读 update-internal.json（内测工作流维护 每次构建覆盖同一个 tag）。
 * 两份清单分开存 切换来源不会互相覆盖。
 */
enum class UpdateChannel(val label: String) {
    RELEASE("正式版"),
    INTERNAL("内测版"),
}

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
    // 构建时间戳（epoch 毫秒）：打包时写进 BuildConfig.BUILD_TIME，清单里带同一个值
    // 内测包和正式版共用 versionCode 光看版本号分不出谁新 只能看谁先构建出来
    val buildTime: Long = 0,
    // 老字段：内测构建号（CI 的 run number）
    // 时间戳上线前的清单只有它 留着给老清单兜底
    val buildId: String = "",
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

    private const val REPO = "XKspeed/HyperMount"
    private const val BRANCH = "main"

    // GitHub API：镜像站集体挂掉（raw 被墙、加速站 504、下载入口回源超时）时 这条路通常还活着
    // 手动检查更新时排在所有镜像前面，避免"镜像一炸就查不到更新"
    private const val API = "https://api.github.com/repos/$REPO"
    private const val API_RAW = "application/vnd.github.raw"
    // 标记这条候选来自 API：它的 host 跟 raw 不是一回事 成功不代表 raw 直连通
    // 所以不能拿它去覆盖 liveProxy（否则下载链接会退成直连）
    private const val API_MARK = "api"

    // 内测通道补模块信息时额外给正式清单的预算：模块只跟 Release 发 内测清单里没有
    // 拿不到就算了（还有缓存兜着） 不能让这一次附带请求把整个检查拖长
    private const val MODULE_BUDGET_MS = 8_000L

    // ---------------- 清单缓存 ----------------
    // 查到更新之后网络就断了 / 用户重启了应用：之前查到的结果不能凭空消失
    // 所以每次拉到清单都落一份本地副本 启动时先拿它垫上 再后台去查
    private const val PREFS = "update_cache"
    // 两个来源各存各的：切来切去时不至于把另一条的缓存覆盖掉
    private const val KEY_MANIFEST = "manifest" // 老版本只存过正式版 读到了就当成正式版的
    private const val KEY_MANIFEST_RELEASE = "manifest_release"
    private const val KEY_MANIFEST_INTERNAL = "manifest_internal"
    private const val KEY_PROXY = "proxy"

    private fun manifestKey(channel: UpdateChannel) =
        if (channel == UpdateChannel.INTERNAL) KEY_MANIFEST_INTERNAL else KEY_MANIFEST_RELEASE

    /** 清单文件名：正式版 update.json 内测版 update-internal.json */
    private fun manifestFile(channel: UpdateChannel) =
        if (channel == UpdateChannel.INTERNAL) "update-internal.json" else "update.json"

    /** 原始清单（走 GitHub raw） */
    private fun rawManifest(channel: UpdateChannel) =
        "https://raw.githubusercontent.com/$REPO/$BRANCH/${manifestFile(channel)}"

    /** 同一份清单走 GitHub API（返回文件原文 不是 base64 包装） */
    private fun apiManifest(channel: UpdateChannel) =
        "$API/contents/${manifestFile(channel)}?ref=$BRANCH"

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
    private fun mirrors(channel: UpdateChannel) = listOf(
        "https://cdn.jsdelivr.net/gh/$REPO@$BRANCH/${manifestFile(channel)}",
        "https://raw.gitmirror.com/$REPO/$BRANCH/${manifestFile(channel)}",
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 本次启动扫通的代理前缀（空串=直连，null=还没查过） */
    @Volatile
    private var liveProxy: String? = null

    /**
     * 本次进程里 API 已经回 403（匿名配额用完）。
     * 再点检查就直接跳过它 不让每次手动检查都白等 5 秒。
     */
    @Volatile
    private var apiBlocked = false

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
     *
     * 正式版还认一下老键 `manifest`：上一版只存过这一份 别让老用户升级上来时白丢缓存。
     */
    fun cached(channel: UpdateChannel = UpdateChannel.RELEASE): UpdateInfo? {
        val p = prefs() ?: return null
        val raw = p.getString(manifestKey(channel), null)
            ?: (if (channel == UpdateChannel.RELEASE) p.getString(KEY_MANIFEST, null) else null)
            ?: return null
        return runCatching { json.decodeFromString<UpdateInfo>(raw) }
            .getOrNull()
            ?.takeIf { it.versionCode > 0 || it.versionName.isNotBlank() }
    }

    /**
     * 拉到清单就立刻落盘 失败也不影响本次结果。
     *
     * 缓存里那份比这次拉到的还新（镜像没刷新）时不动缓存 返回 false 由调用方决定用哪份。
     */
    private fun save(info: UpdateInfo, channel: UpdateChannel): Boolean {
        val p = prefs() ?: return false
        // 不同镜像的新鲜度不一样 偶尔会拿到比本地缓存还旧的副本（CDN 没刷新）
        // 这种不覆盖：否则刚查到的更新会被一份旧清单抹掉
        // 有构建时间就只比时间：versionCode 不可靠（清单的代号曾经从 15 回退到 11，
        // 老规则会把之后所有新清单都挡在门外 缓存永远停在旧的一份）
        val old = cached(channel)
        if (old != null) {
            if (info.buildTime > 0) {
                // 旧缓存没有时间戳（时间戳机制之前存的）→ 新的这份更晚 直接接受
                if (old.buildTime > 0 && info.buildTime < old.buildTime) return false
            } else if (old.versionCode > info.versionCode) {
                return false
            }
        }
        return runCatching {
            p.edit()
                .putString(manifestKey(channel), json.encodeToString(UpdateInfo.serializer(), info))
                .putString(KEY_PROXY, liveProxy.orEmpty())
                .apply()
        }.isSuccess
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // 本地版本走 PackageManager：用 MT 改过 AndroidManifest 的包装上来 这里读到的就是改后的值
    val currentCode: Int get() = AppVersion.code.toInt()
    val currentName: String get() = AppVersion.name

    /** 当前这份包的内测构建号：正式包是空串（时间戳上线前的老判法用） */
    val currentBuildId: String get() = BuildConfig.BUILD_ID

    /** 当前这份包的构建时间（epoch 毫秒）：打包时注入 读不到是 0 */
    val currentBuildTime: Long get() = BuildConfig.BUILD_TIME

    /** 手上这份是不是内测包：正式包不打构建号 为空串 */
    val isInternalBuild: Boolean get() = BuildConfig.BUILD_ID.isNotBlank()

    /**
     * 清单里那个包的构建时间 显示成「2026-11-06 01:07」。
     * 老清单没有这个字段 返回空串 调用方自己决定要不要显示这一行。
     */
    fun buildTimeLabel(info: UpdateInfo): String {
        if (info.buildTime <= 0) return ""
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(info.buildTime))
    }

    /**
     * 超时返回 null 与「没有更新」区分开：调用方可以区分"没查到"和"已最新"。
     *
     * [preferApi] 手动检查时传 true：GitHub API 排在最前面。
     * 启动那次不传 —— 每台设备每次启动都打一次 API 会很快吃光匿名配额（同出口 IP 共享 60 次/小时）。
     */
    suspend fun check(
        channel: UpdateChannel = UpdateChannel.RELEASE,
        preferApi: Boolean = false,
        timeoutMs: Long = 15_000,
    ): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching { fetch(channel, timeoutMs, preferApi) }.getOrNull()
    }

    /**
     * 有没有更新。
     *
     * 只认构建时间戳：谁后打包出来谁新，正式版和内测包同一套判法。
     * 清单里没写时间戳（时间戳机制上线前的老清单）才退回版本号/构建号。
     */
    fun isNewer(info: UpdateInfo?, channel: UpdateChannel = UpdateChannel.RELEASE): Boolean {
        if (info == null) return false
        if (info.buildTime > 0) {
            // 手上这份早于时间戳机制（装的是老包）先让它收到一次更新 装完就对上了
            if (currentBuildTime <= 0) return true
            return info.buildTime > currentBuildTime
        }
        // ---- 老清单：没有时间戳 ----
        if (channel == UpdateChannel.RELEASE) return info.versionCode > currentCode
        if (info.versionCode > currentCode) return true
        val remote = info.buildId.trim()
        if (remote.isEmpty()) return false
        return remote != currentBuildId.trim()
    }

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
        // 内测包与正式版共用同一个 versionCode，只能靠版本名后缀（-debug.42）分新旧
        // 所以同号时不能直接判「没有更新」 要退化到比名字
        if (info.moduleVersionCode > 0 && localCode > 0 && info.moduleVersionCode != localCode) {
            return info.moduleVersionCode > localCode
        }
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

    // ---------------- 下载兜底 ----------------
    // releases/download 那一跳要回源 GitHub 主站 镜像站全挂的时候它也跟着 504
    // （资产本身没丢 走 API 就能拿到）。这里把它换成 API 给出的签名直链：
    //   GET /releases/tags/<tag>        找到 asset id
    //   GET /releases/assets/<id>       302 → release-assets.githubusercontent.com 的签名地址
    // 签名地址能直接丢给浏览器 有效期约一小时 所以每次点下载现取一次

    /** releases/download/<tag>/<asset> */
    private val RELEASE_ASSET =
        Regex("""^https://github\.com/[^/]+/[^/]+/releases/download/([^/]+)/(.+)$""")

    @Serializable
    private data class ApiRelease(val assets: List<ApiAsset> = emptyList())

    @Serializable
    private data class ApiAsset(val id: Long = 0, val name: String = "")

    /**
     * 拿一个「现在点就能下」的地址。
     *
     * 优先走 API 换签名直链（绕开 504 的 releases/download）；
     * 换不到（API 限流 / 断网 / 不是 releases 链接）就退回原来的地址，行为与之前一致。
     */
    suspend fun resolveDownload(raw: String, timeoutMs: Long = 8_000): String =
        withContext(Dispatchers.IO) {
            val fallback = downloadUrl(raw)
            runCatching { apiDownload(raw, timeoutMs) }.getOrNull() ?: fallback
        }

    /** 查 tag 下的资产列表 找到同名资产 再换它的签名直链；任何一步不成都返回 null */
    private fun apiDownload(raw: String, timeoutMs: Long): String? {
        val m = RELEASE_ASSET.find(stripProxy(raw)) ?: return null
        val tag = m.groupValues[1]
        val asset = m.groupValues[2]
        val deadline = System.currentTimeMillis() + timeoutMs
        val text = httpGet("$API/releases/tags/$tag", "application/vnd.github+json", deadline)
            ?: return null
        val release = runCatching { json.decodeFromString<ApiRelease>(text) }.getOrNull() ?: return null
        val id = release.assets.firstOrNull { it.name == asset }?.id ?: return null
        return redirectLocation("$API/releases/assets/$id", deadline)
    }

    /** 去掉可能已经套上的加速站前缀：清单里存的是原始 github 链接 保险起见还是剥一层 */
    private fun stripProxy(url: String): String {
        var u = url.trim()
        PROXIES.forEach { p -> if (p.isNotEmpty() && u.startsWith(p)) u = u.removePrefix(p) }
        return u
    }

    private fun httpGet(url: String, accept: String, deadline: Long): String? = runCatching {
        val left = (deadline - System.currentTimeMillis()).coerceAtMost(6_000L).toInt()
        if (left <= 0) return@runCatching null
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = left
            readTimeout = left
            requestMethod = "GET"
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "HyperMount")
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                // 403 = 匿名配额用完：本轮之后的检查就别再试 API 了
                if (conn.responseCode == 403) apiBlocked = true
                return@runCatching null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            runCatching { conn.disconnect() }
        }
    }.getOrNull()

    /** 只读 302 的 Location：不跟随 否则会把整个 zip 拉进内存 */
    private fun redirectLocation(url: String, deadline: Long): String? = runCatching {
        val left = (deadline - System.currentTimeMillis()).coerceAtMost(6_000L).toInt()
        if (left <= 0) return@runCatching null
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = left
            readTimeout = left
            requestMethod = "GET"
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", "HyperMount")
        }
        try {
            val code = conn.responseCode
            if (code !in 300..399) return@runCatching null
            conn.getHeaderField("Location")?.takeIf { it.startsWith("http") }
        } finally {
            runCatching { conn.disconnect() }
        }
    }.getOrNull()

    private suspend fun fetch(
        channel: UpdateChannel,
        timeoutMs: Long,
        preferApi: Boolean,
    ): UpdateInfo? = withContext(Dispatchers.IO) {
        val hit = pull(channel, timeoutMs, preferApi)
        if (hit == null) {
            AppLog.log("更新", "${channel.label} 所有镜像都没拿到 ${manifestFile(channel)}")
            return@withContext null
        }
        val (info, from) = hit
        // 内测清单自己带模块（build-internal 会打一份 -debug 包） 直接用它；
        // 老内测清单没有模块那几项 才补一次正式清单 免得装上内测包的人收不到模块更新
        // 拉不到（断网）就用上次缓存的那份 至少别把已经提示过的模块更新弄丢
        val merged = if (channel == UpdateChannel.INTERNAL && !hasModule(info)) {
            val release = pull(UpdateChannel.RELEASE, MODULE_BUDGET_MS, preferApi)?.first
                ?: cached(UpdateChannel.RELEASE)
            info.withModule(release)
        } else {
            info
        }
        // 拿到就落盘：之后断网 / 重启 这次的结果都还在
        // 写入被拒说明缓存里那份更新（镜像没刷新）那就用它 不让更新提示倒退
        val result = if (save(merged, channel)) merged else cached(channel) ?: merged
        AppLog.log(
            "更新",
            "${channel.label} 来源 ${from} 远端 ${result.versionName}(${result.versionCode}) " +
                (result.buildId.takeIf { it.isNotBlank() }?.let { "构建 $it " } ?: "") +
                (if (hasModule(result)) "模块 ${moduleLabel(result)} " else "") +
                "本地 ${currentName}($currentCode)",
        )
        result
    }

    /**
     * 把正式清单里的模块信息搬到这份上。
     *
     * 老内测清单里没有模块信息 只能从正式清单里借：
     * 内测清单自带模块时不会走到这里。
     * 对方没有模块信息（老清单）时原样返回。
     */
    private fun UpdateInfo.withModule(release: UpdateInfo?): UpdateInfo {
        // 自己已经带模块了就以自己为准 别被正式清单盖回去
        if (hasModule(this)) return this
        if (release == null || !hasModule(release)) return this
        return copy(
            moduleVersionCode = release.moduleVersionCode,
            moduleVersionName = release.moduleVersionName,
            moduleUrl = release.moduleUrl,
            moduleChangelog = release.moduleChangelog,
        )
    }

    /** 一条候选源：proxy 用于记住"哪条能通" 下载链接跟着它走 */
    private class Source(
        val label: String,
        val proxy: String,
        val url: String,
        val accept: String = "application/json",
    )

    /**
     * 按「API → 直连 → 代理 → 镜像」逐条试到拿回一份清单为止。
     *
     * 阻塞式 HttpURLConnection 不响应协程取消，超时只能自己在每条之前掐：
     * 预算用完就停，免得一串死链把启动后的后台任务拖到半分钟。
     *
     * 返回清单和它的来源标签（日志里写出来 出问题一眼能看出是哪条通/哪条挂）。
     */
    private fun pull(channel: UpdateChannel, timeoutMs: Long, preferApi: Boolean): Pair<UpdateInfo, String>? {
        val manifest = rawManifest(channel)
        val sources = buildList {
            // 手动检查：API 排最前。镜像整片挂掉时（现在 releases/download 就在 504）它是唯一还有戏的一条
            if (preferApi && !apiBlocked) add(Source("API", API_MARK, apiManifest(channel), API_RAW))
            PROXIES.forEach { proxy ->
                add(Source(proxy.ifEmpty { "直连" }, proxy, proxy + manifest))
            }
            mirrors(channel).forEach { add(Source("镜像", "", it)) }
        }.let { list ->
            // 本次已经扫通过一条 就把它排到最前：手动重查时不用再从死链开始试
            // API 那条不动 它跟 raw 是两套 host 成功与否互不说明
            val known = liveProxy ?: return@let list
            val (api, rest) = list.partition { it.proxy == API_MARK }
            api + rest.sortedByDescending { if (it.proxy == known) 1 else 0 }
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        for (source in sources) {
            if (System.currentTimeMillis() >= deadline) break
            val info = fetchOne(source.url, source.accept, source.proxy == API_MARK) ?: continue
            // API 通了不代表 raw 直连通 别拿它覆盖下载要用的代理前缀
            if (source.proxy != API_MARK) liveProxy = source.proxy
            return info to source.label
        }
        return null
    }

    /**
     * 单条地址拉一次：解析失败（被劫持成 HTML 之类）也算失败 继续试下一条。
     *
     * [isApi] 只影响超时和限流处理：API 在境外 给宽一点；403 是匿名配额用完 本轮不再试。
     */
    private fun fetchOne(url: String, accept: String, isApi: Boolean = false): UpdateInfo? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            // 每条只给 3 秒：挂了就赶紧换下一条，别让一条死链吃掉整个预算
            connectTimeout = if (isApi) 5_000 else 3_000
            readTimeout = if (isApi) 5_000 else 3_000
            requestMethod = "GET"
            setRequestProperty("Accept", accept)
            // GitHub API 要求带 User-Agent（默认的 Java/… 有时会被挡）
            setRequestProperty("User-Agent", "HyperMount")
            // 走 CDN 不能吃到旧清单 否则刚发的版本永远查不到
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            val code = conn.responseCode
            if (code == 403 && isApi) apiBlocked = true
            if (code != HttpURLConnection.HTTP_OK) return@runCatching null
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
