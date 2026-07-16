plugins {
    kotlin("multiplatform") version "2.3.21"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    js(IR) {
        browser {
            commonWebpackConfig {
                cssSupport {
                    enabled.set(true)
                }
            }
        }
        binaries.executable()
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                // Зависимости для Kotlin/JS при необходимости
                // implementation("org.jetbrains.kotlinx:kotlinx-html-js:0.11.0")
            }
        }
        val jsTest by getting {
            dependencies {
                implementation(kotlin("test-js"))
                // Webpack source-map preprocessing used by the Kotlin/JS Karma test runner.
                implementation(npm("source-map-loader", "4.0.1"))
            }
        }
    }
}

tasks.named<Copy>("jsProcessResources") {
    // Исходные архивы ресурсов не нужны в дистрибутиве — используется только
    // объединённая копия в resources/ и css/
    exclude("Panzer+Marshal_3.2.10_Android/**")
    exclude("Panzer_Marshal_3.2.14_Browser/**")
}

tasks.register<Exec>("verifyStaticChecks") {
    group = "verification"
    description = "Runs Python static checks on JS/Kotlin consistency and index.html"
    workingDir = rootDir
    commandLine("python", "scripts/check_kotlin_js_consistency.py")
}

tasks.register<Exec>("verifyProductionSmokeTestNpmInstall") {
    group = "verification"
    description = "Installs Node dependencies for the production smoke test"
    workingDir = file("scripts/verify")
    inputs.file("scripts/verify/package.json")
    inputs.file("scripts/verify/package-lock.json")
    outputs.dir("scripts/verify/node_modules")
    val isWindows = System.getProperty("os.name")?.lowercase()?.contains("win") == true
    commandLine = if (isWindows) listOf("cmd", "/c", "npm", "ci") else listOf("npm", "ci")
}

tasks.register<Exec>("verifyProductionSmokeTest") {
    group = "verification"
    description = "Builds production distribution and verifies it loads in headless Chrome"
    dependsOn("jsBrowserDistribution", "verifyProductionSmokeTestNpmInstall")
    workingDir = file("scripts/verify")
    commandLine("node", "verify.mjs")
}

tasks.named("check") {
    dependsOn("verifyStaticChecks")
}
