import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 发布签名配置。
 *
 * 密钥信息**不进版本库**，按以下优先级读取：
 *  1. 环境变量（CI 用，GitHub Actions 从 Secrets 注入）
 *  2. keystore.properties（本地用，已被 .gitignore 排除）
 *
 * 两者都没有时，release 构建产出未签名包，不影响 assembleDebug。
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) {
        f.inputStream().use { load(it) }
    }
}

fun signingValue(envKey: String, propKey: String): String? =
    System.getenv(envKey)?.takeIf { it.isNotBlank() }
        ?: keystoreProps.getProperty(propKey)?.takeIf { it.isNotBlank() }

val storeFilePath = signingValue("SIGNING_STORE_FILE", "storeFile")
val storePasswordValue = signingValue("SIGNING_STORE_PASSWORD", "storePassword")
val keyAliasValue = signingValue("SIGNING_KEY_ALIAS", "keyAlias")
val keyPasswordValue = signingValue("SIGNING_KEY_PASSWORD", "keyPassword")

val hasSigningConfig = !storeFilePath.isNullOrBlank() &&
    !storePasswordValue.isNullOrBlank() &&
    !keyAliasValue.isNullOrBlank() &&
    !keyPasswordValue.isNullOrBlank()

/**
 * 解析 keystore 路径。
 *
 * 注意：本文件位于 `app/` 目录，`file()` 的基准是模块目录而非项目根目录。
 * 因此相对路径统一按**项目根目录**解析，避免 `../xxx` 被解析到错误位置。
 */
fun resolveStoreFile(path: String): File {
    val f = File(path)
    return if (f.isAbsolute) f else rootProject.file(path)
}

android {
    namespace = "com.clrscreen.skipad"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.clrscreen.skipad"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = resolveStoreFile(storeFilePath!!)
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue

                // 同时启用 v1/v2/v3，兼容 Android 7.0 ~ 最新
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        // 调试构建里输出判定得分日志，release 中由 R8 常量折叠移除
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// 无任何第三方依赖：应用不申请网络权限，完全离线运行
dependencies {
}

// 构建时提示签名状态，避免误把未签名包当成品分发
gradle.taskGraph.whenReady {
    if (allTasks.any { it.name.contains("Release") }) {
        if (hasSigningConfig) {
            logger.lifecycle("[ClrScreen] Release 签名已配置，将产出可直接安装的 APK")
        } else {
            logger.lifecycle("[ClrScreen] 未找到签名配置，release 包将是未签名状态")
        }
    }
}
