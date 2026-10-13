package app.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import protocol.ProtocolJson
import protocol.api.AIR
import protocol.api.KnownBlock
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Which world map a block belongs to. Turtles report world and dimension from their seed; either may be null. */
data class MapId(val world: String?, val dimension: String?)

data class BlockPos(val x: Int, val y: Int, val z: Int)

/**
 * What the turtles have seen of the world: the last block reported at each cell, per [MapId]. Cells nobody has seen
 * aren't stored. The pathfinder will plan over this.
 *
 * Turtles report a few blocks after every move, so unlike the other stores this one doesn't write the file on every
 * change. [record] marks it dirty and [saveIfDirty] writes it; the application calls that every few seconds and on
 * shutdown.
 */
class WorldMapStore(
    private val file: File,
    private val json: Json = ProtocolJson // compact: this file gets big
) {
    private val maps = ConcurrentHashMap<MapId, ConcurrentHashMap<BlockPos, KnownBlock>>()
    private val dirty = AtomicBoolean(false)

    init {
        loadFromDisk()
    }

    /** Stores [blocks] in the map [id], replacing whatever was known about their cells. */
    fun record(id: MapId, blocks: List<KnownBlock>) {
        val map = maps.computeIfAbsent(id) { ConcurrentHashMap() }
        for (block in blocks) {
            map[BlockPos(block.x, block.y, block.z)] = block
        }
        dirty.set(true)
    }

    fun get(id: MapId, pos: BlockPos): KnownBlock? = maps[id]?.get(pos)

    /**
     * Every known block in the box from [min] to [max] (both inclusive), sorted by y, then z, then x. Known air is left
     * out unless [includeAir], since only the pathfinder needs to tell it from cells nobody has seen.
     */
    fun region(id: MapId, min: BlockPos, max: BlockPos, includeAir: Boolean = true): List<KnownBlock> {
        val map = maps[id] ?: return emptyList()
        return map.values
            .filter { it.x in min.x..max.x && it.y in min.y..max.y && it.z in min.z..max.z }
            .filter { includeAir || it.name != AIR }
            .sortedWith(compareBy({ it.y }, { it.z }, { it.x }))
    }

    /** Writes the file if anything changed since the last save. */
    @Synchronized
    fun saveIfDirty() {
        if (!dirty.getAndSet(false)) return
        runCatching { saveToDisk() }.onFailure {
            dirty.set(true) // try again next time
            println("WorldMapStore: can't save ${file.path}: ${it.message}")
        }
    }

    private fun loadFromDisk() {
        if (!file.exists()) return
        val text = file.readText()
        if (text.isBlank()) return
        val saved = runCatching { json.decodeFromString<List<SavedMap>>(text) }.getOrNull() ?: return
        for (map in saved) {
            maps[MapId(map.world, map.dimension)] =
                ConcurrentHashMap(map.blocks.associateBy { BlockPos(it.x, it.y, it.z) })
        }
    }

    private fun saveToDisk() {
        val saved = maps.map { (id, blocks) -> SavedMap(id.world, id.dimension, blocks.values.toList()) }
        file.parentFile?.mkdirs()
        // write a temporary file and move it over the old one, so a crash mid-write doesn't lose the whole map
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(saved))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    @Serializable
    private data class SavedMap(
        val world: String? = null,
        val dimension: String? = null,
        val blocks: List<KnownBlock>
    )
}
