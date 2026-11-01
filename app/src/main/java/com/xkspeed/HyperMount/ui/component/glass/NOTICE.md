# 液态玻璃组件来源声明

本目录下的文件移植自 Sonify（https://github.com/XiaoZhi0729/Sonify）
原项目为 GPL-3.0 许可 因此本应用整体以 GPL-3.0 发布

移植时做的事：包名替换、主题色接入 MiuixTheme、调试探针（GlassDiagnostics）
与调参开关（GlassProbe 实际读取）移除或固化为默认值、模糊倍率固定为 1f

| 文件 | 原文件 |
|---|---|
| TitleBarProgressiveBlur.kt | widgets/basic/TitleBarProgressiveBlur.kt（原样） |
| TitleCollapsePolicy.kt | widgets/basic/TitleCollapsePolicy.kt（原样） |
| LiquidTopBarButton.kt | widgets/basic/LiquidTopBarButton.kt |
| BackdropAdaptiveLuminance.kt | widgets/basic/BackdropAdaptiveLuminance.kt |
| LiquidBackdropLuminance.kt | widgets/basic/LiquidBackdropLuminance.kt |
| LiquidBottomTabs.kt | widgets/liquid/LiquidBottomTabs.kt |
| LiquidBottomTab.kt | widgets/liquid/LiquidBottomTab.kt |
| DampedDragAnimation.kt | widgets/liquid/DampedDragAnimation.kt |
| InteractiveHighlight.kt | widgets/liquid/InteractiveHighlight.kt |
| WrapHighlightStyle.kt | widgets/liquid/WrapHighlightStyle.kt |
| DragGestureInspector.kt | widgets/liquid/DragGestureInspector.kt |

ScrollPage.kt / GlassBottomNav.kt / GlassProbe.kt 是本项目写的适配层
参考了原项目的 Title.kt / BottomNavgator.kt 结构
