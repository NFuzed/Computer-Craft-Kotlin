package app.service

import app.store.BlockPos
import app.store.MapId
import protocol.api.Station
import protocol.api.StationSides

// Where a station is in the world. The pathfinder will route turtles to the standing cell and must never dig the chest.

/** Which world map the station is in. */
val Station.mapId: MapId get() = MapId(world, dimension)

/** The cell a turtle stands in to use the station. */
val Station.standingCell: BlockPos get() = BlockPos(x, y, z)

/** The chest's cell: above, below, or in front of the standing cell along the station's heading. */
val Station.chestCell: BlockPos
    get() = when (side) {
        StationSides.UP -> BlockPos(x, y + 1, z)
        StationSides.DOWN -> BlockPos(x, y - 1, z)
        StationSides.FRONT -> when (heading) {
            "N" -> BlockPos(x, y, z - 1)
            "E" -> BlockPos(x + 1, y, z)
            "S" -> BlockPos(x, y, z + 1)
            "W" -> BlockPos(x - 1, y, z)
            else -> error("Station $stationId has its chest in front but no valid heading ($heading)")
        }

        else -> error("Station $stationId has an unknown side: $side")
    }
