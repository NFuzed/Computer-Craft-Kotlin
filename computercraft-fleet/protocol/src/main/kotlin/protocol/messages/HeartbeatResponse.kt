package protocol.messages

import kotlinx.serialization.Serializable
import protocol.Protocol
import protocol.api.PositionSeed

/** Server reply to a heartbeat. Carries work for the turtle, currently just a pending position seed. */
@Serializable
data class HeartbeatResponse(
    val v: Int = Protocol.VERSION,
    val type: String = "heartbeat_ack",
    val seed: PositionSeed? = null
)
