import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

fun prop(name: String): String = (project.findProperty(name) as String?)?.trim() ?: ""

val flutterWebBuildDir = rootProject.file("ui/build/web")
val flutterWebAssetsRootDir = layout.buildDirectory.dir("generated/omnibot_assets").get().asFile
val flutterWebAssetsDir = File(flutterWebAssetsRootDir, "flutter_web")

val buildFlutterWebBundle by tasks.registering(Exec::class) {
    group = "flutter web"
    description = "Build the dedicated web chat Flutter bundle."
    workingDir = rootProject.file("ui")
    val flutterCmd = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "flutter.bat" else "flutter"
    commandLine(
        flutterCmd,
        "build",
        "web",
        "--target",
        "lib/web_main.dart",
        "--base-href",
        "/webchat/",
        "--no-tree-shake-icons",
        "--no-wasm-dry-run"
    )
    inputs.dir(rootProject.file("ui/lib"))
    inputs.dir(rootProject.file("ui/web"))
    inputs.file(rootProject.file("ui/pubspec.yaml"))
    outputs.dir(flutterWebBuildDir)
    doFirst {
        delete(flutterWebBuildDir)
    }
}

val syncFlutterWebBundle by tasks.registering(Copy::class) {
    group = "flutter web"
    description = "Copy Flutter Web build output into Android assets."
    dependsOn(buildFlutterWebBundle)
    from(flutterWebBuildDir)
    into(flutterWebAssetsDir)
    doFirst {
        delete(flutterWebAssetsDir)
    }
}

gradle.projectsEvaluated {
    rootProject.findProject(":flutter")?.tasks
        ?.matching { it.name.startsWith("compileFlutterBuild") }
        ?.configureEach {
            val flutterCompileTask = this
            buildFlutterWebBundle.configure {
                mustRunAfter(flutterCompileTask)
            }
        }
}

android {
    namespace = "cn.com.omnimind.bot"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.com.omnimind.bot"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.5.0.5"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }

    }
    // 添加 flavor 维度
    flavorDimensions += listOf("version", "edition")

    productFlavors {
        create("develop") {
            dimension = "version"
            buildConfigField("String", "BASE_URL", "\"${prop("OMNIBOT_BASE_URL")}\"")
            buildConfigField("String", "APP_UPDATE_WORKER_URL", "\"${prop("OMNIBOT_UPDATE_WORKER_URL")}\"")
            resValue("bool", "is_accessibility_tool", "true")
        }

        create("production") {
            dimension = "version"
            buildConfigField("String", "BASE_URL", "\"${prop("OMNIBOT_BASE_URL")}\"")
            buildConfigField("String", "APP_UPDATE_WORKER_URL", "\"${prop("OMNIBOT_UPDATE_WORKER_URL")}\"")
            resValue("bool", "is_accessibility_tool", "true")
        }

        create("standard") {
            dimension = "edition"
            buildConfigField("boolean", "LOCAL_MODEL_FEATURE_ENABLED", "false")
            buildConfigField("String", "APP_EDITION", "\"standard\"")
        }

        create("omniinfer") {
            dimension = "edition"
            buildConfigField("boolean", "LOCAL_MODEL_FEATURE_ENABLED", "true")
            buildConfigField("String", "APP_EDITION", "\"omniinfer\"")
        }
    }
    signingConfigs {
        create("release") {
            // 引用全局gradle.properties中的变量
            storeFile = project.findProperty("OMNI_RELEASE_STORE_FILE")?.let { file(it) }
            storePassword = project.findProperty("OMNI_RELEASE_STORE_PWD") as String?
            keyAlias = project.findProperty("OMNI_RELEASE_KEY_ALIAS") as String?
            keyPassword = project.findProperty("OMNI_RELEASE_KEY_PWD") as String?

            // V2/V3签名配置（minSdk=30）
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += setOf(
                "**/libc++_shared.so"
            )
        }
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets", "../skills", flutterWebAssetsRootDir)
        }
        getByName("omniinfer") {
            assets.srcDirs("src/omniinfer/assets")
        }
    }

    lint {
        // 使用项目根目录的 lint.xml 配置
        lintConfig = file("../lint.xml")
        // 将错误视为警告继续构建
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.named("preBuild").configure {
    dependsOn(syncFlutterWebBundle)
}
dependencies {
    implementation(project(":flutter"))
    implementation(project(":uikit"))
    implementation(project(":baselib"))
    findProject(":omniinfer-server")?.let {
        add("omniinferImplementation", it)
    }
    implementation(project(":core:main"))
    implementation(project(":core:terminal-view"))
    implementation(project(":core:terminal-emulator"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar","*.jar"))))
    implementation(project(":assists"))
//    implementation(project(":lib"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.work.runtime)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.shizuku.provider)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.gson)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.call.logging)
    testImplementation(libs.junit)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest )
}
