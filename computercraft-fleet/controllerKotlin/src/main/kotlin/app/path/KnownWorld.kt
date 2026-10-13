package app.path

import app.store.BlockPos
import protocol.api.KnownBlock
import kotlin.math.abs
import kotlin.math.max

/** How far, in columns, an unseen column looks for a seen one to borrow from. */
private const val BORROW_RADIUS = 8

/**
 * The planner's view of what the turtles have seen: [blocks] from the world map, plus a guess for cells nobody has
 * seen, made from what's known above and below them in the same column (or the nearest seen column):
 *
 * - under a known solid block: solid (underground, inside a hill, under a tunnel's ceiling)
 * - between known air above and a known solid block below: solid (the ground under where a turtle passed)
 * - between known air above and below, or above the highest known block when that's air: air (open sky, a cave)
 * - otherwise, or with nothing known nearby: solid below [surfaceGuess], air from it up
 *
 * It leans towards solid, since a wrong guess costs little that way: the turtle is ready to dig a cell that turns
 * out to be air, and just moves. A wrong guess of air makes the turtle stop and the server replan.
 *
 * With [assumeUnseenOpen], every cell nobody has seen counts as open instead, so the planner takes the shortest way
 * through unexplored ground (and the turtle maps it as it goes) rather than steering round guessed rock.
 */
class KnownWorld(
    blocks: Collection<KnownBlock>,
    private val surfaceGuess: Int,
    private val assumeUnseenOpen: Boolean = false
) : WorldView {
    private val names = HashMap<BlockPos, String>(blocks.size * 2)
    private val columns = HashMap<Long, Column>()
    private val borrowed = HashMap<Long, Column?>() // unseen column -> nearest seen one (or none), worked out once

    init {
        blocks.groupBy { columnKey(it.x, it.z) }.forEach { (key, column) ->
            val sorted = column.sortedBy { it.y }
            columns[key] = Column(sorted.map { it.y }.toIntArray(), sorted.map { BlockRules.isOpen(it.name) })
        }
        for (block in blocks) names[BlockPos(block.x, block.y, block.z)] = block.name
    }

    override fun blockAt(pos: BlockPos): String? = names[pos]

    override fun probablySolid(pos: BlockPos): Boolean {
        if (assumeUnseenOpen) return false
        val column = column(pos.x, pos.z) ?: return pos.y < surfaceGuess
        val found = column.ys.binarySearch(pos.y)
        if (found >= 0) return !column.open[found] // a borrowed column may know this height
        val above = (-found - 1).takeIf { it < column.ys.size }
        val below = (-found - 2).takeIf { it >= 0 }
        return when {
            above != null && !column.open[above] -> true
            above != null -> below?.let { !column.open[it] } ?: (pos.y < surfaceGuess)
            below != null && column.open[below] -> false
            else -> pos.y < surfaceGuess
        }
    }

    /** The column's known blocks, or the nearest seen column's within [BORROW_RADIUS], or null. */
    private fun column(x: Int, z: Int): Column? {
        val key = columnKey(x, z)
        columns[key]?.let { return it }
        return borrowed.getOrPut(key) { nearestColumn(x, z) }
    }

    /** Searches rings of growing distance; on a tie the first found in a fixed order wins, so results repeat. */
    private fun nearestColumn(x: Int, z: Int): Column? {
        if (columns.isEmpty()) return null
        for (d in 1..BORROW_RADIUS) {
            for (dx in -d..d) {
                for (dz in -d..d) {
                    if (max(abs(dx), abs(dz)) != d) continue // only the ring's edge
                    columns[columnKey(x + dx, z + dz)]?.let { return it }
                }
            }
        }
        return null
    }

    private class Column(val ys: IntArray, val open: List<Boolean>)

    private fun columnKey(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xffffffffL)
}
