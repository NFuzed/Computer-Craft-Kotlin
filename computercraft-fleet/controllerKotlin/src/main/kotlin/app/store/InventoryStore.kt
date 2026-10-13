package app.store

import protocol.api.InventorySlot
import protocol.api.TurtleFuel
import protocol.api.TurtleInventory
import java.util.concurrent.ConcurrentHashMap

/**
 * The last inventory each turtle reported. Kept in memory and written through to the `turtle_inventory` and
 * `inventory_slot` tables, so the dashboard can still show it after a server restart, before the turtle reconnects.
 * [TurtleInventory.lastRefuel] isn't saved.
 */
class InventoryStore(private val db: Database) {
    private val map = ConcurrentHashMap<Int, TurtleInventory>()

    init {
        load()
    }

    @Synchronized
    fun put(turtleId: Int, inventory: TurtleInventory) {
        db.transaction { conn ->
            conn.execute(
                """
                INSERT INTO turtle_inventory (turtle_id, selected_slot, fuel_reported, fuel_level, fuel_limit, ts)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (turtle_id) DO UPDATE SET
                    selected_slot = EXCLUDED.selected_slot, fuel_reported = EXCLUDED.fuel_reported,
                    fuel_level = EXCLUDED.fuel_level, fuel_limit = EXCLUDED.fuel_limit, ts = EXCLUDED.ts
                """,
                turtleId, inventory.selectedSlot, inventory.fuel != null, inventory.fuel?.level,
                inventory.fuel?.limit, inventory.ts
            )
            conn.execute("DELETE FROM inventory_slot WHERE turtle_id = ?", turtleId)
            conn.prepareStatement(
                "INSERT INTO inventory_slot (turtle_id, slot, name, count, nbt) VALUES (?, ?, ?, ?, ?)"
            ).use { stmt ->
                for (s in inventory.slots) {
                    stmt.bind(arrayOf(turtleId, s.slot, s.name, s.count, s.nbt)).addBatch()
                }
                stmt.executeBatch()
            }
        }
        map[turtleId] = inventory
    }

    fun get(turtleId: Int): TurtleInventory? = map[turtleId]

    private fun load() {
        db.transaction { conn ->
            val slots = conn.query("SELECT * FROM inventory_slot ORDER BY turtle_id, slot") { rs ->
                rs.getInt("turtle_id") to InventorySlot(
                    slot = rs.getInt("slot"),
                    name = rs.getString("name"),
                    count = rs.getInt("count"),
                    nbt = rs.getString("nbt")
                )
            }.groupBy({ it.first }, { it.second })

            conn.query("SELECT * FROM turtle_inventory") { rs ->
                val turtleId = rs.getInt("turtle_id")
                turtleId to TurtleInventory(
                    selectedSlot = rs.getInt("selected_slot"),
                    slots = slots[turtleId].orEmpty(),
                    fuel = if (rs.getBoolean("fuel_reported")) {
                        TurtleFuel(level = rs.getIntOrNull("fuel_level"), limit = rs.getIntOrNull("fuel_limit"))
                    } else {
                        null
                    },
                    ts = rs.getLong("ts")
                )
            }.toMap(map)
        }
    }
}
