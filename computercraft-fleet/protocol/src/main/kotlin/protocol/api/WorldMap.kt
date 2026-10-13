package protocol.api

import kotlinx.serialization.Serializable

/** Block name the turtles report for an empty cell (turtle.inspect found nothing). */
const val AIR = "minecraft:air"

/** Most blocks one block_observations message may carry. */
const val MAX_OBSERVED_BLOCKS = 64

/** Largest span, in blocks, a /api/map/blocks query may cover along each axis. */
const val MAX_MAP_QUERY_SPAN = 256

/** One cell of the world map: what a turtle last saw there, and when. */
@Serializable
data class KnownBlock(
    val x: Int,
    val y: Int,
    val z: Int,
    val name: String,               // block id, e.g. "minecraft:stone", or AIR
    val turtleId: Int? = null,      // the turtle that saw it
    val ts: Long                    // unix seconds
)

/** The known blocks inside the box a /api/map/blocks query asked for. Cells nobody has seen are left out. */
@Serializable
data class MapRegion(
    val world: String? = null,
    val dimension: String? = null,
    val blocks: List<KnownBlock>
)
