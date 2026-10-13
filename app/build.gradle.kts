plugins { id("com.android.application"); kotlin("android"); id("com.google.devtools.ksp") }
// Bundle only the eight reviewed inputs; the trusted manifest remains a core resource.
// Copy into generated assets, so the repository keeps one original of each source.
val bundledCatalogAssets = tasks.register<Sync>("bundleCatalogAssets") {
    from(rootProject.file("research")) {
        include("catalog.research.json", "supporting/tracker_catalog_core_compatibility.md",
            "supporting/tracker_catalog_identity_resolution.json", "supporting/tracker_cross_domain_relations_v2_1.json",
            "supporting/tracker_mobile_catalog_navigation_research.json", "supporting/tracker_work_learning_core_v4_compatibility.json",
            "projections/isco_full_projection_v2.json", "projections/sports_additive_catalogue_projection.json")
        into("research")
    }
    into(layout.buildDirectory.dir("generated/catalogAssets"))
}
android {
    namespace = "dev.notai.tracker.android"
    compileSdk = 35
    defaultConfig { applicationId = "dev.notai.tracker"; minSdk = 26; targetSdk = 35; versionCode = 10; versionName = "0.9.1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").assets.srcDir(bundledCatalogAssets)
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Full research-source scenarios plus Robolectric need more than Gradle's 512 MiB default.
            test.maxHeapSize = "1g"
            test.systemProperty("tracker.samples", rootProject.file("samples").absolutePath)
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
    testImplementation("junit:junit:4.13.2")
    testImplementation(testFixtures(project(":core")))
    testImplementation("org.robolectric:robolectric:4.14.1")
}
