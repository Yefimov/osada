plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "osada"

// Self-hosted multiplayer room server (JVM). Deployed to the VPS next to the static game build;
// see docs/multiplayer-server-deployment.md.
include(":multiplayer-server")
