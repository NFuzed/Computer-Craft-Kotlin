package app.store

import app.support.withDatabase
import protocol.api.Station
import protocol.api.StationSides
import protocol.api.StationTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun station(name: String = "Fuel", x: Int = 0) = Station(
    stationId = 0, // assigned by the store
    name = name, type = StationTypes.FUEL, x = x, y = 64, z = 0,
    heading = "N", side = StationSides.FRONT, world = "w", dimension = "overworld", ts = 100
)

class StationStoreTest {

    @Test
    fun `stations survive a restart`() = withDatabase { db ->
        val kept = StationStore(db).insert(station("Kept", x = 0))
        val replaced = StationStore(db).insert(station("Before", x = 5))
        val deleted = StationStore(db).insert(station("Deleted", x = 10))
        StationStore(db).replace(replaced.copy(name = "After"))
        StationStore(db).delete(deleted.stationId)

        val reloaded = StationStore(db)

        assertEquals(listOf(kept, replaced.copy(name = "After")), reloaded.all())
        assertNull(reloaded.get(deleted.stationId))
    }

    @Test
    fun `ids keep counting up after a restart`() = withDatabase { db ->
        val first = StationStore(db).insert(station(x = 0))
        StationStore(db).delete(first.stationId)

        val second = StationStore(db).insert(station(x = 0))

        assertEquals(true, second.stationId > first.stationId, "a deleted station's id isn't reused")
    }
}
