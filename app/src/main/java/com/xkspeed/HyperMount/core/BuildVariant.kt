package com.xkspeed.HyperMount.core

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * 判断当前装的是不是"自己签的正式包"。
 *
 * 不用 `BuildConfig.DEBUG`：debug/release 两种构建都可能被 MT 重签、改版本号，
 * 编译期标记跟实际签名对不上。这里直接比证书指纹 —— 只有用项目 release 私钥
 * 签出来的包才认为正式版；自己签的 debug 包、MT 改过的包指纹都不一样，
 * 于是调试入口自然只在测试时出现。
 */
object BuildVariant {
    /** release-key.jks 的 SHA-256（与 apksigner verify 打印的一致） */
    private const val RELEASE_CERT_SHA256 =
        "13a1bc6fd161f117bb368bc6ee103f47c2257cd5b8042e9b9842dc4a5b897011"

    @Volatile
    private var appContext: Context? = null

    /** 在 Application.onCreate 里调一次 */
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * true = 不是正式签名（debug 包、MT 重签的测试包）
     *
     * 读不到签名时按 true 处理：宁可多给一个调试入口，也不要在调试包上把它藏掉。
     */
    val isDebuggable: Boolean by lazy { !hasReleaseSignature() }

    private fun hasReleaseSignature(): Boolean {
        val context = appContext ?: return false
        return runCatching {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(
                    PackageManager.GET_SIGNING_CERTIFICATES.toLong(),
                ),
            )
            val signers = info.signingInfo?.apkContentsSigners ?: return@runCatching false
            signers.any { signature -> sha256(signature.toByteArray()) == RELEASE_CERT_SHA256 }
        }.getOrDefault(false)
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            digest.forEach { b -> append("%02x".format(b)) }
        }
    }
}
