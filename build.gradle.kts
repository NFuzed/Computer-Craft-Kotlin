plugins {
    kotlin("jvm") version "1.9.21"
    kotlin("plugin.serialization") version "1.9.21"
    application
}

group = "computer-craft-kotlin"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("io.ktor:ktor-bom:2.3.12"))

    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-netty-jvm")
    implementation("io.ktor:ktor-server-websockets-jvm")
    implementation("io.ktor:ktor-server-content-negotiation-jvm")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")

    implementation("ch.qos.logback:logback-classic")
}

application {
    mainClass.set("MainKt")
}

kotlin {
    jvmToolchain(20)
}

sourceSets {
    main {
        kotlin.srcDirs("computercraft-fleet/protocol/src/main/kotlin")
        resources.srcDirs("computercraft-fleet/protocol/src/main/resources")

        kotlin.srcDirs("computercraft-fleet/controllerKotlin/src/main/kotlin")
        resources.srcDirs("computercraft-fleet/controllerKotlin/src/main/resources")
    }
    test {
        kotlin.srcDirs("computercraft-fleet/protocol/src/test/kotlin")
        resources.srcDirs("computercraft-fleet/protocol/src/test/resources")

        kotlin.srcDirs("computercraft-fleet/controllerKotlin/src/test/kotlin")
        resources.srcDirs("computercraft-fleet/controllerKotlin/src/test/resources")
    }
}

