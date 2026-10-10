package app.store

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import protocol.ProtocolJson
import protocol.api.TurtleInventory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The last inventory each turtle reported. Saved to disk so the dashboard can still show it after a server restart,
 * before the turtle reconnects.
 */
class InventoryStore(
    private val file: File,
    private val json: Json = Json(ProtocolJson) { prettyPrint = true }
) {
    private val map = ConcurrentHashMap<Int, TurtleInventory>()

    init {
        loadFromDisk()
    }

    fun put(turtleId: Int, inventory: TurtleInventory) {
        map[turtleId] = inventory
        saveToDisk() // simple version; later we can debounce/batch
    }

    fun get(turtleId: Int): TurtleInventory? = map[turtleId]

    private fun loadFromDisk() {
        if (!file.exists()) return
        val text = file.readText()
        if (text.isBlank()) return
        val saved = runCatching { json.decodeFromString<Map<Int, TurtleInventory>>(text) }.getOrNull() ?: return
        map.putAll(saved)
    }

    private fun saveToDisk() {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString<Map<Int, TurtleInventory>>(map.toSortedMap()))
    }
}
