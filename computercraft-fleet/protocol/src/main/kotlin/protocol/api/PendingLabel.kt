package protocol.api

import kotlinx.serialization.Serializable

/** ComputerCraft truncates computer labels to this many characters. */
const val MAX_LABEL_LENGTH = 32

/** Sent by the dashboard to rename a turtle. A null or blank [label] clears the turtle's label. */
@Serializable
data class LabelRequest(
    val label: String? = null
)

/**
 * A rename waiting to be delivered. The server pushes it to the turtle over /ws/turtle; the turtle applies it
 * with os.setComputerLabel and replies with a label_update, and a label_update or hello carrying the same [label]
 * clears it.
 */
@Serializable
data class PendingLabel(
    val label: String? = null,      // null clears the label
    val ts: Long
)
