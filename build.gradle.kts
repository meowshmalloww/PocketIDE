// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.chaquopy) apply false
}

tasks.register("verifySubmission") {
    group = "verification"
    description = "Runs the reproducible JVM tests, Android lint, and debug APK assembly used for submission."
    dependsOn(
        ":app:testDebugUnitTest",
        ":third_party:kotlinllamacpp:testDebugUnitTest",
        ":app:lintDebug",
        ":app:assembleDebug",
    )
}
