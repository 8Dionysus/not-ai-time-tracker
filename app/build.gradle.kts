import java.security.MessageDigest

plugins { id("com.android.application"); kotlin("android"); kotlin("plugin.serialization"); kotlin("plugin.compose"); id("com.google.devtools.ksp") }
// Package the prepared vocabulary, never source research or preparation readers.
val bundledCatalogAssets = tasks.register<Sync>("bundleCatalogAssets") {
    from(rootProject.file("catalog")) { include("manifest.json", "runtime-catalog.json", "compatibility.json", "locales/**"); into("catalog") }
    into(layout.buildDirectory.dir("generated/catalogAssets"))
}
android {
    namespace = "dev.notai.tracker.android"
    compileSdk = 36
    ndkVersion = "30.0.16248370"
    externalNativeBuild { ndkBuild { path = rootProject.file("core/src/main/c/Android.mk") } }
    defaultConfig { applicationId = "dev.notai.tracker"; minSdk = 26; targetSdk = 36; versionCode = 16; versionName = "0.15.0";testInstrumentationRunner = "dev.notai.tracker.android.ArchiveInstrumentation" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    if(providers.gradleProperty("trackerUnsigned").orNull == "true") buildTypes.getByName("debug").signingConfig = null
    sourceSets.getByName("main").assets.srcDir(bundledCatalogAssets)
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Full catalog/history scenarios plus Robolectric need more than Gradle's 512 MiB default.
            test.maxHeapSize = "1g"
            // Robolectric Android 16/API36 requires a Java 21 runtime. Bytecode target stays 17.
            test.javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
            // Robolectric 4.17 FileDescriptor interception on Java21 uses this public internal interface.
            test.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED")
            test.systemProperty("tracker.samples", rootProject.file("samples").absolutePath)
            providers.gradleProperty("trackerCleanupLibrary").orNull?.let { test.systemProperty("tracker.cleanup.library",it) }
            test.systemProperty("tracker.artifacts", layout.buildDirectory.dir("test-artifacts").get().asFile.absolutePath)
            test.systemProperty("tracker.scale", providers.gradleProperty("trackerScale").getOrElse("false"))
            test.systemProperty("tracker.cold.profile", providers.gradleProperty("trackerColdProfile").getOrElse("false"))
            test.systemProperty("tracker.cold.variant", providers.gradleProperty("trackerColdVariant").getOrElse("normalized"))
            providers.gradleProperty("trackerAndroidFixture").orNull?.let { test.systemProperty("tracker.android.fixture",it) }
            // Screens are rendered by Robolectric only when explicitly requested: -PuiScreenshots=true
            test.systemProperty("tracker.screenshots", providers.gradleProperty("uiScreenshots").getOrElse("false"))
            test.systemProperty("tracker.screenshotDir", rootProject.file("docs/ui/screens").absolutePath)
            // Robolectric writes a lock and Maven cache under user.home. Keep test-only state in the project.
            val testHome = rootProject.file(".gradle/robolectric")
            test.systemProperty("user.home", testHome.absolutePath)
            test.doFirst { testHome.mkdirs() }
            listOf("https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort").forEach { key ->
                System.getProperty(key)?.let { value -> test.systemProperty(key, value) }
            }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(bundledCatalogAssets) }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    ksp("androidx.room:room-compiler:2.7.2")
    // Product interface. Versions are retained while moving compileSdk to 36 / Kotlin 2.1 compatible releases.
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation(testFixtures(project(":core")))
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
}

// Read-only release evidence. Project artifacts are built before their hashes are captured.
tasks.register("releaseInventory") {
    dependsOn(":core:jar")
    doLast {
        val rows = configurations.getByName("releaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts.map { artifact ->
            val digest = MessageDigest.getInstance("SHA-256")
            artifact.file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while(true) { val count = input.read(buffer);if(count < 0) break;if(count > 0) digest.update(buffer,0,count) }
            }
            listOf(artifact.moduleVersion.id.toString(),artifact.file.name,digest.digest().joinToString("") { "%02x".format(it) },artifact.file.absolutePath).joinToString("\t")
        }.sorted()
        val output = rootProject.layout.buildDirectory.file("release-inventory.tsv").get().asFile
        output.parentFile.mkdirs();output.writeText("coordinate\tartifact\tsha256\tlocal_path\n"+rows.joinToString("\n")+"\n")
        logger.lifecycle("Recorded ${rows.size} release runtime artifacts")
    }
}
