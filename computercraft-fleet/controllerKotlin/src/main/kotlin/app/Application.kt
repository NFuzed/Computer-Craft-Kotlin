package app

import app.plugin.configureSerialization
import app.plugin.configureWebSockets
import app.route.configureRoutes
import app.service.FleetService
import app.service.TurtleSessions
import app.store.*
import io.ktor.server.application.*

fun Application.module() = module(Database.fromEnvironment())

/** The server, on [db]. Tests call this with their own database; the server closes it when it stops. */
fun Application.module(db: Database) {
    configureWebSockets()
    configureSerialization()

    environment.monitor.subscribe(ApplicationStopped) { db.close() }

    val positionStore = PositionStore(db)
    val seedStore = SeedStore(db)
    val labelStore = LabelStore(db)
    val inventoryStore = InventoryStore(db)
    val equipmentStore = EquipmentStore(db)
    val worldMapStore = WorldMapStore(db)

    val registry = TurtleRegistry()
    val fleet = FleetService(
        registry, TurtleSessions(), positionStore, seedStore, labelStore, inventoryStore, equipmentStore,
        worldMapStore
    )

    configureRoutes(fleet)
}
