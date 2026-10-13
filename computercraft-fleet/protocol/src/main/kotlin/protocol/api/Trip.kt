package protocol.api

import kotlinx.serialization.Serializable

/** Values of [Trip.status]. */
object TripStatuses {
    const val MOVING = "moving"         // following a path; replanned when something is in the way
    const val ARRIVED = "arrived"
    const val FAILED = "failed"         // see Trip.error
    const val CANCELLED = "cancelled"

    val ENDED = setOf(ARRIVED, FAILED, CANCELLED)
}

/** Sent by the dashboard to send a turtle somewhere. [heading] ("N","E","S","W") is which way it ends up facing. */
@Serializable
data class GotoRequest(
    val x: Int,
    val y: Int,
    val z: Int,
    val heading: String? = null
)

/**
 * A turtle's journey to a goal, which may take several paths: when something is in the way the server learns what it
 * is and plans a new path from where the turtle stopped.
 */
@Serializable
data class Trip(
    val tripId: Long,
    val x: Int,
    val y: Int,
    val z: Int,
    val heading: String? = null,
    val status: String,             // one of TripStatuses
    val pathId: Long,               // the path the turtle is following, or followed last
    val steps: Int,                 // steps in that path
    val fuel: Int,                  // fuel that path needs
    val replans: Int,               // how many times a new path was planned after the first
    val error: String? = null,      // why it failed
    val started: Long,              // unix seconds
    val updated: Long
)
