# 液态玻璃组件来源声明

本目录的液态玻璃组件，原始实现来自 **Kyant0/AndroidLiquidGlass 1.0.5（Apache-2.0）**。

## 血缘

| 顺位 | 项目 | 许可证 | 与本目录的关系 |
|---|---|---|---|
| 原始实现 | [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) `1.0.5` | Apache-2.0 | 代码骨架、动画参数、AGSL 着色器均出自此处的 `catalog/` 示例 |
| 二次改编 | compose-miuix-ui 示例 `component/liquid/*` | Apache-2.0 | 文件头自述 `Adapted from Kyant0/AndroidLiquidGlass (Apache 2.0)`，仅作结构参考 |
| 三次改编 | [NexioSchedule](https://github.com/HaoZai000/NexioSchedule) | AGPL-3.0 | 把上述组件改名搬进自己的 AGPL 工程，未保留上游声明 |
| 四次改编 | [Sonify](https://github.com/XiaoZhi0729/Sonify) | GPL-3.0 | 另一条支线，独立改编自上游 AP2 样例，加了主题色、玻璃开关等改动 |

NexioSchedule 与 Sonify 是**两条互不相干的支线**（行级重合 4.1%），都改编自同一份 AP2 样例，
不是「Nexio 再被 Sonify 改编」。早期我们直接复制的是 Sonify 那一份。

## 重写进度

按 AndroidLiquidGlass 1.0.5 原文回归的作品：`LiquidBottomTab`、`DampedDragAnimation`、
`DragGestureInspector`（主干逐字对齐，只改包名与 `Capsule` 的 API 用法）。

仍在重写中、当前仍带 Sonify 改写痕迹的：`LiquidBottomTabs`（AP2 骨架约 43%，其余为 Sonify
自写的玻璃开关与点按回根）、`LiquidTopBarButton`（Sonify 重写，与 AP2 `LiquidButton` 仅约 10%
重合）、`InteractiveHighlight`（AP2 主干 + Sonify 扩展）。这三处在本文件末尾如实标注。

`TitleBarProgressiveBlur` 出自 NexioSchedule 的 `ProgressiveBlurTopBar`（AGPL-3.0）；
`WrapHighlightStyle` 的高光数值配方与 Sonify 注释所记的 ClassYaba 实现一致，该库无公开仓库，
我们只沿用参数值，代码表达为自写。

在上述部分完成重写前，本应用整体仍按 AGPL-3.0 发布——这是取值更严格的一侧，
对 Sonify 的 GPL-3.0 与 NexioSchedule 的 AGPL-3.0 都成立。

## 为什么本应用仍以 AGPL-3.0 发布

Apache-2.0 代码可以并入 GPLv3 / AGPLv3 作品（单向兼容），条件是保留原作者的版权与许可声明。
本应用选 AGPL-3.0，是为了让分发出去的修改版同样必须开源，避免被加壳闭源转卖。
上游的 Apache-2.0 权利不受影响，声明见本文件与仓库根 `LICENSE`。

## 文件对应

| 本目录文件 | AndroidLiquidGlass 1.0.5 原文件 | 关系 |
|---|---|---|
| LiquidBottomTab.kt | catalog/components/LiquidBottomTab.kt | 主干逐字对齐 |
| LiquidBottomTabs.kt | catalog/components/LiquidBottomTabs.kt | 主干对齐，另有本项目新增分支 |
| DampedDragAnimation.kt | catalog/utils/DampedDragAnimation.kt | 主干逐字对齐 |
| DragGestureInspector.kt | catalog/utils/DragGestureInspector.kt | 主干逐字对齐 |
| InteractiveHighlight.kt | catalog/utils/InteractiveHighlight.kt | 主干对齐，AGSL 径向高光原样保留 |
| LiquidTopBarButton.kt | catalog/components/LiquidButton.kt | 仅参考玻璃分层思路，实现自写 |
| WrapHighlightStyle.kt | — | 自写（环绕高光强度公式自行推导） |
| TitleBarProgressiveBlur.kt | — | 自写（AGSL 多重采样，参考 destinations/ProgressiveBlurContent.kt 思路） |
| TitleCollapsePolicy.kt | — | 自写 |
| BackdropAdaptiveLuminance.kt | catalog/destinations/AdaptiveLuminanceGlassContent.kt | 采样思路参考，实现自写 |
| LiquidBackdropLuminance.kt | 同上 | 自写 |
| ScrollPage.kt / GlassBottomNav.kt / StandardBottomNav.kt / FrostedCapsule.kt / BottomBarMetrics.kt | — | 自写（接入 Miuix 主题与底栏实测高度） |

移植时对上游做的改动：包名替换、主题色接入 MiuixTheme、去掉上游的调试探针与调参开关、
模糊倍率固定为 1f、底栏高度改为运行时实测。
