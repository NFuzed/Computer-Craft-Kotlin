package protocol.messages

import kotlinx.serialization.Serializable

@Serializable
data class Hello(
    val v: Int,
    val type: String,
    val turtleId: Int,
    val label: String? = null,
    val capabilities: List<String> = emptyList(),
    val ts: Double? = null
)
