package app.path

import app.store.BlockPos
import protocol.api.AIR

/**
 * A small world for pathfinding tests, drawn as text. Each `y=N` line starts a layer; each row after it is one z
 * (from 0), and each character one x (from 0):
 *
 * ```
 * y=0
 * .#.
 * ...
 * ```
 *
 * `.` air, `#` stone, `~` water, `C` chest, `B` bedrock, `?` (or anything outside the drawing) nobody has seen it.
 * [probablySolid] says what the planner should assume about unseen cells.
 */
class GridWorld(
    private val blocks: Map<BlockPos, String>,
    private val unseen: Set<BlockPos>,
    private val probablySolid: (BlockPos) -> Boolean = { false }
) : WorldView {

    override fun blockAt(pos: BlockPos): String? = blocks[pos]

    override fun probablySolid(pos: BlockPos): Boolean = probablySolid.invoke(pos)

    /** The smallest box around everything drawn, seen or not. */
    val bounds: Box
        get() {
            val all = blocks.keys + unseen
            return Box(
                BlockPos(all.minOf { it.x }, all.minOf { it.y }, all.minOf { it.z }),
                BlockPos(all.maxOf { it.x }, all.maxOf { it.y }, all.maxOf { it.z })
            )
        }

    fun withProbablySolid(probablySolid: (BlockPos) -> Boolean) = GridWorld(blocks, unseen, probablySolid)

    companion object {
        private val NAMES = mapOf(
            '.' to AIR,
            '#' to "minecraft:stone",
            '~' to "minecraft:water",
            'C' to "minecraft:chest",
            'B' to "minecraft:bedrock"
        )

        fun parse(drawing: String): GridWorld {
            val blocks = mutableMapOf<BlockPos, String>()
            val unseen = mutableSetOf<BlockPos>()
            var y: Int? = null
            var z = 0
            for (line in drawing.trimIndent().lines().map { it.trim() }.filter { it.isNotEmpty() }) {
                if (line.startsWith("y=")) {
                    y = line.removePrefix("y=").toInt()
                    z = 0
                    continue
                }
                val layer = y ?: error("Start the drawing with a y=N line")
                line.forEachIndexed { x, c ->
                    val pos = BlockPos(x, layer, z)
                    when (c) {
                        '?' -> unseen += pos
                        else -> blocks[pos] = NAMES[c] ?: error("Unknown character '$c' in the drawing")
                    }
                }
                z++
            }
            return GridWorld(blocks, unseen)
        }
    }
}
