plugins { id("com.android.application"); kotlin("android"); kotlin("plugin.serialization"); kotlin("plugin.compose"); id("com.google.devtools.ksp") }
// Package the prepared vocabulary, never source research or preparation readers.
val bundledCatalogAssets = tasks.register<Sync>("bundleCatalogAssets") {
    from(rootProject.file("catalog")) { include("manifest.json", "runtime-catalog.json", "compatibility.json"); into("catalog") }
    into(layout.buildDirectory.dir("generated/catalogAssets"))
}
android {
    namespace = "dev.notai.tracker.android"
    compileSdk = 35
    defaultConfig { applicationId = "dev.notai.tracker"; minSdk = 26; targetSdk = 35; versionCode = 11; versionName = "0.10.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets.getByName("main").assets.srcDir(bundledCatalogAssets)
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Full catalog/history scenarios plus Robolectric need more than Gradle's 512 MiB default.
            test.maxHeapSize = "1g"
            test.systemProperty("tracker.samples", rootProject.file("samples").absolutePath)
            test.systemProperty("tracker.artifacts", layout.buildDirectory.dir("test-artifacts").get().asFile.absolutePath)
            test.systemProperty("tracker.scale", providers.gradleProperty("trackerScale").getOrElse("false"))
            test.systemProperty("tracker.cold.profile", providers.gradleProperty("trackerColdProfile").getOrElse("false"))
            test.systemProperty("tracker.cold.variant", providers.gradleProperty("trackerColdVariant").getOrElse("baseline"))
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
    // Product interface. Versions stay on compileSdk 35 / Kotlin 2.1 compatible releases.
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
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
}
