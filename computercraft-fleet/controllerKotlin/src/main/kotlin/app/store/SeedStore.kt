package app.store

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import protocol.ProtocolJson
import protocol.api.PositionSeed
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Seeds waiting to be picked up by a turtle, at most one per turtle (a newer seed replaces an older one).
 * Saved to disk so a seed survives a server restart before the turtle collects it.
 */
class SeedStore(
    private val file: File,
    private val json: Json = Json(ProtocolJson) { prettyPrint = true }
) {
    private val map = ConcurrentHashMap<Int, PositionSeed>()

    init {
        loadFromDisk()
    }

    fun put(turtleId: Int, seed: PositionSeed) {
        map[turtleId] = seed
        saveToDisk()
    }

    fun get(turtleId: Int): PositionSeed? = map[turtleId]

    fun ids(): Set<Int> = map.keys

    /** Removes the turtle's seed only if it is still [seedId], so an ack for an old seed can't clear a newer one. */
    fun acknowledge(turtleId: Int, seedId: Long): Boolean {
        val current = map[turtleId] ?: return false
        if (current.seedId != seedId || !map.remove(turtleId, current)) return false
        saveToDisk()
        return true
    }

    private fun loadFromDisk() {
        if (!file.exists()) return
        val text = file.readText()
        if (text.isBlank()) return
        val saved = runCatching { json.decodeFromString<Map<Int, PositionSeed>>(text) }.getOrNull() ?: return
        map.putAll(saved)
    }

    private fun saveToDisk() {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString<Map<Int, PositionSeed>>(map.toSortedMap()))
    }
}
