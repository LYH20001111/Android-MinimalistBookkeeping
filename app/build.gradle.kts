import com.android.build.api.variant.impl.VariantOutputImpl
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.skyanchor.bookkeeping"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.skyanchor.bookkeeping"
        minSdk = 24
        targetSdk = 36
        // V3：版本 3→4 / 2.1.0→3.0.0
        versionCode = 4
        versionName = "3.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // V2：导出 Room schema 到 app/schemas，供 MigrationTestHelper 验证 2→3 迁移。
        javaCompileOptions {
            annotationProcessorOptions {
                arguments.put("room.schemaLocation", "$projectDir/schemas")
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
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // 源码含中文注释与 emoji 字面量，显式锁定 UTF-8，避免随平台默认编码漂移
        encoding = "UTF-8"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // 将导出的 Room schema 目录加入 androidTest assets，MigrationTestHelper 从 assets 读取历史版本。
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

androidComponents {
    onVariants { variant ->
        val time = SimpleDateFormat("yyyyMMddHHmm").format(Date())
        val vName = android.defaultConfig.versionName ?: "1.0.0"
        val buildType = variant.buildType ?: ""
        var apkBaseName = "极简记账-$vName-$buildType-$time.apk"
        variant.outputs.forEach { output ->
            if (output is VariantOutputImpl) {
                output.outputFileName.set(apkBaseName)
            }
        }
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(libs.fragment)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)
    // V3：云同步（本地优先不变，同步层独立于业务层）
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.workmanager)
    implementation(libs.room.runtime)
    annotationProcessor(libs.room.compiler)
    testImplementation(libs.junit)
    // 供 BackupSerializer 的 JVM 单测使用真实 org.json 实现（仅测试作用域，不进 APK）
    testImplementation(libs.json)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.room.testing)
}