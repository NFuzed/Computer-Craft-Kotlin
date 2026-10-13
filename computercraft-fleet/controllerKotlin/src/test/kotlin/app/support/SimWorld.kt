package app.support

import app.path.BlockRules
import app.store.BlockPos
import protocol.api.AIR

/** What a simulated turtle looks like to turtle.inspect, as a real one does. */
const val TURTLE_BLOCK = "computercraft:turtle_normal"

/**
 * The real world, as far as [SimTurtle]s are concerned: every cell has a block, turtles get in each other's way, and
 * digging changes it. The server only learns about it from what the turtles report, as in Minecraft.
 *
 * Drawn like GridWorld: each `y=N` line starts a layer, each row after it is one z (from 0) and each character one x
 * (from 0). `.` air, `#` stone, `~` water, `C` chest, `B` bedrock. Cells outside the drawing are [outside].
 */
class SimWorld(blocks: Map<BlockPos, String>, private val outside: String = "minecraft:stone") {
    private val blocks = HashMap(blocks)
    private val turtles = HashMap<Int, BlockPos>()

    /** What's in the cell: a turtle, or its block. */
    @Synchronized
    fun blockAt(pos: BlockPos): String = if (pos in turtles.values) TURTLE_BLOCK else blocks[pos] ?: outside

    /** The block itself, ignoring turtles. */
    @Synchronized
    fun terrainAt(pos: BlockPos): String = blocks[pos] ?: outside

    @Synchronized
    fun place(turtleId: Int, pos: BlockPos) {
        check(BlockRules.isOpen(blockAt(pos))) { "Turtle $turtleId can't be placed in ${blockAt(pos)} at $pos" }
        turtles[turtleId] = pos
    }

    @Synchronized
    fun remove(turtleId: Int) {
        turtles.remove(turtleId)
    }

    /** Moves the turtle into [to] if it's open. Returns false if anything is there. */
    @Synchronized
    fun move(turtleId: Int, to: BlockPos): Boolean {
        if (!BlockRules.isOpen(blockAt(to))) return false
        turtles[turtleId] = to
        return true
    }

    /** Digs the block in [pos], like turtle.dig: false if there's nothing to dig, or it can't be dug. */
    @Synchronized
    fun dig(pos: BlockPos): Boolean {
        val block = blockAt(pos)
        if (BlockRules.isOpen(block) || BlockRules.isProtected(block)) return false
        blocks[pos] = AIR
        return true
    }

    companion object {
        private val NAMES = mapOf(
            '.' to AIR,
            '#' to "minecraft:stone",
            '~' to "minecraft:water",
            'C' to "minecraft:chest",
            'B' to "minecraft:bedrock"
        )

        fun parse(drawing: String, outside: String = "minecraft:stone"): SimWorld {
            val blocks = HashMap<BlockPos, String>()
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
                    blocks[BlockPos(x, layer, z)] = NAMES[c] ?: error("Unknown character '$c' in the drawing")
                }
                z++
            }
            return SimWorld(blocks, outside)
        }
    }
}
