import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val devEnv: Properties = Properties().apply {
    val file = sequenceOf(rootProject.file("../.env"), rootProject.file(".env")).firstOrNull { it.exists() }
    if (file != null) file.inputStream().use { load(it) }
}

fun secret(key: String): String = devEnv.getProperty(key).orEmpty()

android {
    namespace = "com.voca.speaker"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.voca.speaker"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // 密钥只进 debug 包，.env 里的值在这一层留空，release 包和开源分发不带任何开发者凭据。
        // 模型名与音色不是凭据，出厂默认值照旧随包走。
        buildConfigField("String", "DEV_GEMINI_API_KEY", "\"\"")
        buildConfigField("String", "DEV_GEMINI_LIVE_MODEL", "\"${secret("GEMINI_LIVE_MODEL").ifBlank { "gemini-3.8-live" }}\"")
        buildConfigField("String", "DEV_GEMINI_TEXT_MODEL", "\"${secret("GEMINI_TEXT_MODEL").ifBlank { "gemini-flash-latest" }}\"")
        buildConfigField("String", "DEV_GEMINI_VOICE", "\"${secret("GEMINI_VOICE").ifBlank { "Aoede" }}\"")
        buildConfigField("String", "DEV_OPENAI_API_KEY", "\"\"")
        buildConfigField("String", "DEV_OPENAI_REALTIME_MODEL", "\"${secret("OPENAI_REALTIME_MODEL").ifBlank { "gpt-realtime-2.1" }}\"")
        buildConfigField("String", "DEV_DOUBAO_API_KEY", "\"\"")
        buildConfigField("String", "DEV_QWEN_API_KEY", "\"\"")
        buildConfigField("String", "DEV_DEEPSEEK_API_KEY", "\"\"")
        buildConfigField("String", "DEV_DEEPSEEK_TEXT_MODEL", "\"${secret("DEEPSEEK_TEXT_MODEL").ifBlank { "deepseek-chat" }}\"")
        buildConfigField("String", "DEV_XFYUN_APP_ID", "\"\"")
        buildConfigField("String", "DEV_XFYUN_API_KEY", "\"\"")
        buildConfigField("String", "DEV_XFYUN_API_SECRET", "\"\"")
        buildConfigField("String", "DEV_TENCENT_APP_ID", "\"\"")
        buildConfigField("String", "DEV_TENCENT_SECRET_ID", "\"\"")
        buildConfigField("String", "DEV_TENCENT_SECRET_KEY", "\"\"")
        buildConfigField("String", "DEV_AZURE_SPEECH_KEY", "\"\"")
        buildConfigField("String", "DEV_AZURE_SPEECH_REGION", "\"${secret("AZURE_SPEECH_REGION")}\"")

        // 只留真机在用的 arm64：onnxruntime 一套原生库就三十多兆，四套指令集全打进去
        // 光这一项就占一百四十多兆，安装包凭空大一倍。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEV_GEMINI_API_KEY", "\"${secret("GEMINI_API_KEY")}\"")
            buildConfigField("String", "DEV_OPENAI_API_KEY", "\"${secret("OPENAI_API_KEY")}\"")
            buildConfigField("String", "DEV_DOUBAO_API_KEY", "\"${secret("DOUBAO_API_KEY")}\"")
            buildConfigField("String", "DEV_QWEN_API_KEY", "\"${secret("DASHSCOPE_API_KEY")}\"")
            buildConfigField("String", "DEV_DEEPSEEK_API_KEY", "\"${secret("DEEPSEEK_API_KEY")}\"")
            buildConfigField("String", "DEV_XFYUN_APP_ID", "\"${secret("XFYUN_APP_ID")}\"")
            buildConfigField("String", "DEV_XFYUN_API_KEY", "\"${secret("XFYUN_API_KEY")}\"")
            buildConfigField("String", "DEV_XFYUN_API_SECRET", "\"${secret("XFYUN_API_SECRET")}\"")
            buildConfigField("String", "DEV_TENCENT_APP_ID", "\"${secret("TENCENT_APP_ID").ifBlank { secret("TECENT_APPID") }}\"")
            buildConfigField("String", "DEV_TENCENT_SECRET_ID", "\"${secret("TENCENT_SECRET_ID").ifBlank { secret("TECENT_SECRETID") }}\"")
            buildConfigField("String", "DEV_TENCENT_SECRET_KEY", "\"${secret("TENCENT_SECRET_KEY").ifBlank { secret("TECENT_SECRETKEY") }}\"")
            buildConfigField("String", "DEV_AZURE_SPEECH_KEY", "\"${secret("AZURE_SPEECH_KEY")}\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // 报文层用 android.util.Log 记供应商原文，单测里让它退成空实现，不然解析路径一进日志就抛。
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // 垃圾桶那支吞噬动效用 Lottie 播，其余动效仍然手写。
    implementation(libs.lottie.compose)

    // 这一轮里有没有人说话由 Silero VAD 判，模型随包走，不依赖网络。
    implementation(libs.onnxruntime.android)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation(libs.onnxruntime.jvm)
}
