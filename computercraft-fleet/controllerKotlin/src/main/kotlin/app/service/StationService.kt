package app.service

import app.store.BlockPos
import app.store.StationStore
import protocol.api.*

/** What creating or replacing a station came to. */
sealed interface StationResult {
    data class Saved(val station: Station) : StationResult
    data class Invalid(val error: String) : StationResult      // the request itself is wrong
    data class Overlaps(val error: String) : StationResult     // it would share a cell with another station
    data object NotFound : StationResult
}

/**
 * The stations turtles go to for fuel, to drop items off and to pick them up. Checks requests from the dashboard and
 * keeps stations from overlapping: a station's standing cell can't be another station's standing cell or chest, so a
 * turtle using one station never blocks another. Two stations may share a chest, e.g. one turtle above it and one
 * beside it.
 */
class StationService(
    private val stations: StationStore,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    /** Every station, or only those of [type]. */
    fun all(type: String? = null): List<Station> = stations.all().filter { type == null || it.type == type }

    fun get(stationId: Long): Station? = stations.get(stationId)

    // Synchronized so the overlap check and the save can't interleave with another request's
    @Synchronized
    fun create(request: StationRequest): StationResult {
        val station = request.toStation(stationId = 0)
        requestError(request)?.let { return StationResult.Invalid(it) }
        overlapError(station)?.let { return StationResult.Overlaps(it) }
        return StationResult.Saved(stations.insert(station))
    }

    @Synchronized
    fun replace(stationId: Long, request: StationRequest): StationResult {
        if (stations.get(stationId) == null) return StationResult.NotFound
        val station = request.toStation(stationId)
        requestError(request)?.let { return StationResult.Invalid(it) }
        overlapError(station)?.let { return StationResult.Overlaps(it) }
        stations.replace(station)
        return StationResult.Saved(station)
    }

    /** Returns false if there was no such station. */
    @Synchronized
    fun delete(stationId: Long): Boolean = stations.delete(stationId)

    private fun StationRequest.toStation(stationId: Long) = Station(
        stationId = stationId,
        name = name.trim(),
        type = type,
        x = x,
        y = y,
        z = z,
        heading = heading,
        side = side,
        world = world,
        dimension = dimension,
        ts = clock()
    )

    /** Why [request] can't be a station, or null if it's fine. */
    private fun requestError(request: StationRequest): String? {
        val name = request.name.trim()
        return when {
            name.isEmpty() -> "name can't be blank"
            name.length > MAX_STATION_NAME_LENGTH ->
                "name must be at most $MAX_STATION_NAME_LENGTH characters, got ${name.length}"

            name.any { it !in ' '..'~' } -> "name may only contain printable ASCII characters"
            request.type !in StationTypes.ALL ->
                "type must be one of ${StationTypes.ALL.joinToString()}, got '${request.type}'"

            request.side !in StationSides.ALL ->
                "side must be one of ${StationSides.ALL.joinToString()}, got '${request.side}'"

            request.heading != null && request.heading !in HEADINGS ->
                "heading must be one of ${HEADINGS.joinToString()}, got '${request.heading}'"

            request.side == StationSides.FRONT && request.heading == null ->
                "heading is required when the chest is in front, so the turtle knows which way to face"

            else -> null
        }
    }

    /** Why [station] would overlap another station, or null if it doesn't. */
    private fun overlapError(station: Station): String? {
        val others = stations.all().filter { it.stationId != station.stationId && it.mapId == station.mapId }
        for (other in others) {
            if (station.standingCell == other.standingCell) {
                return "Station ${other.stationId} (${other.name}) already stands at ${station.standingCell.text}"
            }
            if (station.standingCell == other.chestCell) {
                return "${station.standingCell.text} is the chest of station ${other.stationId} (${other.name})"
            }
            if (station.chestCell == other.standingCell) {
                return "The chest at ${station.chestCell.text} is where station ${other.stationId} " +
                        "(${other.name}) stands"
            }
        }
        return null
    }

    private val BlockPos.text get() = "$x $y $z"
}
