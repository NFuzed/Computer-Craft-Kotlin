package app

import app.plugin.configureSerialization
import app.plugin.configureWebSockets
import app.route.configureRoutes
import app.service.FleetService
import app.service.TurtleSessions
import app.store.*
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
    val inventoryStore = InventoryStore(
        file = File("data/inventories.json")
    )
    val registry = TurtleRegistry()
    val fleet = FleetService(registry, TurtleSessions(), positionStore, seedStore, labelStore, inventoryStore)

    configureRoutes(fleet)
}
