package app.store

import protocol.api.TurtlePosition
import java.util.concurrent.ConcurrentHashMap

/** The last position each turtle reported. Kept in memory and written through to the `turtle_position` table. */
class PositionStore(private val db: Database) {
    private val map = ConcurrentHashMap<Int, TurtlePosition>()

    init {
        load()
    }

    fun upsert(turtleId: Int, pos: TurtlePosition) {
        db.transaction {
            it.execute(
                """
                INSERT INTO turtle_position (turtle_id, x, y, z, heading, world, dimension, ts)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (turtle_id) DO UPDATE SET
                    x = EXCLUDED.x, y = EXCLUDED.y, z = EXCLUDED.z, heading = EXCLUDED.heading,
                    world = EXCLUDED.world, dimension = EXCLUDED.dimension, ts = EXCLUDED.ts
                """,
                turtleId, pos.x, pos.y, pos.z, pos.heading, pos.world, pos.dimension, pos.ts
            )
        }
        map[turtleId] = pos
    }

    fun get(turtleId: Int): TurtlePosition? = map[turtleId]

    fun ids(): Set<Int> = map.keys

    private fun load() {
        db.transaction { conn ->
            conn.query("SELECT * FROM turtle_position") { rs ->
                rs.getInt("turtle_id") to TurtlePosition(
                    x = rs.getInt("x"),
                    y = rs.getInt("y"),
                    z = rs.getInt("z"),
                    heading = rs.getString("heading"),
                    world = rs.getString("world"),
                    dimension = rs.getString("dimension"),
                    ts = rs.getLong("ts")
                )
            }.toMap(map)
        }
    }
}
