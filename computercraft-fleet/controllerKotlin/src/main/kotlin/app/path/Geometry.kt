package app.path

import app.store.BlockPos

/** Which way a turtle faces, on Minecraft's axes: N = -z, E = +x, S = +z, W = -x. Clockwise order. */
enum class Heading(val delta: BlockPos) {
    N(BlockPos(0, 0, -1)),
    E(BlockPos(1, 0, 0)),
    S(BlockPos(0, 0, 1)),
    W(BlockPos(-1, 0, 0));

    val right: Heading get() = entries[(ordinal + 1) % 4]
    val left: Heading get() = entries[(ordinal + 3) % 4]
}

/** Where a turtle is and which way it faces. */
data class Pose(val pos: BlockPos, val heading: Heading)

/** Every cell from [min] to [max], both inclusive. */
data class Box(val min: BlockPos, val max: BlockPos) {
    operator fun contains(pos: BlockPos) =
        pos.x in min.x..max.x && pos.y in min.y..max.y && pos.z in min.z..max.z
}

operator fun BlockPos.plus(other: BlockPos) = BlockPos(x + other.x, y + other.y, z + other.z)
operator fun BlockPos.minus(other: BlockPos) = BlockPos(x - other.x, y - other.y, z - other.z)
