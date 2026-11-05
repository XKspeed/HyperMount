package com.xkspeed.HyperMount.core

import android.content.Context
import com.xkspeed.HyperMount.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

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
        // 内测包版本号跟正式版一致 只能靠构建时间分先后：时间更早（或没有时间戳）说明是旧副本
        val old = cached(channel)
        if (old != null) {
            if (old.versionCode > info.versionCode) return false
            if (old.versionCode == info.versionCode &&
                old.buildTime > 0 && info.buildTime < old.buildTime
            ) {
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

    /** 超时返回 null 与「没有更新」区分开：调用方可以区分"没查到"和"已最新" */
    suspend fun check(
        channel: UpdateChannel = UpdateChannel.RELEASE,
        timeoutMs: Long = 15_000,
    ): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching { fetch(channel, timeoutMs) }.getOrNull()
    }

    /**
     * 清单里那份是不是比手上这份还旧。
     *
     * 只认 versionCode：内测包与正式版同号 光看版本号分不出先后 不算"旧"。
     * 这是唯一需要拒绝的情况里最硬的一条 —— 降级包装不上（Android 不许 versionCode 降低覆盖安装）。
     */
    fun isOutdated(info: UpdateInfo?): Boolean {
        if (info == null) return false
        return info.versionCode in 1 until currentCode
    }

    /**
     * 有没有更新。
     *
     * 一份清单同时服务两种包 靠构建时间戳分先后：
     * 内测包和正式版共用 versionCode 版本号一样大 谁新谁旧只有打包时间说了算。
     * 远端比手上晚 → 有更新；比手上早 → 拒绝（典型是正式版发过之后 versionCode 上去了，
     * 内测清单还停在发版前那次构建 那时推内测包还会被系统拦下来）。
     *
     * 时间戳相同就是同一份构建 不用重复提示；清单里没写时间戳（时间戳上线前的老清单）
     * 退回原来的判法：正式版比 versionCode 内测版比构建号。
     */
    fun isNewer(info: UpdateInfo?, channel: UpdateChannel = UpdateChannel.RELEASE): Boolean {
        if (info == null) return false
        // 降级包一律拒绝 后面时间戳再新也装不进去
        if (isOutdated(info)) return false
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

    private suspend fun fetch(
        channel: UpdateChannel,
        timeoutMs: Long,
    ): UpdateInfo? = withContext(Dispatchers.IO) {
        val manifest = rawManifest(channel)
        val candidates = buildList {
            PROXIES.forEach { proxy -> add(proxy to proxy + manifest) }
            mirrors(channel).forEach { add("" to it) }
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
            val result = if (save(info, channel)) info else cached(channel) ?: info
            AppLog.log(
                "更新",
                "${channel.label} 命中镜像 ${proxy.ifEmpty { "直连/CDN" }} " +
                    "远端 ${result.versionName}(${result.versionCode}) " +
                    (result.buildId.takeIf { it.isNotBlank() }?.let { "构建 $it " } ?: "") +
                    "本地 ${currentName}($currentCode)",
            )
            return@withContext result
        }
        AppLog.log("更新", "${channel.label} 所有镜像都没拿到 ${manifestFile(channel)}")
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
