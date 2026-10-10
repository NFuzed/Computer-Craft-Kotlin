package app.store

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import protocol.ProtocolJson
import protocol.api.PendingLabel
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Renames waiting to be picked up by a turtle, at most one per turtle (a newer rename replaces an older one).
 * Saved to disk so a rename survives a server restart before the turtle collects it.
 */
class LabelStore(
    private val file: File,
    private val json: Json = Json(ProtocolJson) { prettyPrint = true }
) {
    private val map = ConcurrentHashMap<Int, PendingLabel>()

    init {
        loadFromDisk()
    }

    fun put(turtleId: Int, pending: PendingLabel) {
        map[turtleId] = pending
        saveToDisk()
    }

    fun get(turtleId: Int): PendingLabel? = map[turtleId]

    fun ids(): Set<Int> = map.keys

    /** Removes the turtle's rename only if it is for [label], so a turtle still using an old label can't clear it. */
    fun acknowledge(turtleId: Int, label: String?): Boolean {
        val current = map[turtleId] ?: return false
        if (current.label != label || !map.remove(turtleId, current)) return false
        saveToDisk()
        return true
    }

    private fun loadFromDisk() {
        if (!file.exists()) return
        val text = file.readText()
        if (text.isBlank()) return
        val saved = runCatching { json.decodeFromString<Map<Int, PendingLabel>>(text) }.getOrNull() ?: return
        map.putAll(saved)
    }

    private fun saveToDisk() {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString<Map<Int, PendingLabel>>(map.toSortedMap()))
    }
}
