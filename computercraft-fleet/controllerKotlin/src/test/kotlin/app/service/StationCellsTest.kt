package app.service

import app.store.BlockPos
import protocol.api.Station
import protocol.api.StationSides
import protocol.api.StationTypes
import kotlin.test.Test
import kotlin.test.assertEquals

private fun at(side: String, heading: String? = null) = Station(
    stationId = 1, name = "S", type = StationTypes.FUEL, x = 10, y = 64, z = 20,
    heading = heading, side = side, ts = 0
)

class StationCellsTest {

    @Test
    fun `the standing cell is the station's position`() {
        assertEquals(BlockPos(10, 64, 20), at(StationSides.UP).standingCell)
    }

    @Test
    fun `the chest is above, below, or in front along the heading`() {
        assertEquals(BlockPos(10, 65, 20), at(StationSides.UP).chestCell)
        assertEquals(BlockPos(10, 63, 20), at(StationSides.DOWN).chestCell)
        // Minecraft's axes: N = -z, E = +x, S = +z, W = -x
        assertEquals(BlockPos(10, 64, 19), at(StationSides.FRONT, "N").chestCell)
        assertEquals(BlockPos(11, 64, 20), at(StationSides.FRONT, "E").chestCell)
        assertEquals(BlockPos(10, 64, 21), at(StationSides.FRONT, "S").chestCell)
        assertEquals(BlockPos(9, 64, 20), at(StationSides.FRONT, "W").chestCell)
    }
}
