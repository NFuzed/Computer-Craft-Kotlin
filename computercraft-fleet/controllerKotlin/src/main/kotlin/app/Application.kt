package app

import app.plugin.configureSerialization
import app.plugin.configureWebSockets
import app.route.configureRoutes
import app.service.FleetService
import app.store.LabelStore
import app.store.PositionStore
import app.store.SeedStore
import app.store.TurtleRegistry
import io.ktor.server.application.*
import java.io.File

fun Application.module() {
    configureWebSockets()
    configureSerialization()

    val positionStore = PositionStore(
        file = File("data/positions.json")
    )
    val seedStore = SeedStore(
        file = File("data/seeds.json")
    )
    val labelStore = LabelStore(
        file = File("data/labels.json")
    )
    val registry = TurtleRegistry()
    val fleet = FleetService(registry, positionStore, seedStore, labelStore)

    configureRoutes(fleet)
}
