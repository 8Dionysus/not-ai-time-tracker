plugins { kotlin("jvm"); kotlin("plugin.serialization"); `java-test-fixtures` }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test-junit"))
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
