import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.piggybank"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.piggybank"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0-m0"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.datetime)
    // Nightly backup (I1, I2): runs once a day, waits while the battery is low.
    implementation(libs.androidx.work.runtime)
}

/*
 * Privacy guard (test cases K1, K3). Fails the build if the merged manifest of any variant
 * asks for a permission outside this list — including permissions pulled in by libraries.
 * INTERNET is deliberately absent; it is added only in M4 for the optional Drive backup,
 * and adding it must be an explicit edit to this list.
 */
val allowedPermissions = setOf(
    "android.permission.READ_SMS",
    "android.permission.RECEIVE_SMS",
    "android.permission.POST_NOTIFICATIONS",
    // Opt-in "save where I paid"; read from the OS, stored on the phone only.
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_BACKGROUND_LOCATION",
    // WorkManager, for the nightly backup: keep the device awake for the run, and reschedule after a reboot.
    "android.permission.WAKE_LOCK",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    // Added by androidx.core on API 33+ for its own broadcasts; it is app-private.
    "app.piggybank.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
)

val verifyPermissions by tasks.registering {
    group = "verification"
    description = "Fails if the merged manifest requests a permission not on the allowlist."
    dependsOn("processDebugMainManifest", "processReleaseMainManifest")
    val manifests = fileTree(layout.buildDirectory.dir("intermediates/merged_manifest")) { include("**/AndroidManifest.xml") }
    inputs.files(manifests)
    doLast {
        val files = manifests.files
        check(files.isNotEmpty()) { "No merged manifest found; did the manifest tasks run?" }
        val pattern = Regex("""<uses-permission(?:-sdk-23)?[^>]*android:name="([^"]+)"""")
        files.forEach { file ->
            val requested = pattern.findAll(file.readText()).map { it.groupValues[1] }.toSet()
            val extra = requested - allowedPermissions
            check(extra.isEmpty()) { "Permission(s) not allowed in ${file.path}: $extra" }
            check(file.readText().contains("""android:allowBackup="false"""")) {
                "allowBackup must be false so Android's own backup never copies PiggyBank data (${file.path})"
            }
            logger.lifecycle("Permissions OK (${file.parentFile.parentFile.name}): ${requested.sorted()}")
        }
    }
}

tasks.named("check") { dependsOn(verifyPermissions) }
