package com.xkspeed.HyperMount.core.mcp

/**
 * 连上来的 AI 在 initialize 时拿到的使用说明
 *
 * 走 MCP 规范的 `InitializeResult.instructions`：客户端连上就自动塞进模型上下文，
 * 不需要用户手动贴。写的是「什么时候该调哪个工具、怎么等、结果怎么读」，
 * 具体参数以 tools/list 里的 schema 为准，这里不重复。
 *
 * 用英文写：各家客户端与模型对英文指令的遵循度更稳定。
 */
internal object McpInstructions {

    val TEXT: String = """
        HyperMount is a test rig for an Android device's SystemUI. You upload an APK, it is installed and
        run on the phone for one round, then you read back the verdict and the logcat.

        Only act when the user explicitly asks for a test. One run at a time; never run two concurrently.

        ## Pick the right entry point
        - update_systemui_and_run_systemui_test: accepts only com.android.systemui. It overwrites the
          module's mounted MiuiSystemUI.apk and restarts SystemUI. A broken APK means a black screen,
          recoverable only by the volume-key rescue. Sending confirm=true is a statement that the user
          accepted that risk, so do not send it unless the user clearly agreed.
        - update_apk_run_apk_test: every other APK goes here. It installs with pm install, then captures
          logcat for N seconds. com.android.systemui is rejected; use the entry above instead.
        - miui.systemui.plugin belongs on the second entry. It runs inside the host process, and logging is
          already pinned to com.android.systemui; do not override the package yourself.

        ## Uploading
        Small files (up to roughly 1 MB) can go through apk_base64. For anything larger, first POST the raw
        bytes to /upload, take the returned path, then pass it as apk_path. Base64 of a multi-megabyte APK
        will blow up the context window.

        ## Waiting for the verdict
        Both entry points return immediately with a task_id and state=running; neither blocks until the end.
        - Afterwards call get_result. While state is running, check again every 3-5 seconds until done or failed.
        - APK mode finishes in about seconds+5. SystemUI mode waits for the module daemon to settle, so it
          still reports running for the first 15 seconds; that alone is not a failure.
        - Never re-invoke a test entry point to "hurry it up"; it will simply answer that a test is already running.

        ## Reading the result
        The result field is one of:
        - normal: it came up fine
        - rescue_triggered: it crashed and the rescue APK / rescue UI was restored automatically
        - crash: it crashed and no rescue ran
        - rescue_failed: it crashed and the rescue also failed; needs manual handling
        - error / unknown: the run did not complete; see message
        SystemUI mode also reports protect_delta (rescue count for this round). APK mode also reports systemui_alive.

        ## Getting the log
        get_log returns raw logcat, not the app's own output lines. APK mode reads the logcat captured during
        this round; SystemUI mode reads the crash buffer the module grabbed at crash time, and an empty result
        usually means nothing crashed, not that the test failed.
        To find the fault, search for FATAL EXCEPTION, ANR in, and Process ... has died, then read from the
        innermost "Caused by" outward.

        ## Reporting back
        State which chain ran and what the verdict was. If it crashed, give the first exception's class name,
        line number, and cause, then propose the fix. If a SystemUI test leaves a black screen, remind the user
        to press the volume keys to rescue instead of rebooting repeatedly.
    """.trimIndent()
}
