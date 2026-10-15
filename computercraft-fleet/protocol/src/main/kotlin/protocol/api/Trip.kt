package protocol.api

import kotlinx.serialization.Serializable

/** Values of [Trip.status]. */
object TripStatuses {
    const val MOVING = "moving"         // following a path; replanned when something is in the way
    const val WAITING = "waiting"       // for other turtles to move out of the way (Trip.waitingFor) before setting off
    const val ARRIVED = "arrived"
    const val FAILED = "failed"         // see Trip.error
    const val CANCELLED = "cancelled"

    val ENDED = setOf(ARRIVED, FAILED, CANCELLED)
    val IN_PROGRESS = setOf(MOVING, WAITING)
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
 * is and plans a new path from where the turtle stopped. When idle turtles are in the way, it waits for them to move
 * aside first.
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
    val waitingFor: List<Int> = emptyList(), // turtles still moving out of the way, while waiting
    val makingWayFor: Int? = null,  // the turtle this one is moving out of the way of; the goal is anywhere off its path
    val started: Long,              // unix seconds
    val updated: Long
)
