import java.security.MessageDigest

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

}

application {
    mainClass.set("app/MainKt")
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

fun sha1Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-1")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

tasks.register("genManifest") {
    group = "build"
    description = "Generate manifest.json with SHA1s for resource files"

    doLast {
        val resDir = project.layout.projectDirectory
            .dir("computercraft-fleet/controllerKotlin/src/main/resources")
            .asFile

        val templateFile = File(resDir, "manifest.template.json")
        val outFile = File(resDir, "manifest.json")

        require(templateFile.exists()) {
            "Missing manifest.template.json at: ${templateFile.path}"
        }

        val templateText = templateFile.readText(Charsets.UTF_8)

        val pathRegex = Regex("\"path\"\\s*:\\s*\"([^\"]+)\"")
        val paths = pathRegex.findAll(templateText)
            .map { it.groupValues[1] }
            .toList()

        require(paths.isNotEmpty()) { "No file paths found in manifest.template.json" }

        val shaByPath = paths.associateWith { p ->
            val f = File(resDir, p)
            require(f.exists()) { "Missing resource file: $p (expected at ${f.path})" }
            sha1Hex(f.readBytes())
        }

        var outText = templateText
        for ((path, sha) in shaByPath) {
            val fileEntryRegex = Regex(
                "(\"path\"\\s*:\\s*\"${Regex.escape(path)}\"\\s*,\\s*\"sha1\"\\s*:\\s*\")([^\"]*)(\")"
            )
            outText = outText.replace(fileEntryRegex) { m ->
                m.groupValues[1] + sha + m.groupValues[3]
            }
        }

        outFile.writeText(outText, Charsets.UTF_8)
        println("Wrote ${outFile.path}")
    }
}

tasks.named("run") {
    dependsOn("genManifest")
}