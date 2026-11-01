plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.xkspeed.HyperMount"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.xkspeed.HyperMount"
        minSdk = 35
        targetSdk = 37
        versionCode = 7
        versionName = "1.2.2"

        // 只打 arm64：HyperOS 机型全是 arm64-v8a，
        // 其余 ABI 的 .so（目前只有 androidx.graphics.path）白占体积
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("release-key.jks")
            // keytool（JDK 21 起）默认生成的是 PKCS12，尽管扩展名是 .jks。
            // 不显式声明类型时可能被按 JKS 解析，报
            // "Failed to read key ... Tag number over 30 is not supported"
            storeType = "pkcs12"
            // 优先读 CI 注入的环境变量；本地无环境变量时用默认值（与本地 keystore 一致）
            storePassword = System.getenv("KEY_STORE_PASSWORD") ?: "SysUI@Mount2026"
            keyAlias = System.getenv("KEY_ALIAS") ?: "systemuimount"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "SysUI@Mount2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        // 设置页要显示应用版本（BuildConfig.VERSION_NAME）
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    // activity 1.13.0 起才装配 navigationevent，否则 Miuix 的返回手势拦截会抛
    // IllegalStateException（No NavigationEventDispatcher was provided），启动即崩。
    // 与 ApkEditorMiuix / miuix-example 保持同一组合。
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigationevent:navigationevent-compose-android:1.1.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Miuix —— 小米 HyperOS 风格 UI（0.9.4 正式版，2026-09 发布）
    implementation("top.yukonga.miuix.kmp:miuix-core-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-shader-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-squircle-android:0.9.4")
    // 顶栏/底栏的实时模糊（示例 App 的 BlurredBar 依赖它）
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")
    // 二级页转场 + 预测性返回（NavDisplay / NavTransitions）
    implementation("top.yukonga.miuix.kmp:miuix-nav-android:0.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // 液态玻璃引擎：AGSL 渐进模糊 + 折射/内阴影/描边高光
    // 顶栏与底栏用的是 Sonify 的同款实现，必须同版本（1.0.5 的 padding 取整行为被依赖）
    implementation("io.github.kyant0:backdrop:1.0.5")
    implementation("io.github.kyant0:shapes:1.0.0")

    // libsu —— root 命令执行（topjohnwu，Magisk 作者，2.1k star）
    // 用它的常驻 shell，避免每条命令都 fork 一个 su 进程
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
}
