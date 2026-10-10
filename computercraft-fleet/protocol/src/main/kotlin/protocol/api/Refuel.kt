package protocol.api

import kotlinx.serialization.Serializable

/** Most items a single refuel can burn: one full stack. */
const val MAX_REFUEL_COUNT = 64

/** Sent by the dashboard to make a turtle burn fuel items from its selected slot. */
@Serializable
data class RefuelRequest(
    val count: Int? = null          // 1..64 items; null burns the whole stack
)

/** Returned with 202 once a refuel has been sent to the turtle. Its [refuelId] matches the turtle's [RefuelResult]. */
@Serializable
data class RefuelCommand(
    val refuelId: Long,
    val count: Int?,
    val ts: Long
)

/** What happened when the turtle ran a refuel. */
@Serializable
data class RefuelResult(
    val refuelId: Long,
    val refueled: Boolean,
    val error: String? = null,      // turtle.refuel's reason when refueled is false, e.g. "Items not combustible"
    val fuelAdded: Int? = null,     // null when the turtle has unlimited fuel
    val ts: Long
)
