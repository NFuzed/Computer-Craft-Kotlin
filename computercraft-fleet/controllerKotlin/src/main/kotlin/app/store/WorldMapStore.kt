package app.store

import protocol.api.AIR
import protocol.api.KnownBlock
import java.sql.ResultSet

/** Which world map a block belongs to. Turtles report world and dimension from their seed; either may be null. */
data class MapId(val world: String?, val dimension: String?)

data class BlockPos(val x: Int, val y: Int, val z: Int)

/**
 * What the turtles have seen of the world: the last block reported at each cell, per [MapId], in the `world_block`
 * table. Cells nobody has seen aren't stored. The pathfinder will plan over this.
 *
 * Unlike the other stores this one keeps nothing in memory, since the map can grow far larger than the rest; every
 * call goes to the database. A null world or dimension is stored as an empty string, because both are part of the
 * primary key.
 */
class WorldMapStore(private val db: Database) {

    /** Stores [blocks] in the map [id], replacing whatever was known about their cells. */
    fun record(id: MapId, blocks: List<KnownBlock>) {
        if (blocks.isEmpty()) return
        db.transaction { conn ->
            conn.prepareStatement(
                """
                INSERT INTO world_block (world, dimension, y, z, x, name, turtle_id, ts)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (world, dimension, chunk_x, chunk_z, y, z, x) DO UPDATE SET
                    name = EXCLUDED.name, turtle_id = EXCLUDED.turtle_id, ts = EXCLUDED.ts
                """
            ).use { stmt ->
                // a message can report the same cell twice; only the last report should count
                for (b in blocks.associateBy { BlockPos(it.x, it.y, it.z) }.values) {
                    stmt.bind(arrayOf(id.worldKey, id.dimensionKey, b.y, b.z, b.x, b.name, b.turtleId, b.ts))
                        .addBatch()
                }
                stmt.executeBatch()
            }
        }
    }

    fun get(id: MapId, pos: BlockPos): KnownBlock? = db.transaction { conn ->
        conn.query(
            """
            SELECT * FROM world_block
            WHERE world = ? AND dimension = ? AND chunk_x = ? AND chunk_z = ? AND y = ? AND z = ? AND x = ?
            """,
            id.worldKey, id.dimensionKey, pos.x shr 4, pos.z shr 4, pos.y, pos.z, pos.x
        ) { it.toKnownBlock() }.firstOrNull()
    }

    /**
     * Every known block in the box from [min] to [max] (both inclusive), sorted by y, then z, then x. Known air is left
     * out unless [includeAir], since only the pathfinder needs to tell it from cells nobody has seen.
     *
     * It looks up each chunk the box covers by the primary key, so it only reads blocks in those chunks.
     */
    fun region(id: MapId, min: BlockPos, max: BlockPos, includeAir: Boolean = true): List<KnownBlock> =
        db.transaction { conn ->
            conn.query(
                """
                SELECT b.* FROM generate_series(?, ?) AS cx
                CROSS JOIN generate_series(?, ?) AS cz
                JOIN world_block b
                  ON b.world = ? AND b.dimension = ? AND b.chunk_x = cx AND b.chunk_z = cz
                WHERE b.y BETWEEN ? AND ? AND b.z BETWEEN ? AND ? AND b.x BETWEEN ? AND ?
                  AND (? OR b.name <> ?)
                ORDER BY b.y, b.z, b.x
                """,
                min.x shr 4, max.x shr 4, min.z shr 4, max.z shr 4, id.worldKey, id.dimensionKey,
                min.y, max.y, min.z, max.z, min.x, max.x, includeAir, AIR
            ) { it.toKnownBlock() }
        }

    private val MapId.worldKey get() = world ?: ""
    private val MapId.dimensionKey get() = dimension ?: ""

    private fun ResultSet.toKnownBlock() = KnownBlock(
        x = getInt("x"),
        y = getInt("y"),
        z = getInt("z"),
        name = getString("name"),
        turtleId = getIntOrNull("turtle_id"),
        ts = getLong("ts")
    )
}
