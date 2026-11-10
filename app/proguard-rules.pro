# ============================================================
# R8 / ProGuard 规则
# release 构建开启 minify + shrinkResources（R8 默认开启）。
# ============================================================

# ------- Kotlinx Serialization -------
# 项目用 @Serializable 数据类做反序列化（App.kt / PickerPage.kt）。
# 不保留会被 R8 移除/重命名，导致 Json 反射查找失败、运行时崩溃。
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepattributes InnerClasses
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$Companion Companion;
}
-keepclassmembers class <2>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    static **$Companion Companion;
}
-keepclassmembers class <2>$Companion {
    static <2>$Companion INSTANCE;
}
-keep class kotlinx.serialization.** { *; }

# ------- 应用入口（双保险，AGP 的 aapt 规则也会保留清单组件）-------
-keep public class com.xkspeed.HyperMount.MainActivity { *; }

# ------- libsu（root 命令执行，含反射/ServiceLoader，自带规则保底）-------
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# ------- Miuix / Compose 依赖自带 consumer 规则，这里只抑制潜在警告 -------
-dontwarn top.yukonga.miuix.**
-dontwarn org.jetbrains.skiko.**
-dontwarn io.github.kyant0.**