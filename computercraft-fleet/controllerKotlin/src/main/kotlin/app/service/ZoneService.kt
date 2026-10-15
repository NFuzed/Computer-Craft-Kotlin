package app.service

import app.path.Box
import app.store.BlockPos
import app.store.MapId
import app.store.ZoneStore
import protocol.api.MAX_ZONE_NAME_LENGTH
import protocol.api.Zone
import protocol.api.ZoneRequest
import protocol.api.ZoneTypes

/** What creating or replacing a zone came to. */
sealed interface ZoneResult {
    data class Saved(val zone: Zone) : ZoneResult
    data class Invalid(val error: String) : ZoneResult         // the request itself is wrong
    data object NotFound : ZoneResult
}

/** Which world map the zone is in. */
val Zone.mapId: MapId get() = MapId(world, dimension)

/** The zone's blocks, with an open Y bound reaching the end of the world that way. */
val Zone.box: Box
    get() = Box(BlockPos(minX, minY ?: Int.MIN_VALUE, minZ), BlockPos(maxX, maxY ?: Int.MAX_VALUE, maxZ))

/**
 * Boxes the dashboard marks out: no-dig zones, which trips plan around without digging, and quarries, which are only
 * stored for now. Zones may overlap each other and stations.
 */
class ZoneService(
    private val zones: ZoneStore,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    /** Every zone, or only those of [type]. */
    fun all(type: String? = null): List<Zone> = zones.all().filter { type == null || it.type == type }

    fun get(zoneId: Long): Zone? = zones.get(zoneId)

    @Synchronized
    fun create(request: ZoneRequest): ZoneResult {
        requestError(request)?.let { return ZoneResult.Invalid(it) }
        return ZoneResult.Saved(zones.insert(request.toZone(zoneId = 0)))
    }

    @Synchronized
    fun replace(zoneId: Long, request: ZoneRequest): ZoneResult {
        if (zones.get(zoneId) == null) return ZoneResult.NotFound
        requestError(request)?.let { return ZoneResult.Invalid(it) }
        val zone = request.toZone(zoneId)
        zones.replace(zone)
        return ZoneResult.Saved(zone)
    }

    /** Returns false if there was no such zone. */
    @Synchronized
    fun delete(zoneId: Long): Boolean = zones.delete(zoneId)

    private fun ZoneRequest.toZone(zoneId: Long) = Zone(
        zoneId = zoneId,
        name = name.trim(),
        type = type,
        minX = minX,
        maxX = maxX,
        minZ = minZ,
        maxZ = maxZ,
        minY = minY,
        maxY = maxY,
        world = world,
        dimension = dimension,
        ts = clock()
    )

    /** Why [request] can't be a zone, or null if it's fine. */
    private fun requestError(request: ZoneRequest): String? {
        val name = request.name.trim()
        val minY = request.minY
        val maxY = request.maxY
        return when {
            name.isEmpty() -> "name can't be blank"
            name.length > MAX_ZONE_NAME_LENGTH ->
                "name must be at most $MAX_ZONE_NAME_LENGTH characters, got ${name.length}"

            name.any { it !in ' '..'~' } -> "name may only contain printable ASCII characters"
            request.type !in ZoneTypes.ALL ->
                "type must be one of ${ZoneTypes.ALL.joinToString()}, got '${request.type}'"

            request.minX > request.maxX -> "minX (${request.minX}) must not be more than maxX (${request.maxX})"
            request.minZ > request.maxZ -> "minZ (${request.minZ}) must not be more than maxZ (${request.maxZ})"
            minY != null && maxY != null && minY > maxY -> "minY ($minY) must not be more than maxY ($maxY)"
            else -> null
        }
    }
}
