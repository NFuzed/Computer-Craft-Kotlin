# Computer-Craft-Kotlin

A Kotlin ([Ktor](https://ktor.io/)) control server for a fleet of [ComputerCraft: Tweaked](https://tweaked.cc/) turtles.

Right now the server's main job is **code delivery**. Each turtle runs a small `startup.lua`. On boot it fetches a
bootstrap script from the server, compares its local files to a SHA-1 manifest, downloads only the files that changed,
and then runs the entry program. The groundwork for live fleet control (WebSockets, handshake messages, position
tracking) is in place but not connected yet.

---

## Project layout

```
.
├── build.gradle.kts                 # Single Gradle build; also defines the genManifest task
├── settings.gradle.kts
└── computercraft-fleet/
    ├── protocol/                    # Types shared between the server and turtles
    │   └── src/main/kotlin/protocol/
    │       ├── Protocol.kt          # Protocol version + HTTP route paths
    │       ├── Json.kt              # Shared kotlinx.serialization Json config
    │       ├── api/
    │       │   ├── TurtleStatus.kt  # TurtleStatus + TurtlePosition (dashboard API)
    │       │   ├── PositionSeed.kt  # SeedRequest + PositionSeed (position seeding)
    │       │   └── ApiError.kt
    │       └── messages/
    │           ├── PositionUpdate.kt
    │           ├── RegisterRequest.kt
    │           └── HeartbeatResponse.kt
    │
    └── controllerKotlin/            # The Ktor server
        └── src/main/
            ├── kotlin/app/
            │   ├── Main.kt          # Starts Netty on 0.0.0.0:8000
            │   ├── Application.kt   # Ktor module: plugins, stores, routes
            │   ├── plugin/WebSockets.kt
            │   ├── route/
            │   │   ├── Routes.kt          # Registers all routes
            │   │   ├── ApiRoutes.kt       # /api/turtles (dashboard REST API)
            │   │   ├── BootstrapRoutes.kt # GET /bootstrap/boot.lua
            │   │   ├── ManifestRoutes.kt  # GET /manifest.json
            │   │   └── FileRoutes.kt      # GET /files/{path...}
            │   ├── service/FleetService.kt # Builds TurtleStatus from registry + positions + seeds
            │   └── store/
            │       ├── PositionStore.kt   # In-memory + JSON-on-disk turtle positions
            │       ├── SeedStore.kt       # Pending position seeds, saved to disk
            │       └── TurtleRegistry.kt  # Label + last-seen per turtle (in memory)
            └── resources/           # Lua code that gets served to turtles
                ├── startup.lua
                ├── manifest.template.json
                ├── manifest.json    # Generated; do not edit by hand
                ├── bootstrap/
                │   ├── boot.lua
                │   ├── updater.lua
                │   └── sha1converter.lua
                └── turtle/
                    ├── main.lua     # Entry program: registers, then heartbeats while it runs
                    └── lib/
                        ├── net.lua  # register / heartbeat / position calls to /api
                        └── nav.lua  # Dead-reckoning position tracking (no GPS)
```

Gradle builds everything as **one module**. The root `build.gradle.kts` adds the `protocol` and `controllerKotlin`
source directories to the main source set, so the server can use the protocol classes directly.

---

## Requirements

- **JDK 20.** The Kotlin toolchain is pinned to 20. The foojay resolver plugin can download it automatically.
- The Gradle wrapper, which is included.
- A Minecraft world with **CC: Tweaked** and the HTTP API enabled.
- A public URL that turtles can reach. The project currently uses an **ngrok** tunnel to `localhost:8000`.

---

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

## HTTP API

The route paths are defined in `protocol.Protocol.Paths`.

| Method | Path                          | Returns                                                                                                                                                                            |
|--------|-------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GET`  | `/bootstrap/boot.lua`         | The bootstrap script, as plain text                                                                                                                                                |
| `GET`  | `/manifest.json`              | The file manifest (path, SHA-1, entry point)                                                                                                                                       |
| `GET`  | `/files/{path...}`            | Any file on the server's classpath under `resources/`, as raw bytes                                                                                                                |
| `GET`  | `/api/turtles`                | `TurtleStatus[]` for every known turtle, sorted by ID                                                                                                                              |
| `GET`  | `/api/turtles/{id}`           | One `TurtleStatus`, or `404`                                                                                                                                                       |
| `POST` | `/api/turtles/{id}/register`  | Takes a `RegisterRequest` JSON body (`turtleId`, `label?`) and returns the turtle's `TurtleStatus`                                                                                 |
| `POST` | `/api/turtles/{id}/heartbeat` | Refreshes `lastSeen` and returns a `HeartbeatResponse` (with any pending seed), or `404` if the turtle isn't registered (it should register again)                                 |
| `POST` | `/api/turtles/{id}/position`  | Takes a `PositionUpdate` JSON body and returns `204`. This is temporary until turtles report over WebSocket                                                                        |
| `POST` | `/api/turtles/{id}/seed`      | Takes a `SeedRequest` (`x`, `y`, `z`, `heading`, `world?`, `dimension?`, `source?`) and returns the queued `PositionSeed` with `202`. The turtle doesn't have to be registered yet |

`/api` responses are JSON. Errors come back as `{"error": "..."}` with status `400` or `404`. `TurtleStatus` (
in `protocol.api`) contains `turtleId`, `label`, `online`, `lastSeen`, `position` and `pendingSeed` (a seed the turtle
hasn't applied yet). A turtle counts as online if the
server has heard from it in the last 30 seconds.

Example:

```bash
curl -X POST localhost:8000/api/turtles/7/position \
     -H "Content-Type: application/json" \
     -d '{"turtleId":7,"x":10,"y":64,"z":-3,"heading":"N"}'
curl localhost:8000/api/turtles
```

Seeding a turtle's position (what the dashboard will do):

```bash
curl -X POST localhost:8000/api/turtles/7/seed \
     -H "Content-Type: application/json" \
     -d '{"x":10,"y":64,"z":-3,"heading":"N"}'
```

A WebSocket plugin is installed with a 15 s ping and a 30 s timeout, but no WebSocket routes are registered yet.

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
   ├─ POST /api/turtles/{id}/register   (label from os.getComputerLabel())
   └─ now, then every 10 s: POST /api/turtles/{id}/heartbeat
          ├─ 404 (server restarted) → register again
          ├─ response has a new seed → nav.applySeed()
          └─ position changed → POST /api/turtles/{id}/position
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

These are `@Serializable` data classes in `protocol.messages`. Ktor encodes them with the
shared `ProtocolJson` (`ignoreUnknownKeys = true`, `encodeDefaults = true`):

| Message             | Direction       | Fields                                                                                                                                         |
|---------------------|-----------------|------------------------------------------------------------------------------------------------------------------------------------------------|
| `RegisterRequest`   | turtle → server | `v = Protocol.VERSION`, `type = "register"`, `turtleId`, `label?`                                                                              |
| `PositionUpdate`    | turtle → server | `v = Protocol.VERSION`, `type = "position_update"`, `turtleId`, `x`, `y`, `z`, `heading?` (`N/E/S/W`), `world?`, `dimension?`, `seedId?`, `ts` |
| `HeartbeatResponse` | server → turtle | `v = Protocol.VERSION`, `type = "heartbeat_ack"`, `seed?` (a `PositionSeed`)                                                                   |

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
   `data/seeds.json`. A newer seed replaces an older one.
2. On its next heartbeat, the turtle receives the seed and applies it, replacing any position it had. Seeding again
   is also how a drifted position is corrected.
3. The turtle's next position report includes the `seedId`, which acknowledges the seed and clears it on the server.
   Until then the server keeps sending it, so the turtle ignores a seed whose `seedId` it has already applied.

A turtle that has never been seeded doesn't move: the nav functions return `false, "not seeded"`.

## Position storage

`PositionStore` keeps one `TurtlePosition` per `turtleId`. It stores them in a `ConcurrentHashMap` and saves them
to `data/positions.json`, relative to the working directory:

- On startup, it loads existing data from the file. A missing file is ignored, and so is one that is empty or can't be
  parsed.
- `upsert(id, pos)` writes the whole file to disk on every call. There is a note in the code to debounce or batch this
  later.
- `get(id)` returns one position, and `ids()` returns every turtle ID that has a position.
- The file is a JSON object keyed by turtle ID, for example `{"7": {"x": 10, ...}}`.

`TurtleRegistry` keeps each turtle's label and when it was last seen, in memory only. `FleetService` combines the
registry and the position store into the `TurtleStatus` objects that `/api` returns. `data/` is git-ignored.

---

## Status / roadmap

Done:

- [x] Code delivery over HTTP with SHA-1-based incremental updates
- [x] Boot from a cached copy when the server is offline
- [x] Manifest hashes generated at build time
- [x] `/api/turtles` REST endpoints for the dashboard
- [x] Turtles register after boot and send a heartbeat every 10 s (over HTTP for now)
- [x] Dead-reckoning position tracking with fuel-based recovery of interrupted moves (no GPS)
- [x] Seed a turtle's position through the API

Not yet connected:

- [ ] `/ws/turtle` WebSocket session for turtles (plugin installed, no routes)
- [ ] `/ws/dashboard` live event stream for the frontend
- [ ] Dashboard UI for seeding (the API exists)
- [ ] Report position more often than once per heartbeat while moving
- [ ] **Commander turtle** that carries other turtles and seeds the ones it places (see below)
- [ ] Move register/heartbeat onto `/ws/turtle` once it exists
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
   `"source": "commander:<commanderId>"`. This is why the seed endpoint accepts IDs that haven't registered yet.
4. It calls `turnOn()` on the peripheral. The child boots, registers, and picks up its seed on its first heartbeat.

Still to check in-game: which way a turtle placed by another turtle faces, and whether a placed turtle already has
an ID before its first boot (a labelled turtle keeps its ID and files when picked up).
