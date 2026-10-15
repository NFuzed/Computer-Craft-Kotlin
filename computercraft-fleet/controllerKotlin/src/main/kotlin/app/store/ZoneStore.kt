package app.store

import protocol.api.Zone
import java.sql.ResultSet
import java.util.concurrent.ConcurrentHashMap

/**
 * The zones the dashboard has marked out. Kept in memory, since there are few of them and every trip reads them, and
 * written through to the `zone` table. The database assigns zone ids.
 */
class ZoneStore(private val db: Database) {
    private val map = ConcurrentHashMap<Long, Zone>()

    init {
        load()
    }

    /** Every zone, oldest first. */
    fun all(): List<Zone> = map.values.sortedBy { it.zoneId }

    fun get(zoneId: Long): Zone? = map[zoneId]

    /** Saves a new zone, ignoring its [Zone.zoneId], and returns it with the id it was given. */
    @Synchronized
    fun insert(zone: Zone): Zone {
        val saved = db.transaction { conn ->
            conn.query(
                """
                INSERT INTO zone (name, type, min_x, max_x, min_z, max_z, min_y, max_y, world, dimension, ts)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING *
                """,
                zone.name, zone.type, zone.minX, zone.maxX, zone.minZ, zone.maxZ, zone.minY,
                zone.maxY, zone.world, zone.dimension, zone.ts
            ) { it.toZone() }.single()
        }
        map[saved.zoneId] = saved
        return saved
    }

    /** Replaces the zone with the same id. Returns false if there is none. */
    @Synchronized
    fun replace(zone: Zone): Boolean {
        if (!map.containsKey(zone.zoneId)) return false
        db.transaction { conn ->
            conn.execute(
                """
                UPDATE zone SET name = ?, type = ?, min_x = ?, max_x = ?, min_z = ?, max_z = ?, min_y = ?, max_y = ?,
                    world = ?, dimension = ?, ts = ?
                WHERE zone_id = ?
                """,
                zone.name, zone.type, zone.minX, zone.maxX, zone.minZ, zone.maxZ, zone.minY,
                zone.maxY, zone.world, zone.dimension, zone.ts, zone.zoneId
            )
        }
        map[zone.zoneId] = zone
        return true
    }

    /** Returns false if there was no such zone. */
    @Synchronized
    fun delete(zoneId: Long): Boolean {
        if (!map.containsKey(zoneId)) return false
        db.transaction { it.execute("DELETE FROM zone WHERE zone_id = ?", zoneId) }
        map.remove(zoneId)
        return true
    }

    private fun load() {
        db.transaction { conn ->
            conn.query("SELECT * FROM zone") { it.toZone() }.associateByTo(map) { it.zoneId }
        }
    }

    private fun ResultSet.toZone() = Zone(
        zoneId = getLong("zone_id"),
        name = getString("name"),
        type = getString("type"),
        minX = getInt("min_x"),
        maxX = getInt("max_x"),
        minZ = getInt("min_z"),
        maxZ = getInt("max_z"),
        minY = getIntOrNull("min_y"),
        maxY = getIntOrNull("max_y"),
        world = getString("world"),
        dimension = getString("dimension"),
        ts = getLong("ts")
    )
}
