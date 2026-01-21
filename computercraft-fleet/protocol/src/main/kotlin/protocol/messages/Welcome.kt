package protocol.messages

import kotlinx.serialization.Serializable

@Serializable
data class Welcome(
    val v: Int = 1,
    val type: String = "welcome",
    val sessionId: String,
    val serverTime: Long,
    val heartbeatSec: Int = 5
)
