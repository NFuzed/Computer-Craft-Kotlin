package protocol.messages

import kotlinx.serialization.Serializable
import protocol.Protocol

/** Sent by a turtle once after boot so the server knows it exists. */
@Serializable
data class RegisterRequest(
    val v: Int = Protocol.VERSION,
    val type: String = "register",
    val turtleId: Int,
    val label: String? = null       // os.getComputerLabel(), null if unset
)
