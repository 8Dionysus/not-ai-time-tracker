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
    systemProperty("tracker.samples", rootProject.file("samples").absolutePath)
    systemProperty("tracker.artifacts", layout.buildDirectory.dir("test-artifacts").get().asFile.absolutePath)
}
