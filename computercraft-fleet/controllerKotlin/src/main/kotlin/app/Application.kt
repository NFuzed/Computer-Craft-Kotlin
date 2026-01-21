package app

import app.plugin.configureWebSockets
import app.route.configureRoutes
import io.ktor.server.application.*

fun Application.module() {
    configureWebSockets()
    configureRoutes()
}
