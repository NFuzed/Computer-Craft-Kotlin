package app.plugin

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import protocol.ProtocolJson

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(ProtocolJson)
    }
}
