// Plugin versions come from the root build, which already puts Kotlin, Detekt and ktlint on the
// build classpath; repeating a version here fails plugin resolution.
plugins {
    kotlin("jvm")
    application

    id("dev.detekt")
    id("org.jlleitschuh.gradle.ktlint")
}

group = "org.osada"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val ktorVersion = "3.5.2"

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-compression-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-caching-headers-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("ch.qos.logback:logback-classic:1.5.20")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-client-websockets-jvm:$ktorVersion")

    // Gradle 9 no longer puts the JUnit Platform launcher on the test worker classpath for us.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

application {
    applicationName = "osada-server"
    mainClass.set("org.osada.mpserver.MainKt")

    // The VPS also runs Foundry VTT and imchargen on ~2 GB of RAM, so the room server stays small.
    applicationDefaultJvmArgs = listOf("-Xms32m", "-Xmx192m", "-XX:+UseSerialGC")
}

tasks.named<Tar>("distTar") {
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
}

tasks.test {
    useJUnitPlatform()
}

detekt {
    toolVersion.set("2.0.0-alpha.3")

    buildUponDefaultConfig.set(true)
    allRules.set(false)

    parallel.set(true)
    ignoreFailures.set(false)

    config.setFrom(file("$rootDir/config/detekt/detekt.yml"))

    source.setFrom(
        "src/main/kotlin",
        "src/test/kotlin",
    )
}

tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    exclude("**/build/**")

    reports {
        html.required.set(true)
        checkstyle.required.set(true)
        sarif.required.set(true)
        markdown.required.set(false)
    }
}

ktlint {
    verbose.set(true)
    outputToConsole.set(true)
    ignoreFailures.set(false)

    filter {
        exclude("**/build/**")
    }
}
