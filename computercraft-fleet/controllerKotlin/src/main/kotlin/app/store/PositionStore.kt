package app.store

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import protocol.ProtocolJson
import protocol.api.TurtlePosition
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class PositionStore(
    private val file: File,
    private val json: Json = Json(ProtocolJson) { prettyPrint = true }
) {
    private val map = ConcurrentHashMap<Int, TurtlePosition>()

    init {
        loadFromDisk()
    }

    fun upsert(turtleId: Int, pos: TurtlePosition) {
        map[turtleId] = pos
        saveToDisk() // simple version; later we can debounce/batch
    }

    fun get(turtleId: Int): TurtlePosition? = map[turtleId]

    fun ids(): Set<Int> = map.keys

    private fun loadFromDisk() {
        if (!file.exists()) return
        val text = file.readText()
        if (text.isBlank()) return
        val saved = runCatching { json.decodeFromString<Map<Int, TurtlePosition>>(text) }.getOrNull() ?: return
        map.putAll(saved)
    }

    private fun saveToDisk() {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString<Map<Int, TurtlePosition>>(map.toSortedMap()))
    }
}
