package app.store

import kotlinx.serialization.json.Json
import protocol.ProtocolJson
import protocol.api.EquippedUpgrade
import protocol.api.TurtleEquipment
import java.util.concurrent.ConcurrentHashMap

/**
 * The upgrades each turtle last reported or was seeded with. Kept in memory and written through to the `turtle_equipment` table, so the
 * dashboard knows what a turtle can do after a server restart, before the turtle reconnects.
 */
class EquipmentStore(
    private val db: Database,
    private val json: Json = ProtocolJson
) {
    private val map = ConcurrentHashMap<Int, TurtleEquipment>()

    init {
        load()
    }

    fun put(turtleId: Int, equipment: TurtleEquipment) {
        db.transaction {
            it.execute(
                """
                INSERT INTO turtle_equipment (turtle_id, left_side, right_side, items_known, capabilities, seeded, ts)
                VALUES (?, CAST(? AS JSONB), CAST(? AS JSONB), ?, ?, ?, ?)
                ON CONFLICT (turtle_id) DO UPDATE SET
                    left_side = EXCLUDED.left_side, right_side = EXCLUDED.right_side,
                    items_known = EXCLUDED.items_known, capabilities = EXCLUDED.capabilities,
                    seeded = EXCLUDED.seeded, ts = EXCLUDED.ts
                """,
                turtleId,
                equipment.left?.let { json.encodeToString(EquippedUpgrade.serializer(), it) },
                equipment.right?.let { json.encodeToString(EquippedUpgrade.serializer(), it) },
                equipment.itemsKnown, equipment.capabilities, equipment.seeded, equipment.ts
            )
        }
        map[turtleId] = equipment
    }

    fun get(turtleId: Int): TurtleEquipment? = map[turtleId]

    private fun load() {
        db.transaction { conn ->
            conn.query("SELECT * FROM turtle_equipment") { rs ->
                @Suppress("UNCHECKED_CAST")
                rs.getInt("turtle_id") to TurtleEquipment(
                    left = rs.getString("left_side")?.let { json.decodeFromString<EquippedUpgrade>(it) },
                    right = rs.getString("right_side")?.let { json.decodeFromString<EquippedUpgrade>(it) },
                    itemsKnown = rs.getBoolean("items_known"),
                    capabilities = (rs.getArray("capabilities").array as Array<String>).toList(),
                    seeded = rs.getBoolean("seeded"),
                    ts = rs.getLong("ts")
                )
            }.toMap(map)
        }
    }
}
