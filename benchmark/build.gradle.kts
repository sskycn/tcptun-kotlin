plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "com.tcptun.client.benchmark"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            matchingFallbacks += listOf("release")
        }
    }

    testOptions.managedDevices.localDevices {
        create("tcptunBenchmarkApi35") {
            device = "Pixel 2"
            apiLevel = 35
            systemImageSource = "aosp"
        }
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.junit)
    implementation(libs.androidx.uiautomator)
}
