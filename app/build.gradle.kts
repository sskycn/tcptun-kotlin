import java.util.Properties
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val signingProperties = Properties().apply {
    val signingPropertiesFile = rootProject.file("signing.properties")
    if (signingPropertiesFile.isFile) {
        signingPropertiesFile.inputStream().use(::load)
    }
}

fun signingValue(propertyName: String, environmentName: String): String? =
    signingProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(environmentName).orNull?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "TCPTUN_RELEASE_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "TCPTUN_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "TCPTUN_RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "TCPTUN_RELEASE_KEY_PASSWORD")
val hasReleaseSigningConfig =
    !releaseStoreFile.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

val appVersionName = providers.gradleProperty("releaseVersionName").orNull?.also {
    require(it.isNotBlank()) { "releaseVersionName must not be blank" }
} ?: "1.0"
val appVersionCode = providers.gradleProperty("releaseVersionCode").orNull?.let { value ->
    requireNotNull(value.toIntOrNull()) { "releaseVersionCode must be an integer" }
        .also { require(it > 0) { "releaseVersionCode must be positive" } }
} ?: 1

val supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val targetAbi = providers.gradleProperty("targetAbi").orNull?.also { value ->
    require(value in supportedAbis) {
        "targetAbi must be one of ${supportedAbis.joinToString()}"
    }
}

android {
    namespace = "com.tcptun.client"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.tcptun.client"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += targetAbi?.let(::listOf) ?: supportedAbis
        }
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
            ndk {
                debugSymbolLevel = "FULL"
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "src/main/keepRules/rules.keep",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        managedDevices {
            localDevices {
                create("tcptunCiApi35") {
                    device = "Pixel 2"
                    apiLevel = 35
                    systemImageSource = "aosp-atd"
                }
            }
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.mlkit.barcode.scanning)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// androidbridge.aar contains prebuilt Go native libraries, so AGP cannot always
// export their unstripped symbols automatically. Keep a Play Console-compatible
// symbols ZIP alongside the release AAB.
val packageReleaseNativeSymbols = tasks.register<Zip>("packageReleaseNativeSymbols") {
    dependsOn("mergeReleaseNativeLibs")
    from(layout.buildDirectory.dir("intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib")) {
        include(
            "arm64-v8a/**/*.so",
            "armeabi-v7a/**/*.so",
            "x86_64/**/*.so",
        )
    }
    archiveFileName.set("native-debug-symbols.zip")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/native-debug-symbols/release"))
}

tasks.configureEach {
    if (name == "bundleRelease") {
        finalizedBy(packageReleaseNativeSymbols)
    }
}

val maintainabilityCheck = tasks.register<Exec>("maintainabilityCheck") {
    group = "verification"
    description = "Prevents known Kotlin hotspots from growing beyond their refactoring baselines."
    workingDir(rootProject.projectDir)
    commandLine("bash", "scripts/check-maintainability.sh")
    inputs.dir(layout.projectDirectory.dir("src/main/java"))
    inputs.file(rootProject.layout.projectDirectory.file("scripts/check-maintainability.sh"))
}

tasks.named("check").configure {
    dependsOn(maintainabilityCheck, "compileDebugAndroidTestKotlin")
}
