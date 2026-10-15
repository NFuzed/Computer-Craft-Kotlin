package protocol.api

import kotlinx.serialization.Serializable

/** Values of [Zone.type]: what a zone is for. */
object ZoneTypes {
    const val NO_DIG = "nodig"      // planned routes never dig a block in it
    const val QUARRY = "quarry"     // an area for turtles to mine; only stored for now

    val ALL = setOf(NO_DIG, QUARRY)
}

/** Zone names are shown on the dashboard; same limit as station names. */
const val MAX_ZONE_NAME_LENGTH = 32

/**
 * Sent by the dashboard to create or replace a zone: a box of blocks, from min to max inclusive on each axis. X and Z
 * are always bounded; a null [minY] or [maxY] leaves the box open downwards or upwards. Zones are shared by the whole
 * fleet and apply in their world and dimension.
 */
@Serializable
data class ZoneRequest(
    val name: String,
    val type: String,               // one of ZoneTypes
    val minX: Int,
    val maxX: Int,
    val minZ: Int,
    val maxZ: Int,
    val minY: Int? = null,
    val maxY: Int? = null,
    val world: String? = null,
    val dimension: String? = null
)

/** A no-dig zone or a quarry. */
@Serializable
data class Zone(
    val zoneId: Long,
    val name: String,
    val type: String,
    val minX: Int,
    val maxX: Int,
    val minZ: Int,
    val maxZ: Int,
    val minY: Int? = null,
    val maxY: Int? = null,
    val world: String? = null,
    val dimension: String? = null,
    val ts: Long                    // when it was created or last replaced, unix seconds
)
