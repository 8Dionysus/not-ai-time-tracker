plugins { kotlin("jvm"); kotlin("plugin.serialization"); `java-test-fixtures` }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test-junit"))
    // Already used by the existing build toolchain; host fixture only, not shipped.
    testImplementation("org.xerial:sqlite-jdbc:3.41.2.2")
    testFixturesImplementation(kotlin("test-junit"))
    testFixturesImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

tasks.test {
    providers.gradleProperty("trackerCleanupLibrary").orNull?.let { systemProperty("tracker.cleanup.library",it) }
    doFirst { rootProject.layout.buildDirectory.get().asFile.mkdirs() }
    systemProperty("tracker.samples", rootProject.file("samples").absolutePath)
    systemProperty("tracker.artifacts", layout.buildDirectory.dir("test-artifacts").get().asFile.absolutePath)
}

// Opt-in isolated host capacity/profile fixture; not a JUnit pass or Android measurement.
tasks.register<JavaExec>("scheduleDeliveryProfile") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.notai.tracker.ScheduleDeliveryProfile")
    maxHeapSize = "1024m"
    providers.gradleProperty("trackerCleanupLibrary").orNull?.let { systemProperty("tracker.cleanup.library",it) }
    systemProperty("tracker.schedule.profile",layout.buildDirectory.file("schedule-delivery-profile.json").get().asFile.absolutePath)
}

tasks.register<JavaExec>("indexedScheduleDeliveryProfile") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.notai.tracker.IndexedScheduleDeliveryProfile")
    maxHeapSize = "1024m"
    systemProperty("tracker.schedule.index.profile",layout.buildDirectory.file("indexed-schedule-delivery-profile.json").get().asFile.absolutePath)
}
