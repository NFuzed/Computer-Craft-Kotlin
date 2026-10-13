package app

import app.plugin.configureSerialization
import app.plugin.configureWebSockets
import app.route.configureRoutes
import app.service.FleetService
import app.service.TurtleSessions
import app.store.*
import io.ktor.server.application.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private const val WORLD_MAP_SAVE_INTERVAL_MS = 5_000L

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
    val equipmentStore = EquipmentStore(
        file = File("data/equipment.json")
    )
    val worldMapStore = WorldMapStore(
        file = File("data/worldmap.json")
    )
    // the world map changes on every turtle move, so it's saved every few seconds rather than on every change
    launch {
        while (true) {
            delay(WORLD_MAP_SAVE_INTERVAL_MS)
            worldMapStore.saveIfDirty()
        }
    }
    environment.monitor.subscribe(ApplicationStopped) { worldMapStore.saveIfDirty() }

    val registry = TurtleRegistry()
    val fleet = FleetService(
        registry, TurtleSessions(), positionStore, seedStore, labelStore, inventoryStore, equipmentStore,
        worldMapStore
    )

    configureRoutes(fleet)
}
