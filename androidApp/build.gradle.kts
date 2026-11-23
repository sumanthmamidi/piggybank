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
    // Optional Google Drive backup: Google sign-in and the drive.appdata scope only.
    implementation(libs.play.services.auth)
}

/*
 * Privacy guard (test cases K1, K3). Fails the build if the merged manifest of any variant
 * asks for a permission outside this list — including permissions pulled in by libraries.
 * INTERNET exists only for the optional Drive backup (M4); verifyNetworkUse below fails the build
 * if any source file other than backup/DriveClient.kt opens a connection.
 */
val allowedPermissions = setOf(
    "android.permission.READ_SMS",
    "android.permission.RECEIVE_SMS",
    // Only for the optional Google Drive backup; verifyNetworkUse keeps it to DriveClient.kt.
    "android.permission.INTERNET",
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

/** K1/K2: the only network code is the Drive client. */
val verifyNetworkUse by tasks.registering {
    group = "verification"
    description = "Fails if any source file other than backup/DriveClient.kt uses a network API."
    val sources = fileTree(rootDir) {
        include("androidApp/src/**/*.kt", "shared/src/**/*.kt")
        exclude("**/backup/DriveClient.kt", "**/build/**")
    }
    inputs.files(sources)
    doLast {
        val network = Regex("""HttpURLConnection|openConnection|java\.net\.(URL|Socket)|\bSocket\(|okhttp|io\.ktor|Retrofit|WebView""")
        val offenders = sources.files.filter { network.containsMatchIn(it.readText()) }.map { it.relativeTo(rootDir).path }
        check(offenders.isEmpty()) { "Network code outside backup/DriveClient.kt: $offenders" }
        logger.lifecycle("Network use OK: only backup/DriveClient.kt")
    }
}

tasks.named("check") { dependsOn(verifyPermissions, verifyNetworkUse) }
