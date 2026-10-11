plugins { id("com.android.application"); kotlin("android"); id("com.google.devtools.ksp") }
android {
    namespace = "dev.notai.tracker.android"
    compileSdk = 35
    defaultConfig { applicationId = "dev.notai.tracker"; minSdk = 26; targetSdk = 35; versionCode = 10; versionName = "0.9.1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
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
