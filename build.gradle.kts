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

        @Suppress("UNUSED_VARIABLE")
        val jsMain by getting {
            dependencies {
                // Kotlin/JS dependencies can be added here.
            }
        }

        @Suppress("UNUSED_VARIABLE")
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

/*
 * Валидация событий сценария: ссылки между событиями, координаты, идентификаторы техники.
 */
tasks.register<Exec>("verifyScenarioEvents") {
    group = "verification"
    description = "Validates authored <events> in scenario XML: references, anchors and spawn eqids"

    workingDir = rootDir
    commandLine("python", "scripts/check_scenario_events.py")
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

tasks.register<Exec>("verifyRulesetKeys") {
    group = "verification"
    description = "Checks every ruleset rule has a live engine read and complete en/ru copy"

    workingDir = rootDir
    commandLine("python", "scripts/check_ruleset_keys.py")
}

tasks.register<Exec>("verifyAuthorCredits") {
    group = "verification"
    description = "Validates the authorship sidecar and keeps credits out of description prose"

    workingDir = rootDir
    commandLine("python", "scripts/check_author_credits.py")
}

tasks.register<Exec>("verifyObjectiveVisibility") {
    group = "verification"
    description = "Locks the hidden/visible victory-objective audit the objectives rail relies on"

    workingDir = rootDir
    commandLine("python", "scripts/check_objective_visibility.py")
}

tasks.register<Exec>("verifyKeyboardManual") {
    group = "verification"
    description = "Checks manual.html's shortcut list against the game's keyboard command catalog"

    workingDir = rootDir
    commandLine("python", "scripts/check_keyboard_manual.py")
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
 * Scenario regression probe (2026-08-28).
 *
 * Added because the question "have the scenarios and maps been verified?" had no gate to answer
 * it: `verifyProductionSmokeTest` stops at the start menu (it reports `Scenario loaded: null`) and
 * no jsTest loads a scenario at all. This one drives four real scenarios -- including the largest
 * shipped map and the one with the most railroad stations -- places their units, resolves their
 * terrain and ends a turn on each.
 *
 * Kept out of `check` for the same reason the two smoke tests are: it needs a built distribution
 * and a real Chrome.
 */
tasks.register<Exec>("verifyScenarioRegression") {
    group = "verification"
    description = "Loads four real scenarios in headless Chrome and ends a turn on each"

    dependsOn(
        "jsBrowserDevelopmentExecutableDistribution",
        "verifyProductionSmokeTestNpmInstall",
    )

    workingDir = file("scripts/verify")
    commandLine("node", "og-fidelity-regression-probe.mjs")
}

/*
 * Turn Report toggle probe (2026-09-05).
 *
 * Added because "the yellow arrow is gone and the button looks off-centre" is a question about
 * rendered geometry, and reading CSS cannot answer it: `#combatLogButton` is absolutely positioned
 * inside `#statusbar` while the window it opens is pinned to the VIEWPORT centre, so whether the
 * two agree depends on what padding and width the bar ends up with. This measures both, in both
 * layouts, and checks that the desktop arrow and the phone sprite are never shown together.
 *
 * Kept out of `check` for the same reason the smoke tests are: it needs a built distribution and a
 * real Chrome.
 */
tasks.register<Exec>("verifyCombatLogButton") {
    group = "verification"
    description = "Measures the Turn Report toggle's face and centring in headless Chrome"

    dependsOn(
        "jsBrowserDistribution",
        "verifyProductionSmokeTestNpmInstall",
    )

    workingDir = file("scripts/verify")
    commandLine("node", "combatlog-button-probe.mjs")
}

/*
 * Headquarters roster geometry, and the scenario loading curtain.
 *
 * Two of the four faults in the 2026-09-05 roster screenshot were pure cascade accidents that no
 * unit test can see: this sheet has no global `border-box`, and `.osada-hero-rosterrow-portrait`'s
 * `background:` shorthand sits below `.osada-portrait-photo` and reset its `background-size`. Both
 * only exist once a browser has cascaded the real stylesheet, so they are measured in one.
 *
 * The curtain is here too because its whole job is a timing question -- it must be up before the
 * outgoing map is torn down and gone once the new one is painted.
 */
tasks.register<Exec>("verifyHeroRoster") {
    group = "verification"
    description = "Measures roster row geometry and the scenario loading curtain in headless Chrome"

    dependsOn(
        "jsBrowserDistribution",
        "verifyProductionSmokeTestNpmInstall",
    )

    workingDir = file("scripts/verify")
    commandLine("node", "hero-roster-probe.mjs")
}

/*
 * Mobile viewport smoke test.
 *
 * Kept separate from verifyProductionSmokeTest on purpose: that task is the desktop-regression
 * gate and must not be weakened into a touch-emulating hybrid. Chrome emulation is also not
 * evidence for iOS Safari — real-device results belong in the PR.
 */
tasks.register<Exec>("verifyMobileSmokeTest") {
    group = "verification"
    description = "Verifies the mobile shell at 667x375 with a coarse pointer in headless Chrome"

    dependsOn(
        "jsBrowserDistribution",
        "verifyProductionSmokeTestNpmInstall",
    )

    workingDir = file("scripts/verify")
    commandLine("node", "mobile-smoke.mjs")
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
        "verifyScenarioEvents",
        "verifyUnitDescriptions",
        "verifyTranslations",
        "verifyKeyboardManual",
        "verifyObjectiveVisibility",
        "verifyAuthorCredits",
        "verifyRulesetKeys",
        "verifyTrackedSources",
        "detekt",
        "ktlintCheck",
    )
}

tasks.named<org.gradle.jvm.tasks.Jar>("jsJar") {
    isZip64 = true
}
