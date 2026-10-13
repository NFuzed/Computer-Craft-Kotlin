package app.store

import protocol.api.Station
import java.sql.ResultSet
import java.util.concurrent.ConcurrentHashMap

/**
 * The stations the dashboard has set up. Kept in memory, since there are few of them and the pathfinder will read them
 * often, and written through to the `station` table. The database assigns station ids.
 */
class StationStore(private val db: Database) {
    private val map = ConcurrentHashMap<Long, Station>()

    init {
        load()
    }

    /** Every station, oldest first. */
    fun all(): List<Station> = map.values.sortedBy { it.stationId }

    fun get(stationId: Long): Station? = map[stationId]

    /** Saves a new station, ignoring its [Station.stationId], and returns it with the id it was given. */
    @Synchronized
    fun insert(station: Station): Station {
        val saved = db.transaction { conn ->
            conn.query(
                """
                INSERT INTO station (name, type, x, y, z, heading, side, world, dimension, ts)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING *
                """,
                station.name, station.type, station.x, station.y, station.z, station.heading, station.side,
                station.world, station.dimension, station.ts
            ) { it.toStation() }.single()
        }
        map[saved.stationId] = saved
        return saved
    }

    /** Replaces the station with the same id. Returns false if there is none. */
    @Synchronized
    fun replace(station: Station): Boolean {
        if (!map.containsKey(station.stationId)) return false
        db.transaction { conn ->
            conn.execute(
                """
                UPDATE station SET name = ?, type = ?, x = ?, y = ?, z = ?, heading = ?, side = ?, world = ?,
                    dimension = ?, ts = ?
                WHERE station_id = ?
                """,
                station.name, station.type, station.x, station.y, station.z, station.heading, station.side,
                station.world, station.dimension, station.ts, station.stationId
            )
        }
        map[station.stationId] = station
        return true
    }

    /** Returns false if there was no such station. */
    @Synchronized
    fun delete(stationId: Long): Boolean {
        if (!map.containsKey(stationId)) return false
        db.transaction { it.execute("DELETE FROM station WHERE station_id = ?", stationId) }
        map.remove(stationId)
        return true
    }

    private fun load() {
        db.transaction { conn ->
            conn.query("SELECT * FROM station") { it.toStation() }.associateByTo(map) { it.stationId }
        }
    }

    private fun ResultSet.toStation() = Station(
        stationId = getLong("station_id"),
        name = getString("name"),
        type = getString("type"),
        x = getInt("x"),
        y = getInt("y"),
        z = getInt("z"),
        heading = getString("heading"),
        side = getString("side"),
        world = getString("world"),
        dimension = getString("dimension"),
        ts = getLong("ts")
    )
}
