package app.store

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import protocol.ProtocolJson
import protocol.api.TurtleEquipment
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The upgrades each turtle last reported. Saved to disk so the dashboard knows what a turtle can do after a server
 * restart, before the turtle reconnects.
 */
class EquipmentStore(
    private val file: File,
    private val json: Json = Json(ProtocolJson) { prettyPrint = true }
) {
    private val map = ConcurrentHashMap<Int, TurtleEquipment>()

    init {
        loadFromDisk()
    }

    fun put(turtleId: Int, equipment: TurtleEquipment) {
        map[turtleId] = equipment
        saveToDisk() // simple version; later we can debounce/batch
    }

    fun get(turtleId: Int): TurtleEquipment? = map[turtleId]

    private fun loadFromDisk() {
        if (!file.exists()) return
        val text = file.readText()
        if (text.isBlank()) return
        val saved = runCatching { json.decodeFromString<Map<Int, TurtleEquipment>>(text) }.getOrNull() ?: return
        map.putAll(saved)
    }

    private fun saveToDisk() {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString<Map<Int, TurtleEquipment>>(map.toSortedMap()))
    }
}
