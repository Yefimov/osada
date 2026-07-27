plugins {
    kotlin("multiplatform") version "2.3.21"

    // Detekt 2.x поддерживает JDK 25.
    id("dev.detekt") version "2.0.0-alpha.3"

    // Проверка форматирования Kotlin-кода.
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
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
        // Opt in once for the whole project instead of a @file:OptIn header in every
        // file that touches @JsExport (the exported game API surface).
        all {
            languageSettings.optIn("kotlin.js.ExperimentalJsExport")
        }

        val jsMain by getting {
            dependencies {
                // Kotlin/JS dependencies can be added here.
            }
        }

        val jsTest by getting {
            dependencies {
                implementation(kotlin("test-js"))

                // Webpack source-map preprocessing used by
                // the Kotlin/JS Karma test runner.
                implementation(npm("source-map-loader", "4.0.1"))
            }
        }
    }
}

/*
 * Detekt
 *
 * Анализирует code smells:
 * LargeClass, LongMethod, LongParameterList,
 * ReturnCount, MagicNumber, unused declarations и т. д.
 */
val detektConfigFile = file("$rootDir/config/detekt/detekt.yml")

detekt {
    toolVersion.set("2.0.0-alpha.3")

    buildUponDefaultConfig.set(true)
    allRules.set(false)

    parallel.set(true)
    ignoreFailures.set(false)

    config.setFrom(detektConfigFile)

    source.setFrom(
        "src/jsMain/kotlin",
        "src/jsTest/kotlin",
    )
}

tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    exclude(
        "**/build/**",
        "**/generated/**",
        "**/node_modules/**",
    )

    reports {
        html.required.set(true)
        checkstyle.required.set(true)
        sarif.required.set(true)
        markdown.required.set(false)
    }
}

tasks.withType<dev.detekt.gradle.DetektCreateBaselineTask>().configureEach {
    exclude(
        "**/build/**",
        "**/generated/**",
        "**/node_modules/**",
    )
}

/*
 * ktlint
 *
 * Проверяет форматирование, отступы, импорты,
 * пробелы и максимальную длину строки.
 */
ktlint {
    verbose.set(true)
    outputToConsole.set(true)
    ignoreFailures.set(false)

    filter {
        exclude("**/build/**")
        exclude("**/generated/**")
        exclude("**/node_modules/**")
    }
}

/*
 * Собственная Python-проверка проекта.
 */
tasks.register<Exec>("verifyStaticChecks") {
    group = "verification"
    description = "Runs Python static checks on JS/Kotlin consistency and index.html"

    workingDir = rootDir
    commandLine("python", "scripts/check_kotlin_js_consistency.py")
}

/*
 * Валидация диалогов кампаний: граф диалога, условия, эффекты и цели сценария.
 */
tasks.register<Exec>("verifyCampaignDialogue") {
    group = "verification"
    description = "Validates campaign dialogue graphs, conditions, effects and scenario actions"

    workingDir = rootDir
    commandLine("python", "scripts/check_campaign_dialogue.py")
}

tasks.register<Exec>("verifyUnitDescriptions") {
    group = "verification"
    description = "Validates row-specific equipment narrative descriptions"

    workingDir = rootDir
    commandLine("python", "scripts/check_unit_descriptions.py")
}

tasks.register<Exec>("verifyTranslations") {
    group = "verification"
    description = "Validates localization bundles, placeholders, plurals and stable key usage"

    workingDir = rootDir
    commandLine("python", "scripts/check_translations.py")
}

tasks.register<Exec>("verifyTrackedSources") {
    group = "verification"
    description = "Fails when a Kotlin source under src/ is untracked by git (DEFERRED.md §4.7)"

    // Never up-to-date: the answer depends on the git index, not on any file this task reads.
    outputs.upToDateWhen { false }

    workingDir = rootDir
    commandLine("python", "scripts/check_tracked_sources.py")
}

/*
 * Подготовка зависимостей production smoke test.
 */
tasks.register<Exec>("verifyProductionSmokeTestNpmInstall") {
    group = "verification"
    description = "Installs Node dependencies for the production smoke test"

    workingDir = file("scripts/verify")

    inputs.file("scripts/verify/package.json")
    inputs.file("scripts/verify/package-lock.json")
    outputs.dir("scripts/verify/node_modules")

    val isWindows =
        System
            .getProperty("os.name")
            ?.lowercase()
            ?.contains("win") == true

    commandLine =
        if (isWindows) {
            listOf("cmd", "/c", "npm", "ci")
        } else {
            listOf("npm", "ci")
        }
}

/*
 * Production smoke test.
 */
tasks.register<Exec>("verifyProductionSmokeTest") {
    group = "verification"
    description = "Builds production distribution and verifies it loads in headless Chrome"

    dependsOn(
        "jsBrowserDistribution",
        "verifyProductionSmokeTestNpmInstall",
    )

    workingDir = file("scripts/verify")
    commandLine("node", "verify.mjs")
}

/*
 * Общая проверка проекта.
 *
 * Detekt и ktlint обычно сами подключаются к check,
 * но здесь зависимости указаны явно.
 */
tasks.named("check") {
    dependsOn(
        "verifyStaticChecks",
        "verifyCampaignDialogue",
        "verifyUnitDescriptions",
        "verifyTranslations",
        "verifyTrackedSources",
        "detekt",
        "ktlintCheck",
    )
}

tasks.named<org.gradle.jvm.tasks.Jar>("jsJar") {
    isZip64 = true
}
