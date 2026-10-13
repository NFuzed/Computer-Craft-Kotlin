package app.route

import app.support.FleetTest
import app.support.fleetTest
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import protocol.api.*
import kotlin.test.*

private fun fuel(x: Int = 0, y: Int = 64, z: Int = 0, name: String = "Fuel", side: String = StationSides.UP) =
    StationRequest(name = name, type = StationTypes.FUEL, x = x, y = y, z = z, side = side)

private suspend fun FleetTest.create(request: StationRequest): HttpResponse =
    client.post("/api/stations") {
        contentType(ContentType.Application.Json)
        setBody(request)
    }

private suspend fun FleetTest.update(id: Long, request: StationRequest): HttpResponse =
    client.put("/api/stations/$id") {
        contentType(ContentType.Application.Json)
        setBody(request)
    }

private suspend fun FleetTest.created(request: StationRequest): Station {
    val response = create(request)
    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    return response.body()
}

private suspend fun HttpResponse.error(): String = body<ApiError>().error

class StationRoutesTest {

    // --- creating and reading ---

    @Test
    fun `a created station is returned with an id and can be read back`() = fleetTest {
        val station = created(
            StationRequest(
                name = "Main fuel", type = StationTypes.FUEL, x = 10, y = 64, z = -3,
                world = "w", dimension = "overworld"
            )
        )

        assertEquals("Main fuel", station.name)
        assertEquals(StationTypes.FUEL, station.type)
        assertEquals(Triple(10, 64, -3), Triple(station.x, station.y, station.z))
        assertEquals("w", station.world)
        assertEquals("overworld", station.dimension)
        assertEquals(StationSides.UP, station.side, "the chest is above by default")
        assertNull(station.heading)

        assertEquals(station, client.get("/api/stations/${station.stationId}").body<Station>())
    }

    @Test
    fun `stations get different ids`() = fleetTest {
        val a = created(fuel(x = 0))
        val b = created(fuel(x = 5))

        assertNotEquals(a.stationId, b.stationId)
    }

    @Test
    fun `all stations are listed, and can be filtered by type`() = fleetTest {
        val fuel = created(fuel(x = 0))
        val drop = created(StationRequest(name = "Drop", type = StationTypes.DROP, x = 5, y = 64, z = 0))
        val pickup = created(StationRequest(name = "Pickup", type = StationTypes.PICKUP, x = 10, y = 64, z = 0))

        assertEquals(listOf(fuel, drop, pickup), client.get("/api/stations").body<List<Station>>())
        assertEquals(listOf(drop), client.get("/api/stations?type=drop").body<List<Station>>())
    }

    @Test
    fun `filtering by an unknown type is refused`() = fleetTest {
        val response = client.get("/api/stations?type=teleporter")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `an unknown station is not found`() = fleetTest {
        assertEquals(HttpStatusCode.NotFound, client.get("/api/stations/12345").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/stations/abc").status)
    }

    // --- validation ---

    @Test
    fun `a station needs a known type`() = fleetTest {
        val response = create(fuel().copy(type = "teleporter"))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue("type must be one of" in response.error(), response.error())
    }

    @Test
    fun `a station needs a known side`() = fleetTest {
        val response = create(fuel().copy(side = "left"))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue("side must be one of" in response.error(), response.error())
    }

    @Test
    fun `a chest in front needs a heading, so the turtle knows which way to face`() = fleetTest {
        val response = create(fuel(side = StationSides.FRONT))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue("heading" in response.error(), response.error())
    }

    @Test
    fun `a heading must be N, E, S or W`() = fleetTest {
        val response = create(fuel().copy(heading = "NE"))

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `a station needs a name of at most 32 printable characters`() = fleetTest {
        assertEquals(HttpStatusCode.BadRequest, create(fuel(name = "  ")).status)
        assertEquals(HttpStatusCode.BadRequest, create(fuel(name = "x".repeat(MAX_STATION_NAME_LENGTH + 1))).status)
        assertEquals(HttpStatusCode.BadRequest, create(fuel(name = "tab\there")).status)
        assertEquals(HttpStatusCode.Created, create(fuel(name = "x".repeat(MAX_STATION_NAME_LENGTH))).status)
    }

    @Test
    fun `a name is trimmed`() = fleetTest {
        assertEquals("Fuel", created(fuel(name = "  Fuel ")).name)
    }

    @Test
    fun `a malformed body is refused`() = fleetTest {
        val response = client.post("/api/stations") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Fuel"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // --- overlapping stations ---

    @Test
    fun `two stations can't share a standing cell`() = fleetTest {
        created(fuel(x = 0, y = 64, z = 0))

        val response = create(fuel(x = 0, y = 64, z = 0, name = "Other"))

        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `a station can't stand in another station's chest`() = fleetTest {
        created(fuel(x = 0, y = 64, z = 0)) // chest at (0, 65, 0)

        assertEquals(HttpStatusCode.Conflict, create(fuel(x = 0, y = 65, z = 0, name = "Other")).status)
    }

    @Test
    fun `a station's chest can't be in another station's standing cell`() = fleetTest {
        created(fuel(x = 0, y = 65, z = 0))

        // standing at (0, 64, 0) with the chest above, at (0, 65, 0)
        assertEquals(HttpStatusCode.Conflict, create(fuel(x = 0, y = 64, z = 0, name = "Other")).status)
    }

    @Test
    fun `two stations may share a chest`() = fleetTest {
        created(fuel(x = 0, y = 64, z = 0, side = StationSides.UP)) // chest at (0, 65, 0)

        val below = create(fuel(x = 0, y = 66, z = 0, side = StationSides.DOWN, name = "Other"))

        assertEquals(HttpStatusCode.Created, below.status, below.bodyAsText())
    }

    @Test
    fun `the same cell in another dimension doesn't overlap`() = fleetTest {
        created(fuel().copy(dimension = "overworld"))

        assertEquals(HttpStatusCode.Created, create(fuel().copy(dimension = "nether")).status)
    }

    // --- updating and deleting ---

    @Test
    fun `a station can be replaced`() = fleetTest {
        val station = created(fuel(x = 0))

        val response = update(
            station.stationId,
            StationRequest(
                name = "Moved", type = StationTypes.DROP, x = 7, y = 70, z = 7,
                heading = "E", side = StationSides.FRONT
            )
        )

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val updated = response.body<Station>()
        assertEquals(station.stationId, updated.stationId)
        assertEquals("Moved", updated.name)
        assertEquals(StationTypes.DROP, updated.type)
        assertEquals(Triple(7, 70, 7), Triple(updated.x, updated.y, updated.z))
        assertEquals("E", updated.heading)
        assertEquals(StationSides.FRONT, updated.side)
        assertEquals(updated, client.get("/api/stations/${station.stationId}").body<Station>())
    }

    @Test
    fun `replacing a station doesn't overlap with itself`() = fleetTest {
        val station = created(fuel(x = 0))

        assertEquals(HttpStatusCode.OK, update(station.stationId, fuel(x = 0, name = "Renamed")).status)
    }

    @Test
    fun `replacing a station still can't overlap another`() = fleetTest {
        created(fuel(x = 0))
        val other = created(fuel(x = 5, name = "Other"))

        assertEquals(HttpStatusCode.Conflict, update(other.stationId, fuel(x = 0, name = "Other")).status)
    }

    @Test
    fun `replacing an unknown station is not found`() = fleetTest {
        assertEquals(HttpStatusCode.NotFound, update(12345, fuel()).status)
    }

    @Test
    fun `a deleted station is gone`() = fleetTest {
        val station = created(fuel())

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/stations/${station.stationId}").status)

        assertEquals(HttpStatusCode.NotFound, client.get("/api/stations/${station.stationId}").status)
        assertEquals(emptyList(), client.get("/api/stations").body<List<Station>>())
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/stations/${station.stationId}").status)
    }

    @Test
    fun `a deleted station's cells can be reused`() = fleetTest {
        val station = created(fuel())
        client.delete("/api/stations/${station.stationId}")

        assertEquals(HttpStatusCode.Created, create(fuel()).status)
    }
}
