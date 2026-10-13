package app.store

import protocol.api.PositionSeed
import java.util.concurrent.ConcurrentHashMap

/**
 * Seeds waiting to be picked up by a turtle, at most one per turtle (a newer seed replaces an older one).
 * Kept in memory and written through to the `pending_seed` table, so a seed survives a server restart before the
 * turtle collects it.
 */
class SeedStore(private val db: Database) {
    private val map = ConcurrentHashMap<Int, PositionSeed>()

    init {
        load()
    }

    @Synchronized
    fun put(turtleId: Int, seed: PositionSeed) {
        db.transaction {
            it.execute(
                """
                INSERT INTO pending_seed (turtle_id, seed_id, x, y, z, heading, world, dimension, source, ts)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (turtle_id) DO UPDATE SET
                    seed_id = EXCLUDED.seed_id, x = EXCLUDED.x, y = EXCLUDED.y, z = EXCLUDED.z,
                    heading = EXCLUDED.heading, world = EXCLUDED.world, dimension = EXCLUDED.dimension,
                    source = EXCLUDED.source, ts = EXCLUDED.ts
                """,
                turtleId, seed.seedId, seed.x, seed.y, seed.z, seed.heading, seed.world, seed.dimension, seed.source,
                seed.ts
            )
        }
        map[turtleId] = seed
    }

    fun get(turtleId: Int): PositionSeed? = map[turtleId]

    fun ids(): Set<Int> = map.keys

    /** Removes the turtle's seed only if it is still [seedId], so an ack for an old seed can't clear a newer one. */
    @Synchronized
    fun acknowledge(turtleId: Int, seedId: Long): Boolean {
        val current = map[turtleId] ?: return false
        if (current.seedId != seedId) return false
        db.transaction { it.execute("DELETE FROM pending_seed WHERE turtle_id = ? AND seed_id = ?", turtleId, seedId) }
        map.remove(turtleId)
        return true
    }

    private fun load() {
        db.transaction { conn ->
            conn.query("SELECT * FROM pending_seed") { rs ->
                rs.getInt("turtle_id") to PositionSeed(
                    seedId = rs.getLong("seed_id"),
                    x = rs.getInt("x"),
                    y = rs.getInt("y"),
                    z = rs.getInt("z"),
                    heading = rs.getString("heading"),
                    world = rs.getString("world"),
                    dimension = rs.getString("dimension"),
                    source = rs.getString("source"),
                    ts = rs.getLong("ts")
                )
            }.toMap(map)
        }
    }
}
