plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.5.0"
}
rootProject.name = "Computer-Craft-Kotlin"


include("controllerKotlin")
project(":controllerKotlin").projectDir =
    file("computercraft-fleet/controllerKotlin")

include("protocol")
project(":protocol").projectDir =
    file("computercraft-fleet/protocol")
