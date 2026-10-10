package protocol.api

import kotlinx.serialization.Serializable

/** Valid turtle headings. N = -z, E = +x, S = +z, W = -x (Minecraft's axes). */
val HEADINGS = setOf("N", "E", "S", "W")

/**
 * Sent by the dashboard (or, later, a commander turtle placing a new turtle) to tell a turtle
 * where it is. Turtles have no GPS, so this is the starting point for their dead reckoning.
 */
@Serializable
data class SeedRequest(
    val x: Int,
    val y: Int,
    val z: Int,
    val heading: String,            // "N","E","S","W"
    val world: String? = null,
    val dimension: String? = null,
    val source: String = "dashboard" // who seeded it, e.g. "dashboard" or "commander:12"
)

/**
 * A seed waiting to be delivered. The server pushes it to the turtle over /ws/turtle
 * and clears it once the turtle reports a position carrying the same [seedId].
 */
@Serializable
data class PositionSeed(
    val seedId: Long,
    val x: Int,
    val y: Int,
    val z: Int,
    val heading: String,
    val world: String? = null,
    val dimension: String? = null,
    val source: String,
    val ts: Long
)
