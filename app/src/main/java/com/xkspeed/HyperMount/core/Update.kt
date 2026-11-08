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
    // 更新包/模块包的 SHA-256：镜像站不可信 下完必须对一遍
    // 老清单没有这两项（空串）时退化成只比文件大小
    val sha256: String = "",
    val moduleSha256: String = "",
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

    // 内测通道补模块信息时额外给正式清单的预算：模块只跟 Release 发 内测清单里没有
    // 拿不到就算了（还有缓存兜着） 不能让这一次附带请求把整个检查拖长
    private const val MODULE_BUDGET_MS = 8_000L

    /** 并发探测单条的超时：镜像站半死不活时 5 秒还没回就当它挂了 */
    private const val PROBE_MS = 5_000L

    /** 竞速胜出的源记多久：镜像不会半小时内整体复活 这期间不再重复探测 */
    private const val SESSION_MS = 30 * 60 * 1000L

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
     * 判断一个地址是不是已经套过镜像前缀（避免重复拼接）。
     * 候选池本身在 [Mirrors] 里，不写死任何单个站点。
     */
    private val PROXY_PREFIXES: List<String> =
        Mirrors.pool.filter { it.kind == MirrorKind.PREFIX }.map { it.base }

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

    /**
     * 本次会话竞速出来的清单源。
     *
     * 镜像站不会在半小时内突然全好，所以胜出的一条记 30 分钟：
     * 这期间再检查直接用，不再让十几条候选重新跑一遍。
     */
    private class SessionPick(val label: String, val url: String, val proxy: String)

    private val sessions = java.util.concurrent.ConcurrentHashMap<UpdateChannel, Pair<SessionPick, Long>>()

    private fun sessionPick(channel: UpdateChannel): SessionPick? {
        val (pick, until) = sessions[channel] ?: return null
        if (System.currentTimeMillis() > until) {
            sessions.remove(channel)
            return null
        }
        return pick
    }

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

    /** App 更新包的原始地址（未套镜像） */
    fun apkUrl(info: UpdateInfo?): String =
        info?.url.orEmpty().ifBlank { "https://github.com/$REPO/releases" }

    /**
     * App 更新包的下载候选：原始地址 + 所有能转发 release 资产的镜像。
     * 哪条能用、支不支持分段由 [Downloader] 现场探测，这里只负责把候选列出来。
     */
    fun apkSources(info: UpdateInfo?): List<String> =
        Mirrors.fileVariants(apkUrl(info)).map { it.second }.distinct()

    /** 模块 zip 的下载候选：清单里没给地址时返回空列表 */
    fun moduleSources(info: UpdateInfo?): List<String> {
        val raw = info?.moduleUrl.orEmpty()
        if (raw.isBlank()) return emptyList()
        return Mirrors.fileVariants(raw).map { it.second }.distinct()
    }

    /**
     * 给下载地址套上本次扫通的代理。
     * GitHub 直连能通就不加前缀；通了镜像才查到的更新 这里同样走镜像。
     */
    fun downloadUrl(raw: String): String {
        val url = raw.trim().ifEmpty { "https://github.com/$REPO/releases" }
        val proxy = liveProxy.orEmpty()
        if (proxy.isEmpty()) return url
        // 已经是代理/镜像地址就不重复加前缀
        if (PROXY_PREFIXES.any { it.isNotEmpty() && url.startsWith(it) }) return url
        return proxy + url
    }


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


    /**
     * 拿一份清单。
     *
     * 顺序：
     *  1. 手动检查先问 API（镜像整片挂掉时它通常是唯一还活着的一条）
     *  2. 30 分钟内竞速胜出的源直接用，不再重新探测
     *  3. 并发探测全部候选（直连 + 镜像池 + CDN），谁先回来用谁
     *  4. 全挂 → 回退官方直连再试一次：检查更新这个功能本身不能因为有镜像就失败
     */
    private suspend fun pull(
        channel: UpdateChannel,
        timeoutMs: Long,
        preferApi: Boolean,
    ): Pair<UpdateInfo, String>? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs

        // 1. API
        if (preferApi && !apiBlocked) {
            fetchOne(apiManifest(channel), API_RAW, isApi = true)?.let {
                return@withContext it to "API"
            }
        }

        // 2. 本次会话已经竞速出来的源
        sessionPick(channel)?.let { pick ->
            val info = fetchOne(pick.url, "application/json")
            if (info != null) return@withContext info to pick.label
            sessions.remove(channel)
        }

        // 3. 并发探测：每条 5 秒，并行跑，总耗时就是最慢那条
        val budget = minOf(PROBE_MS, (deadline - System.currentTimeMillis()).coerceAtLeast(1_000L))
        val ranked = Mirrors.rank(Mirrors.manifestVariants(rawManifest(channel)), budget)
        for ((mirror, url) in ranked) {
            if (System.currentTimeMillis() >= deadline) break
            val info = fetchOne(url, "application/json") ?: continue
            val label = mirror?.label ?: "直连"
            // 域名替换型的镜像只能读文件 转不了 release 资产 下载仍走直连/前缀站
            val proxy = if (mirror != null && mirror.kind == MirrorKind.PREFIX) mirror.base else ""
            liveProxy = proxy
            sessions[channel] = SessionPick(label, url, proxy) to (System.currentTimeMillis() + SESSION_MS)
            return@withContext info to label
        }

        // 4. fail-open：探测全挂了也再试一次官方直连
        fetchOne(rawManifest(channel), "application/json")?.let {
            liveProxy = ""
            return@withContext it to "直连"
        }
        null
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
