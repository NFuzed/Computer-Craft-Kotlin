# Computer-Craft-Kotlin

A Kotlin ([Ktor](https://ktor.io/)) control server for a fleet of [ComputerCraft: Tweaked](https://tweaked.cc/) turtles.

Right now the server's main job is **code delivery**. Each turtle runs a small `startup.lua`. On boot it fetches a
bootstrap script from the server, compares its local files to a SHA-1 manifest, downloads only the files that changed,
and then runs the entry program. While it runs, each turtle keeps a WebSocket open to the server, which the server
uses to push position seeds and renames to it the moment the dashboard asks.

---

## Project layout

```
.
├── build.gradle.kts                 # Single Gradle build; also defines the genManifest task
├── settings.gradle.kts
└── computercraft-fleet/
    ├── protocol/                    # Types shared between the server and turtles
    │   └── src/main/kotlin/protocol/
    │       ├── Protocol.kt          # Protocol version, route paths, WebSocket message types
    │       ├── Json.kt              # Shared kotlinx.serialization Json config
    │       ├── api/
    │       │   ├── TurtleStatus.kt  # TurtleStatus + TurtlePosition (dashboard API)
    │       │   ├── PositionSeed.kt  # SeedRequest + PositionSeed (position seeding)
    │       │   ├── PendingLabel.kt  # LabelRequest + PendingLabel (renaming turtles)
    │       │   ├── TurtleEquipment.kt # TurtleEquipment, EquippedUpgrade, Capabilities
    │       │   ├── TurtleCommand.kt # Commands, CommandRequest, TurtleCommand, CommandResult
    │       │   ├── WorldMap.kt      # KnownBlock, MapRegion (world map API)
    │       │   ├── Station.kt       # StationTypes, StationSides, StationRequest, Station
    │       │   └── ApiError.kt
    │       └── messages/
    │           ├── PositionUpdate.kt
    │           └── TurtleMessages.kt # Hello, LabelUpdate, BlockObservations, SeedMessage, RenameMessage, ErrorMessage, HeartbeatMessage
    │
    └── controllerKotlin/            # The Ktor server
        ├── src/main/
        │   ├── kotlin/app/
        │   │   ├── Main.kt          # Starts Netty on 0.0.0.0:8000
        │   │   ├── Application.kt   # Ktor module: plugins, stores, routes
        │   │   ├── plugin/WebSockets.kt
        │   │   ├── path/
        │   │   │   ├── PathPlanner.kt     # A* route planning (costs, rules, results)
        │   │   │   ├── KnownWorld.kt      # The world map as the planner sees it, with guesses for unseen cells
        │   │   │   ├── SearchTables.kt    # Primitive hash table and heap for the search
        │   │   │   ├── BlockRules.kt      # Which blocks are open, and which are never dug or entered
        │   │   │   └── Geometry.kt        # Heading, Pose, Box
        │   │   ├── route/
        │   │   │   ├── Routes.kt          # Registers all routes
        │   │   │   ├── ApiRoutes.kt       # /api/turtles (dashboard REST API)
        │   │   │   ├── StationRoutes.kt   # /api/stations (fuel, drop and pickup stations)
        │   │   │   ├── GotoRoutes.kt      # /api/turtles/{id}/goto (send a turtle somewhere)
        │   │   │   ├── TurtleSocketRoutes.kt # /ws/turtle (one WebSocket per running turtle)
        │   │   │   ├── BootstrapRoutes.kt # GET /bootstrap/boot.lua
        │   │   │   ├── ManifestRoutes.kt  # GET /manifest.json
        │   │   │   └── FileRoutes.kt      # GET /files/{path...}
        │   │   ├── service/
        │   │   │   ├── FleetService.kt    # Builds TurtleStatus; pushes seeds/renames to connected turtles
        │   │   │   ├── Capabilities.kt    # Works out what a turtle can do from its equipment
        │   │   │   ├── StationService.kt  # Checks stations and keeps them from overlapping
        │   │   │   ├── TripService.kt     # Plans, sends and replans turtles' trips
        │   │   │   ├── StationCells.kt    # A station's standing cell and chest cell
        │   │   │   └── TurtleSessions.kt  # The open /ws/turtle connection per turtle
        │   │   └── store/
        │   │       ├── Database.kt        # Postgres connection pool, creates the tables, small JDBC helpers
        │   │       ├── PositionStore.kt   # Each turtle's last position
        │   │       ├── SeedStore.kt       # Pending position seeds
        │   │       ├── LabelStore.kt      # Pending renames
        │   │       ├── InventoryStore.kt  # Each turtle's last reported inventory
        │   │       ├── EquipmentStore.kt  # Each turtle's last reported equipment
        │   │       ├── WorldMapStore.kt   # Blocks the turtles have seen (database only, not cached)
        │   │       ├── StationStore.kt    # Fuel, drop and pickup stations
        │   │       └── TurtleRegistry.kt  # Label + last-seen per turtle (in memory)
        │   └── resources/           # Lua code that gets served to turtles
        │       ├── db/schema.sql    # The tables, created on startup if missing
        │       ├── startup.lua
        │       ├── manifest.template.json
        │       ├── manifest.json    # Generated; do not edit by hand
        │       ├── bootstrap/
        │       │   ├── boot.lua
        │       │   ├── updater.lua
        │       │   └── sha1converter.lua
        │       └── turtle/
        │           ├── main.lua     # Entry program: connects, then handles seeds and renames
        │           └── lib/
        │               ├── net.lua  # WebSocket connection to /ws/turtle (reconnects on drop)
        │               └── nav.lua  # Dead-reckoning position tracking (no GPS), and the cells around the turtle
        └── src/test/kotlin/app/
            ├── support/             # TestPostgres, fleetTest, FakeTurtle, SimWorld and SimTurtle (see Tests)
            ├── store/               # Store tests against a real Postgres
            ├── service/             # Tests of plain logic, no database
            ├── path/                # Pathfinding tests on worlds drawn as text (GridWorld)
            └── route/               # Whole-server tests over HTTP and /ws/turtle
```

Gradle builds everything as **one module**. The root `build.gradle.kts` adds the `protocol` and `controllerKotlin`
source directories to the main source set, so the server can use the protocol classes directly.

---

## Requirements

- **JDK 20.** The Kotlin toolchain is pinned to 20. The foojay resolver plugin can download it automatically.
- The Gradle wrapper, which is included.
- **PostgreSQL** (any recent version; developed against 18). See [Database setup](#database-setup).
- A Minecraft world with **CC: Tweaked** and the HTTP API enabled.
- A public URL that turtles can reach. The project currently uses an **ngrok** tunnel to `localhost:8000`.

---

## Database setup

The server keeps everything in Postgres. Create a database and a user for it once, e.g. in pgAdmin's Query Tool
while connected as `postgres`:

```sql
CREATE USER fleet WITH PASSWORD 'choose-a-password';
CREATE DATABASE fleet OWNER fleet;
```

The server reads its connection from environment variables:

| Variable            | Default                                  |
|---------------------|------------------------------------------|
| `FLEET_DB_URL`      | `jdbc:postgresql://localhost:5432/fleet` |
| `FLEET_DB_USER`     | `fleet`                                  |
| `FLEET_DB_PASSWORD` | none; the server won't start without it  |

On startup it runs `resources/db/schema.sql`, which creates any table that doesn't exist yet. It leaves existing
tables alone, so after changing a table there, drop it (or the whole database) to have it recreated; there are no
migrations, so its data is lost.

## Running the server

```bash
./gradlew run
```

On Windows, use `gradlew.bat run`.

`run` depends on `genManifest`, so the manifest hashes are refreshed before each start. The server listens on *
*`0.0.0.0:8000`**.

To expose it to Minecraft with ngrok:

```bash
ngrok http 8000
```

### Changing the server URL

The public URL is hard-coded in two places. Update both whenever your tunnel or host changes:

| File                               | Field                                |
|------------------------------------|--------------------------------------|
| `resources/startup.lua`            | `BASE`                               |
| `resources/manifest.template.json` | `baseUrl` (keep the `/files` suffix) |

---

## Tests

```bash
./gradlew test
```

The tests need no database setup. They start their own Postgres from binaries Gradle downloads
([zonky embedded-postgres](https://github.com/zonkyio/embedded-postgres)), separate from the server's database, with
no install or Docker needed. Starting it takes about 10 s per run; after that each test takes well under a second.
It's stopped when the tests finish. The helpers are in `controllerKotlin/src/test/kotlin/app/support/`:

- **`withDatabase { db -> }`** gives a test its own empty database with the schema created, for testing a store on
  its own.
- **`fleetTest { }`** runs the whole server (routes, WebSockets, services and stores) in memory on a fresh database.
  Nothing listens on a real port, so it doesn't clash with a server you're running. `client` calls the API.
- **`turtle(id) { }`**, inside a `fleetTest`, connects a `FakeTurtle` and says hello. It sends protocol messages,
  `receive`s what the server pushes (skipping heartbeats), and `sync()` waits until the server has handled everything
  sent so far.
- **`SimWorld`** is a world drawn as text (`.` air, `#` stone, `~` water, `C` chest, `B` bedrock, one `y=N` layer at a
  time; everything outside the drawing is stone) that turtles move and dig in. The server only learns it from what
  turtles report, as in Minecraft.
- **`simTurtle(id, world, start, canDig, fuel, stepDelayMs)`** puts a `SimTurtle` in a `SimWorld` and connects it.
  It does what `main.lua` does with paths: runs each step (digging first when told to), reports its position and the
  blocks around it after each one, stops when a move fails, and sends a `path_result`. Turtles are solid to each
  other. `goto(...)`, `trip(id)` and `awaitTrip(id)` drive and follow trips.

```kotlin
@Test
fun `blocks a turtle observes show up in the map`() = fleetTest {
        turtle(7) {
            send(BlockObservations(turtleId = 7, blocks = listOf(ObservedBlock(1, 64, 0, "minecraft:stone"))))
            sync()
        }
        val region = client.get("/api/map/blocks?minX=0&minY=60&minZ=0&maxX=5&maxY=70&maxZ=5").body<MapRegion>()
        assertEquals(listOf("minecraft:stone"), region.blocks.map { it.name })
    }
```

```kotlin
@Test
fun `with a pickaxe and no way round, it digs through`() = fleetTest {
        val world = SimWorld.parse("y=70\n..#..")
        val turtle = simTurtle(7, world, at(0, 70, 0, E), canDig = true)

        goto(7, 4, 70, 0)

        assertEquals(TripStatuses.ARRIVED, awaitTrip(7).status)
        assertEquals(BlockPos(4, 70, 0), turtle.pose.pos)
        assertEquals(AIR, world.terrainAt(BlockPos(2, 70, 0)))
    }
```

The turtle's Lua code itself isn't tested; `SimTurtle` follows the same rules, so keep the two in step.

---

## HTTP API

The route paths are defined in `protocol.Protocol.Paths`.

| Method   | Path                                   | Returns                                                                                                                                                                                                                                                                                          |
|----------|----------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GET`    | `/bootstrap/boot.lua`                  | The bootstrap script, as plain text                                                                                                                                                                                                                                                              |
| `GET`    | `/manifest.json`                       | The file manifest (path, SHA-1, entry point)                                                                                                                                                                                                                                                     |
| `GET`    | `/files/{path...}`                     | Any file on the server's classpath under `resources/`, as raw bytes                                                                                                                                                                                                                              |
| `GET`    | `/api/turtles`                         | `TurtleStatus[]` for every known turtle, sorted by ID                                                                                                                                                                                                                                            |
| `GET`    | `/api/turtles/{id}`                    | One `TurtleStatus`, or `404`                                                                                                                                                                                                                                                                     |
| `POST`   | `/api/turtles/{id}/seed`               | Takes a `SeedRequest` (`x`, `y`, `z`, `heading`, `world?`, `dimension?`, `source?`) and returns the queued `PositionSeed` with `202`. The turtle doesn't have to be connected yet                                                                                                                |
| `PUT`    | `/api/turtles/{id}/label`              | Takes a `LabelRequest` (`label?`; null or blank clears it, max 32 printable ASCII characters) and returns the turtle's `TurtleStatus` with `202`. See [Renaming](#renaming)                                                                                                                      |
| `PUT`    | `/api/turtles/{id}/equipment`          | Takes an `EquipmentSeedRequest` (`left?`, `right?`, each an `EquippedUpgrade`; null is empty) and returns the turtle's `TurtleStatus`. `404` for an unknown turtle. See [Equipment and capabilities](#equipment-and-capabilities)                                                                |
| `GET`    | `/api/turtles/{id}/inventory`          | The turtle's last reported `TurtleInventory`, or `404` if it never reported one. See [Inventory](#inventory)                                                                                                                                                                                     |
| `POST`   | `/api/turtles/{id}/inventory/selected` | Takes a `SelectSlotRequest` (`slot`, 1-16) and pushes it to the turtle, returning the request with `202`. `409` if the turtle isn't connected                                                                                                                                                    |
| `POST`   | `/api/turtles/{id}/refuel`             | Takes a `RefuelRequest` (`count?`, 1-64; null burns the whole selected stack) and pushes it to the turtle, returning a `RefuelCommand` (`refuelId`, `count`, `ts`) with `202`. `409` if the turtle isn't connected. See [Refuelling](#refuelling)                                                |
| `POST`   | `/api/turtles/{id}/commands`           | Takes a `CommandRequest` (`command`, one of `Commands`) and pushes it to the turtle, returning a `TurtleCommand` (`commandId`, `command`, `ts`) with `202`. `400` for an unknown command, `409` if the turtle isn't connected. See [Commands](#commands)                                         |
| `GET`    | `/api/map/blocks`                      | Takes `minX`, `minY`, `minZ`, `maxX`, `maxY`, `maxZ` (inclusive, required, at most 256 apart on each axis), optional `world` and `dimension`, and optional `excludeAir` (`true` leaves out known air), and returns a `MapRegion` with every known block in that box. See [World map](#world-map) |
| `GET`    | `/api/stations`                        | Every `Station`, oldest first; `?type=fuel`, `drop` or `pickup` returns only that type. See [Stations](#stations)                                                                                                                                                                                |
| `POST`   | `/api/turtles/{id}/goto`               | Takes a `GotoRequest` (`x`, `y`, `z`, `heading?`), plans a path and sends it, returning the `Trip` with `202`. `400` for a bad heading, `409` if the turtle isn't connected or has no position, `422` if there's no path or not enough fuel. See [Goto](#goto)                                   |
| `GET`    | `/api/turtles/{id}/goto`               | The turtle's current or last `Trip`, or `404`                                                                                                                                                                                                                                                    |
| `DELETE` | `/api/turtles/{id}/goto`               | Cancels the trip in progress: the turtle stops after its current step. Returns the `Trip` with `202`, or `404`                                                                                                                                                                                   |
| `POST`   | `/api/stations`                        | Takes a `StationRequest` and returns the new `Station` with `201`. `400` if it's invalid, `409` if it overlaps another station                                                                                                                                                                   |
| `GET`    | `/api/stations/{id}`                   | One `Station`, or `404`                                                                                                                                                                                                                                                                          |
| `PUT`    | `/api/stations/{id}`                   | Takes a `StationRequest` and replaces the station, keeping its id; returns it with `200`. `400`, `404` or `409` as above                                                                                                                                                                         |
| `DELETE` | `/api/stations/{id}`                   | Deletes the station: `204`, or `404`                                                                                                                                                                                                                                                             |

`/api` responses are JSON. Errors come back as `{"error": "..."}` with status `400`, `404` or `409`. `TurtleStatus` (
in `protocol.api`) contains `turtleId`, `label`, `online`, `lastSeen`, `position`, `pendingSeed` (a seed the turtle
hasn't applied yet), `pendingLabel` (a rename the turtle hasn't applied yet) and `equipment` (its upgrades and
`capabilities`; see [Equipment and capabilities](#equipment-and-capabilities)) and `lastCommand` (its latest
`CommandResult`; see [Commands](#commands)). A turtle counts as online while
its `/ws/turtle` connection is open. `lastSeen` is when the server last heard from it.

Example:

```bash
curl localhost:8000/api/turtles
```

Seeding a turtle's position (what the dashboard will do):

```bash
curl -X POST localhost:8000/api/turtles/7/seed \
     -H "Content-Type: application/json" \
     -d '{"x":10,"y":64,"z":-3,"heading":"N"}'
```

Renaming a turtle:

```bash
curl -X PUT localhost:8000/api/turtles/7/label      -H "Content-Type: application/json"      -d '{"label":"Miner 1"}'
```

Reading a turtle's inventory and selecting slot 3:

```bash
curl localhost:8000/api/turtles/7/inventory
curl -X POST localhost:8000/api/turtles/7/inventory/selected -H "Content-Type: application/json" -d '{"slot":3}'
```

Burning 8 items from the selected slot, then the whole stack:

```bash
curl -X POST localhost:8000/api/turtles/7/refuel -H "Content-Type: application/json" -d '{"count":8}'
curl -X POST localhost:8000/api/turtles/7/refuel -H "Content-Type: application/json" -d '{"count":null}'
```

### Turtle WebSocket (`/ws/turtle`)

Turtles can only make outgoing connections, so the server can't call a turtle directly. Instead each running turtle
opens `ws(s)://<server>/ws/turtle` with `http.websocket` and keeps it open. The server uses it to push seeds and
renames as soon as the dashboard sends them. There is no polling.

1. The turtle sends a `hello` (`turtleId`, `label?`) as its first message. Anything else, or nothing within 10 s,
   closes the connection with `1008`.
2. The server records the label, replaces any older connection from the same turtle (e.g. it rebooted before the
   old one timed out), then sends any pending `rename` and `seed`.
3. After that, the turtle sends `position_update`, `label_update`, `inventory_update`, `equipment_update`,
   `refuel_result`, `command_result`, `block_observations` and `path_result` messages.
   The server pushes `seed` and `rename` whenever the API queues one, `select_slot`, `refuel` and `command` when the
   API asks for one, `path` and `path_cancel` for trips, replies `error` to a message it can't handle (the connection
   stays open), and sends a
   `heartbeat` every 10 s.

The server pings every 15 s and drops a connection after 30 s without a reply. CC: Tweaked answers pings itself, but
Lua never sees them, so the turtle relies on the heartbeats instead: if nothing arrives for 30 s, `net.lua` closes the
connection and reconnects. That catches connections that died without closing, e.g. when the server's PC sleeps. If
the connection drops, `net.lua` reconnects every 5 s. Anything queued while a turtle is offline stays in
the `pending_seed` / `pending_label` tables and is sent on its next `hello`.

---

## How a turtle boots and updates

```
startup.lua ──GET /bootstrap/boot.lua──▶ server
   │  (caches boot.lua locally; uses the cache if the server is offline)
   ▼
boot.lua
   ├─ downloads bootstrap/sha1converter.lua (only if missing)
   ├─ downloads bootstrap/updater.lua      (only if missing)
   └─ updater.update(manifestUrl, server)
          ├─ GET /manifest.json
          ├─ for each file: sha1(local) ≠ manifest sha1 ?
          │      └─ GET {baseUrl}/{path}, write it, re-hash to verify
          └─ returns manifest.entry
   ▼
entry(SERVER)   -- turtle/main.lua, given the server base URL
   ├─ nav.load()                        (restores position, recovers an interrupted move)
   └─ net.run(): open ws(s)://<server>/ws/turtle, send hello (label from os.getComputerLabel())
          ├─ connected, position changed → position_update
          ├─ connected, turtle_inventory event → inventory_update
          ├─ connected, equipment changed → equipment_update
          ├─ select_slot pushed → turtle.select(), inventory_update
          ├─ rename pushed → os.setComputerLabel(), label_update
          ├─ seed pushed → nav.applySeed(), position_update
          ├─ command pushed → nav move/turn or turtle.dig/place/..., position_update, inventory_update, command_result
          └─ connection dropped, or 30 s without a message → retry every 5 s
```

Key details:

- **`startup.lua`** runs whenever the turtle starts. It downloads the latest `boot.lua` and writes it to disk
  atomically (write to `.tmp`, then move). If the server can't be reached, it runs the cached copy. It passes the server
  base URL to `boot.lua` as an argument.
- **`sha1converter.lua`** is a SHA-1 implementation in pure Lua, built on `bit32`. Turtles don't have a built-in hash
  function, so the updater uses this to compare files against the manifest.
- **`updater.lua`** reads `manifest.json`. For each file, it hashes the local copy and downloads a new one only if the
  hash differs. After writing, it hashes the download again and throws an error if the result doesn't match. If an
  update fails, `boot.lua` logs the error and continues.
- `startup.lua` and `updater.lua` are listed in the manifest, so the update system updates itself. `sha1converter.lua`
  is **not** listed: it is downloaded once and never refreshed.

### Installing on a new turtle

Run this in the turtle's shell, replacing `<server>` with your server URL, then reboot:

```
wget <server>/files/startup.lua startup.lua
reboot
```

---

## Adding or changing turtle code

1. Add or edit Lua files under `controllerKotlin/src/main/resources/`.
2. Add new files to the `files` list in **`manifest.template.json`**, with `"sha1": ""`.
3. If needed, set `entry` to the program that should run after updating.
4. Run `./gradlew genManifest`, or just `./gradlew run`.

`genManifest` reads every `path` in the template, computes the SHA-1 of each file, and writes the result
to `manifest.json`. It fails if a listed file doesn't exist. Turtles pick up changes the next time they
reboot. `processResources` depends on `genManifest`, so any Gradle build regenerates the manifest. If the server is
already running, restart it after changing turtle code.

`.gitattributes` keeps `*.lua` and `*.json` files LF-only, and the updater downloads and reads files in binary mode.
Both are there so the bytes a turtle hashes are exactly the bytes `genManifest` hashed. With Windows CRLF line endings,
the hashes never match.

---

## Protocol messages

These are `@Serializable` data classes in `protocol.messages`, sent as JSON text frames over `/ws/turtle` and
encoded with the shared `ProtocolJson` (`ignoreUnknownKeys = true`, `encodeDefaults = true`). Every message has
`v = Protocol.VERSION` and a `type` from `Protocol.MessageTypes`, which the server uses to decode it:

| Message               | `type`               | Direction       | Fields                                                                                                                       |
|-----------------------|----------------------|-----------------|------------------------------------------------------------------------------------------------------------------------------|
| `Hello`               | `hello`              | turtle → server | `turtleId`, `label?`. Must be the first message                                                                              |
| `PositionUpdate`      | `position_update`    | turtle → server | `turtleId`, `x`, `y`, `z`, `heading?` (`N/E/S/W`), `world?`, `dimension?`, `seedId?`, `ts`                                   |
| `LabelUpdate`         | `label_update`       | turtle → server | `turtleId`, `label?`: the label the turtle now has                                                                           |
| `InventoryUpdate`     | `inventory_update`   | turtle → server | `turtleId`, `selectedSlot`, `slots` (`InventorySlot[]`: `slot`, `name`, `count`, `nbt?`), `fuel?` (`level?`, `limit?`), `ts` |
| `RefuelResultUpdate`  | `refuel_result`      | turtle → server | `turtleId`, `refuelId`, `refueled`, `error?`, `fuelAdded?`, `ts`                                                             |
| `EquipmentUpdate`     | `equipment_update`   | turtle → server | `turtleId`, `left?`, `right?` (`EquippedUpgrade`: `item?`, `peripheralTypes`), `itemsKnown`, `ts`                            |
| `CommandResultUpdate` | `command_result`     | turtle → server | `turtleId`, `commandId`, `command`, `success`, `error?`, `ts`                                                                |
| `SeedMessage`         | `seed`               | server → turtle | `seed` (a `PositionSeed`)                                                                                                    |
| `RenameMessage`       | `rename`             | server → turtle | `label?` (null clears the label)                                                                                             |
| `SelectSlotMessage`   | `select_slot`        | server → turtle | `slot` (1-16)                                                                                                                |
| `RefuelMessage`       | `refuel`             | server → turtle | `refuelId`, `count?` (1-64; null burns the whole selected stack)                                                             |
| `CommandMessage`      | `command`            | server → turtle | `commandId`, `command` (one of `Commands`)                                                                                   |
| `BlockObservations`   | `block_observations` | turtle → server | `turtleId`, `world?`, `dimension?`, `blocks` (`ObservedBlock[]`: `x`, `y`, `z`, `name`), `ts`. See [World map](#world-map)   |
| `PathResultUpdate`    | `path_result`        | turtle → server | `turtleId`, `pathId`, `status` (`done`, `blocked`, `failed`, `cancelled`), `stepsDone`, `error?`, `ts`                       |
| `PathMessage`         | `path`               | server → turtle | `pathId`, `start` (`x`, `y`, `z`, `heading`), `steps` (`action`, `dig`)                                                      |
| `PathCancelMessage`   | `path_cancel`        | server → turtle | `pathId`                                                                                                                     |
| `ErrorMessage`        | `error`              | server → turtle | `error`: why the last message was rejected                                                                                   |
| `HeartbeatMessage`    | `heartbeat`          | server → turtle | none. Sent every 10 s; the turtle reconnects after 30 s without any message                                                  |

---

## Position tracking (no GPS)

Turtles don't use GPS, because that would need a wireless modem on every turtle. Instead, `turtle/lib/nav.lua` uses
**dead reckoning**: it starts from a known position and heading (the *seed*) and updates them after every successful
move. Turtle code must move through `nav.forward/back/up/down/turnLeft/turnRight` rather than the `turtle` API, or the
tracked position drifts.

Headings follow Minecraft's axes: `N` = -z, `E` = +x, `S` = +z, `W` = -x.

State is saved to `state/nav.json` on the turtle after every change, so it survives reboots and chunk unloads.

**Interrupted moves.** Before each move, nav saves the move name and the current fuel level as `pending`. A move that
succeeds costs exactly 1 fuel. If the turtle shuts down mid-move, `nav.load()` compares the fuel level on the next
boot: 1 lower means the move happened and is applied, unchanged means it didn't. With `turtleNeedFuel` disabled
(fuel is `"unlimited"`) this can't be checked, and nav prints a warning. Turns cost no fuel, so the new heading is
saved before turning. A shutdown mid-turn can leave the heading off by 90° until the turtle is re-seeded.

**Seeding.**

1. The dashboard sends `POST /api/turtles/{id}/seed`. The server stores a `PositionSeed` with a new `seedId` in
   the `pending_seed` table. A newer seed replaces an older one.
2. The server pushes it to the turtle straight away, or on its next `hello` if it is offline. The turtle applies
   it, replacing any position it had. Seeding again is also how a drifted position is corrected.
3. The turtle then sends a position update. Every position update carries the `seedId` it is based on, and the
   first one to arrive acknowledges the seed and clears it on the server. Until then the server resends it on every
   connect, so the turtle ignores a seed whose `seedId` it has already applied (but still reports its position).

A turtle that has never been seeded doesn't move: the nav functions return `false, "not seeded"`.

## Renaming

A turtle's name is its ComputerCraft label, and the turtle sends it in every `hello`. So the server can't simply
store a new name, because the next `hello` would overwrite it. Renames go through the turtle instead:

1. The dashboard sends `PUT /api/turtles/{id}/label`. The server stores a `PendingLabel` in the `pending_label` table
   and
   shows it as `pendingLabel` in `TurtleStatus`. A newer rename replaces an older one.
2. The server pushes it to the turtle straight away, or on its next `hello` if it is offline. The turtle calls
   `os.setComputerLabel` and replies with a `label_update`.
3. A `label_update` or `hello` whose label matches the pending one clears it, and the new `label` shows up in
   `TurtleStatus`. One with any other label (e.g. a turtle that connected before getting the rename) leaves it
   pending.

Labels are limited to 32 printable ASCII characters because ComputerCraft drops anything else. If it did, the
turtle's label would never match and the rename would be sent forever.

## Inventory

The turtle sends its whole inventory as an `inventory_update`: after every connect, whenever the `turtle_inventory`
event fires (items added, removed or moved), and after selecting a slot (which doesn't fire that event). It lists
only non-empty slots, from `turtle.getItemDetail`, plus `turtle.getSelectedSlot()`. The server replaces what it had
for that turtle and saves it to the `turtle_inventory` and `inventory_slot` tables, so `GET /api/turtles/{id}/inventory`
still answers after a
server restart. The response's `ts` says how old it is.

`POST /api/turtles/{id}/inventory/selected` sends a `select_slot` to the turtle straight away. It isn't queued like
seeds and renames, because a selection made while the turtle is offline would likely be stale when it connects, so
an offline turtle gets `409`. The `202` only means the message was sent; the stored inventory shows the new
`selectedSlot` once the turtle's `inventory_update` arrives.

## Refuelling

Each `inventory_update` also carries the turtle's `fuel`: `level` and `limit` from `turtle.getFuelLevel()` and
`turtle.getFuelLimit()`, both left out when the server runs with unlimited fuel (`turtleNeedFuel = false`). Older
turtle code doesn't send it, so `fuel` is null until the turtle updates. Fuel only changes when the turtle moves or
refuels; a command reports it afterwards (see [Commands](#commands)).

`POST /api/turtles/{id}/refuel` sends a `refuel` with a new `refuelId` straight away. Like slot selection it isn't
queued, so an offline turtle gets `409`. The turtle calls `turtle.refuel(count)` on its selected slot (no count burns
the whole stack), replies with a `refuel_result` carrying the same `refuelId`, whether it worked, the reason if it
didn't (e.g. `Items not combustible`) and how much fuel it gained, then sends an `inventory_update`. The server keeps
each turtle's latest result in memory and returns it as `lastRefuel` from `GET /api/turtles/{id}/inventory`, so
the dashboard can match it to the refuel it sent.

## Commands

`POST /api/turtles/{id}/commands` makes a turtle run one turtle API call, named in `protocol.api.Commands`:

| Commands                           | Turtle API                                      |
|------------------------------------|-------------------------------------------------|
| `forward`, `back`, `up`, `down`    | `nav.forward()` etc., so dead reckoning follows |
| `turnLeft`, `turnRight`            | `nav.turnLeft()`, `nav.turnRight()`             |
| `dig`, `digUp`, `digDown`          | `turtle.dig()` etc.; needs the `dig` capability |
| `attack`, `attackUp`, `attackDown` | `turtle.attack()` etc.; needs `attack`          |
| `place`, `placeUp`, `placeDown`    | `turtle.place()` etc., from the selected slot   |
| `drop`, `dropUp`, `dropDown`       | `turtle.drop()` etc.: the whole selected stack  |
| `suck`, `suckUp`, `suckDown`       | `turtle.suck()` etc.                            |

Like refuels, commands aren't queued (`409` while offline), since a move picked while the turtle is offline would be
stale by the time it connects. The server doesn't check capabilities: a turtle without a tool answers `dig` with the
turtle API's reason (`No tool to dig with`). Moves need a seeded turtle; an unseeded one answers `not seeded`.

The turtle runs the command, sends a `position_update` if it moved or turned, a `block_observations` (see
[World map](#world-map)) and an `inventory_update` (fuel and items
may have changed), then a `command_result` with the same `commandId`, whether it worked, and the turtle API's reason if
it didn't (e.g. `Movement obstructed`). The server keeps each turtle's latest result in memory and returns it as
`lastCommand` in `TurtleStatus`.

While a turtle API call runs, CC: Tweaked drops other events, including `websocket_message`, so a command sent while
the previous one is still running is lost. The dashboard waits for each result before allowing the next command.

## World map

The server builds a map of the blocks the turtles have seen, for the pathfinder to plan over. A seeded turtle runs
`turtle.inspect()`, `inspectUp()` and `inspectDown()` after it connects, after it's seeded and after every command, and
sends the results as `block_observations`: up to 64 blocks, each with world coordinates worked out from its
dead-reckoned position and heading (`nav.adjacent()`) and a block `name`, which is `minecraft:air` when nothing was
there. Each inspect takes a tick and uses no fuel. If the turtle's position is wrong, so are the coordinates.

`WorldMapStore` keeps one map per `world` and `dimension` (taken from the turtle's seed; either can be null), with
the latest block reported at each cell as a `KnownBlock` (`x`, `y`, `z`, `name`, `turtleId`, `ts`). A new report
replaces what was known about a cell. Cells nobody has seen aren't stored. Each report is upserted straight
into the `world_block` table, whose primary key is `(world, dimension, chunk_x, chunk_z, y, z, x)`; a null world or
dimension is
stored as an empty string, since a primary key can't hold nulls. `chunk_x` and `chunk_z` are generated by Postgres
(`x >> 4`, `z >> 4`, Minecraft's 16x16 chunks), so a box query looks up just the chunks it covers rather than every
block at its heights. Nothing is cached in memory, so the map can grow beyond what fits in RAM.

A turtle can't travel between worlds or dimensions, so when it reports a position in a different world or dimension
from its last one (it was re-seeded with the right one), `FleetService.recordPosition` first moves the blocks it was the
last to report from the old map to the new one. Where the new map already knows a cell, the newer report wins. Blocks
it saw that another turtle has reported since stay where they are.

`GET /api/map/blocks` returns the known blocks in a box, sorted by y, then z, then x:

```bash
curl "localhost:8000/api/map/blocks?minX=0&minY=60&minZ=0&maxX=15&maxY=70&maxZ=15"
```

The pathfinder needs known air, but the dashboard doesn't draw it, so it adds `excludeAir=true` to leave it out.

## Stations

Stations are fixed places, set up from the dashboard, where turtles go to refuel (`fuel`), drop items off (`drop`)
or pick up items someone has put out for them (`pickup`). They're shared by the whole fleet: a turtle will use the
nearest station of the type it needs, by path length, once the pathfinder exists.

A `StationRequest` has a `name` (at most 32 printable characters, trimmed), a `type`, the cell the turtle stands in
(`x`, `y`, `z`, not the chest), optional `world` and `dimension` like a seed, a `side` saying where the chest is from
there (`up` by default, `down`, or `front`), and a `heading` (`N`, `E`, `S`, `W`) the turtle faces there. The
heading is required when the chest is in `front`, since that's what puts the chest in front of it.

```bash
curl -X POST localhost:8000/api/stations -H "Content-Type: application/json"      -d '{"name":"Main fuel","type":"fuel","x":10,"y":64,"z":-3}'
```

A station's standing cell can't be another station's standing cell or chest (`409`), so a turtle using one station
never blocks another. Two stations may share a chest, e.g. one turtle above it and one beside it. Stations in another
`world` or `dimension` never overlap. `StationCells.kt` works out the chest cell (`chestCell`); the pathfinder must
never dig it. Stations are saved in the `station` table and kept in memory.

## Pathfinding

`PathPlanner` (in `app/path`) plans a turtle's route with A*; [Goto](#goto) uses it to send turtles places. It works on
a `WorldView` (what's known at each cell, and a guess for cells nobody has seen) and returns the steps a
turtle takes: `forward`, `back`, `up`, `down`, `turnLeft`, `turnRight`, each with the pose it ends at and whether
it digs first. `Path.fuel` is the number of moves; turns and digs use no fuel.

Costs are in moves (`PathCosts`), and the planner finds the cheapest path for them:

| Step                                 | Cost   |
|--------------------------------------|--------|
| Move into known air or liquid        | 1      |
| Turn                                 | 0.5    |
| Back up (only into known open cells) | 1.1    |
| Dig up or down, then move            | 1 + 2  |
| Dig in front, then move              | 1 + 11 |
| Cell nobody has seen                 | +0.01  |
| ...probably solid, but can't be dug  | +4     |

So a turtle takes a detour of up to 11 moves rather than dig one block sideways, and prefers digging up or down to
open space over tunnelling. A cell nobody has seen counts as air or, if `WorldView.probablySolid` says so, as a dig;
the turtle only digs cells the plan expects to be solid, and replans when it finds a block it didn't expect.

`PathRules` say what one turtle may do on one trip: `canDig` (it has a tool), `impassable` cells it never enters (other
turtles, station chests), `noDigZones` (boxes where nothing is dug), the `bounds` the search stays inside, and
`maxNodes` (1.5 million states) after which it gives up with `TooFar`. Protected blocks are never dug or entered:
anything from ComputerCraft (turtles, computers), storage (chests, barrels, shulker boxes, hoppers, furnaces),
bedrock, spawners and portals.

On a test machine a 200 block trip over unexplored ground plans in about 20 ms, and the worst case so far (from deep
underground through unexplored, probably solid ground, which means going up to the surface and back down) in about
0.4 s. `PathPlannerOptimalityTest` checks the paths are the cheapest by comparing with an exhaustive search on
random worlds.

## Goto

`POST /api/turtles/{id}/goto` with `{"x":..,"y":..,"z":..,"heading":"N"}` sends a turtle somewhere. `TripService`
plans a path at once, so an impossible trip is refused (`422`), sends it as a `path` message, and follows the trip:

1. The server plans over the known blocks in a box around the start and goal (24 blocks to each side, from 16 below
   the lower one to 16 above the highest of the start, the goal, the tallest known block and the surface guess). At
   first it **explores**: cells nobody has seen count as open, so the path takes the shortest way through unexplored
   ground and the turtle maps that route as it goes, which pays off because turtles go back and forth between their
   work and the stations. Once a trip has been blocked 3 times (`TripConfig.exploreUntilBlocked`), its new paths use
   `KnownWorld`'s guesses instead, so a turtle in rock digs through rather than probing the rock all round it. Other
   turtles' cells and station chests are never entered; the turtle digs
   only if its equipment includes a digging tool. A path needing more fuel than the turtle has is refused.
2. The turtle (`main.lua`) checks it is at the path's `start`; if not (it moved after the server planned), it doesn't
   move and replies `blocked`. Then it runs each step, digging first when a step says `dig` (repeatedly, for falling
   sand and gravel), and reports its position and the blocks around it after each one.
3. If a move fails, the turtle stops and replies `blocked`. By then the server has the blocks it saw, so it plans a
   new path from where the turtle is: `Trip.replans` counts these, and the trip fails after 20. When the step led
   into a cell the map says is open (a mob, a player), the server waits a second first.
4. `done` ends the trip as `arrived`. `failed` (e.g. out of fuel) and no path from where it stopped end it as
   `failed`, with `Trip.error` saying why. So does the turtle disconnecting: it stops its path when the connection
   drops.

A new goto replaces a trip in progress (the old one ends as `cancelled`), and `DELETE` cancels one: the server sends
`path_cancel` and the turtle stops after its current step. Paths run in their own coroutine on the turtle
(`parallel.waitForAny`), so messages such as a cancel still arrive while it moves. Trips are kept in memory: each
turtle's current or last one.

**Guessing unseen cells** (`KnownWorld`, after exploring has been blocked a few times): a cell nobody has seen is
guessed from what's known above and below it in
its column, or the nearest seen column within 8 blocks. Under a known solid block, or between known air and known
ground, it's solid; between known air above and below, or above a column's highest known block when that's air, it's
air; otherwise it's solid below y 62 and air above. Wrong guesses of solid cost little (the turtle is ready to dig a
cell that turns out to be air), wrong guesses of air cost a replan, so it leans towards solid. To help, the cell a
turtle reports standing in is recorded as air, and another turtle seen by `turtle.inspect` is stored as the air it
stands in, since it will move.

## Equipment and capabilities

So the dashboard knows which commands a turtle can run, each turtle reports what it has equipped as an
`equipment_update`: after every connect, and whenever a `turtle_inventory`, `peripheral` or `peripheral_detach` event
changes it (equipping swaps an item between the inventory and a side). Each side is an `EquippedUpgrade` with the
`item` from `turtle.getEquippedLeft/Right()` and the `peripheralTypes` from `peripheral.getType(side)`; an empty side
is null.

The server stores it in the `turtle_equipment` table and returns it as `equipment` in `TurtleStatus`, with a
`capabilities`
list worked out in `app/service/Capabilities.kt` (names in `protocol.api.Capabilities`):

| Upgrade                                 | Capabilities                           |
|-----------------------------------------|----------------------------------------|
| Any pickaxe, shovel, axe or hoe         | `dig`, `attack`                        |
| Any sword                               | `attack`                               |
| Crafting table (peripheral `workbench`) | `craft`                                |
| Wireless or ender modem (`modem`)       | `modem`                                |
| Speaker                                 | `speaker`                              |
| Any other peripheral (e.g. modded)      | its peripheral type, e.g. `geoScanner` |

Every turtle can move, place, drop, suck and refuel, so those aren't listed. Older CC: Tweaked versions don't have
`turtle.getEquippedLeft/Right`; their turtles report `itemsKnown: false` and only peripheral upgrades, so a tool
doesn't show up, and a peripheral block beside an empty side can be mistaken for an upgrade.

**Seeding equipment.** For those turtles the dashboard can say what is equipped with
`PUT /api/turtles/{id}/equipment` and an `EquipmentSeedRequest`, for example:

```bash
curl -X PUT localhost:8000/api/turtles/7/equipment \
  -H 'Content-Type: application/json' \
  -d '{"left": {"item": "minecraft:diamond_pickaxe"}, "right": {"item": "computercraft:wireless_modem_normal", "peripheralTypes": ["modem"]}}'
```

The server stores it like a report, with `itemsKnown: true`, the capabilities worked out the same way and
`seeded: true`. A report with `itemsKnown: false` doesn't replace a seed, so it survives reconnects; a report from a
turtle that can read its items does. Each equipped side needs an `item` or at least one peripheral type.

## Storage

Everything is saved in Postgres (see [Database setup](#database-setup)):

| Table                                | Store            | Holds                                 |
|--------------------------------------|------------------|---------------------------------------|
| `turtle_position`                    | `PositionStore`  | Each turtle's last `TurtlePosition`   |
| `pending_seed`                       | `SeedStore`      | Seeds not yet applied                 |
| `pending_label`                      | `LabelStore`     | Renames not yet applied               |
| `turtle_inventory`, `inventory_slot` | `InventoryStore` | Each turtle's last inventory and fuel |
| `turtle_equipment`                   | `EquipmentStore` | Each turtle's last equipment          |
| `world_block`                        | `WorldMapStore`  | The world map                         |

All stores except `WorldMapStore` load their table into a `ConcurrentHashMap` on startup and write through to the
database on every change, so reads never hit the database. `lastRefuel` and `lastCommand` aren't saved.

`TurtleRegistry` keeps each turtle's label and when it was last seen, in memory only. `TurtleSessions` keeps each
turtle's open WebSocket. `FleetService` combines them with the stores into the `TurtleStatus` objects that `/api`
returns.

---

## Status / roadmap

Done:

- [x] Code delivery over HTTP with SHA-1-based incremental updates
- [x] Boot from a cached copy when the server is offline
- [x] Manifest hashes generated at build time
- [x] `/api/turtles` REST endpoints for the dashboard
- [x] `/ws/turtle` WebSocket session: turtles connect after boot, and seeds/renames are pushed instantly
- [x] Dead-reckoning position tracking with fuel-based recovery of interrupted moves (no GPS)
- [x] Seed a turtle's position through the API
- [x] Rename a turtle through the API
- [x] Read a turtle's inventory and select a slot through the API
- [x] Report a turtle's fuel and refuel it through the API
- [x] Report a turtle's equipment and what it can do
- [x] Move, turn, dig, place, drop, suck and attack through the API
- [x] World map built from the blocks turtles inspect
- [x] Fuel, drop and pickup stations set up through the API
- [x] A* route planning
- [x] Send a turtle somewhere through the API, replanning when it's blocked

Not yet connected:

- [ ] `/ws/dashboard` live event stream for the frontend
- [ ] Dashboard UI for seeding (the API exists)
- [ ] **Commander turtle** that carries other turtles and seeds the ones it places (see below)
- [ ] `turtle/main.lua` does real work (currently prints a test message)
- [ ] Add `sha1converter.lua` to the manifest so it gets updated too
- [ ] Make the server URL configurable instead of hard-coding it

### Planned: commander turtle

A commander carries turtles in its inventory. It is seeded from the dashboard like any other turtle. Each turtle it
places is seeded automatically, using the existing seed API:

1. The commander places a turtle in front of itself with `turtle.place()`. The new turtle's position is the
   commander's position plus one block along the commander's heading.
2. It calls `peripheral.wrap("front")` on the placed turtle. `getID()` gives the new turtle's computer ID even before
   it has booted.
3. It sends `POST /api/turtles/{childId}/seed` with that position, the child's heading and
   `"source": "commander:<commanderId>"`. This is why the seed endpoint accepts IDs that haven't connected yet.
4. It calls `turnOn()` on the peripheral. The child boots, connects, and gets its seed right after its `hello`.

Still to check in-game: which way a turtle placed by another turtle faces, and whether a placed turtle already has
an ID before its first boot (a labelled turtle keeps its ID and files when picked up).
