// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.test) apply false
}

tasks.register("qualityGate") {
    group = "verification"
    description = "Runs all source-quality checks required by CI and formal releases."
    dependsOn(
        ":app:testDebugUnitTest",
        ":app:lintDebug",
        ":app:lintRelease",
        ":app:compileDebugAndroidTestKotlin",
        ":app:maintainabilityCheck",
    )
}
