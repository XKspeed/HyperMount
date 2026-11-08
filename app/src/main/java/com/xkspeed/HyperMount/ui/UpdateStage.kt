package com.xkspeed.HyperMount.ui

/**
 * 更新卡片当前处在哪一步。
 *
 * 卡片是原地换形态的：点下载后按钮位置换成进度条（更新说明不动），
 * 下完卡片变成安装卡 —— 不弹新页面也不换位置。
 */
sealed interface UpdateStage {

    /** 还没开始：更新说明 + 下载按钮 */
    data object Idle : UpdateStage

    /** 下载前筛源：并发探一遍候选镜像，已探完 [done] 条 / 共 [total] 条 */
    data class Probing(val done: Int, val total: Int) : UpdateStage

    /** 下载中：[progress] 0f..1f，按钮位置显示进度 整张卡底色也按它填充 */
    data class Downloading(val progress: Float) : UpdateStage

    /** 下好了：已经拉起系统安装器 点卡片可以再拉起一次 */
    data class ReadyToInstall(val path: String) : UpdateStage

    /** 下载失败：卡片变红 点一下重试 */
    data class Failed(val reason: String) : UpdateStage

    /** 模块包已落到公共目录（模块不装 只给 zip） */
    data class Saved(val path: String) : UpdateStage
}
