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
    │       │   └── ApiError.kt
    │       └── messages/
    │           ├── PositionUpdate.kt
    │           └── TurtleMessages.kt # Hello, LabelUpdate, SeedMessage, RenameMessage, ErrorMessage
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
            │   │   ├── TurtleSocketRoutes.kt # /ws/turtle (one WebSocket per running turtle)
            │   │   ├── BootstrapRoutes.kt # GET /bootstrap/boot.lua
            │   │   ├── ManifestRoutes.kt  # GET /manifest.json
            │   │   └── FileRoutes.kt      # GET /files/{path...}
            │   ├── service/
            │   │   ├── FleetService.kt    # Builds TurtleStatus; pushes seeds/renames to connected turtles
            │   │   └── TurtleSessions.kt  # The open /ws/turtle connection per turtle
            │   └── store/
            │       ├── PositionStore.kt   # In-memory + JSON-on-disk turtle positions
            │       ├── SeedStore.kt       # Pending position seeds, saved to disk
            │       ├── LabelStore.kt      # Pending renames, saved to disk
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
                    ├── main.lua     # Entry program: connects, then handles seeds and renames
                    └── lib/
                        ├── net.lua  # WebSocket connection to /ws/turtle (reconnects on drop)
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

| Method | Path                                   | Returns                                                                                                                                                                           |
|--------|----------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GET`  | `/bootstrap/boot.lua`                  | The bootstrap script, as plain text                                                                                                                                               |
| `GET`  | `/manifest.json`                       | The file manifest (path, SHA-1, entry point)                                                                                                                                      |
| `GET`  | `/files/{path...}`                     | Any file on the server's classpath under `resources/`, as raw bytes                                                                                                               |
| `GET`  | `/api/turtles`                         | `TurtleStatus[]` for every known turtle, sorted by ID                                                                                                                             |
| `GET`  | `/api/turtles/{id}`                    | One `TurtleStatus`, or `404`                                                                                                                                                      |
| `POST` | `/api/turtles/{id}/seed`               | Takes a `SeedRequest` (`x`, `y`, `z`, `heading`, `world?`, `dimension?`, `source?`) and returns the queued `PositionSeed` with `202`. The turtle doesn't have to be connected yet |
| `PUT`  | `/api/turtles/{id}/label`              | Takes a `LabelRequest` (`label?`; null or blank clears it, max 32 printable ASCII characters) and returns the turtle's `TurtleStatus` with `202`. See [Renaming](#renaming)       |
| `GET`  | `/api/turtles/{id}/inventory`          | The turtle's last reported `TurtleInventory`, or `404` if it never reported one. See [Inventory](#inventory)                                                                      |
| `POST` | `/api/turtles/{id}/inventory/selected` | Takes a `SelectSlotRequest` (`slot`, 1-16) and pushes it to the turtle, returning the request with `202`. `409` if the turtle isn't connected                                     |

`/api` responses are JSON. Errors come back as `{"error": "..."}` with status `400`, `404` or `409`. `TurtleStatus` (
in `protocol.api`) contains `turtleId`, `label`, `online`, `lastSeen`, `position`, `pendingSeed` (a seed the turtle
hasn't applied yet) and `pendingLabel` (a rename the turtle hasn't applied yet). A turtle counts as online while
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

### Turtle WebSocket (`/ws/turtle`)

Turtles can only make outgoing connections, so the server can't call a turtle directly. Instead each running turtle
opens `ws(s)://<server>/ws/turtle` with `http.websocket` and keeps it open. The server uses it to push seeds and
renames as soon as the dashboard sends them. There is no polling.

1. The turtle sends a `hello` (`turtleId`, `label?`) as its first message. Anything else, or nothing within 10 s,
   closes the connection with `1008`.
2. The server records the label, replaces any older connection from the same turtle (e.g. it rebooted before the
   old one timed out), then sends any pending `rename` and `seed`.
3. After that, the turtle sends `position_update`, `label_update` and `inventory_update` messages. The server pushes
   `seed` and `rename` whenever the API queues one, `select_slot` when the API asks for one, and replies `error` to a
   message it can't handle (the connection stays open).

The server pings every 15 s and drops a connection after 30 s without a reply. CC: Tweaked answers pings itself. If
the connection drops, `net.lua` reconnects every 5 s. Anything queued while a turtle is offline stays in
`data/seeds.json` / `data/labels.json` and is sent on its next `hello`.

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
          ├─ select_slot pushed → turtle.select(), inventory_update
          ├─ rename pushed → os.setComputerLabel(), label_update
          ├─ seed pushed → nav.applySeed(), position_update
          └─ connection dropped → retry every 5 s
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

| Message             | `type`             | Direction       | Fields                                                                                         |
|---------------------|--------------------|-----------------|------------------------------------------------------------------------------------------------|
| `Hello`             | `hello`            | turtle → server | `turtleId`, `label?`. Must be the first message                                                |
| `PositionUpdate`    | `position_update`  | turtle → server | `turtleId`, `x`, `y`, `z`, `heading?` (`N/E/S/W`), `world?`, `dimension?`, `seedId?`, `ts`     |
| `LabelUpdate`       | `label_update`     | turtle → server | `turtleId`, `label?`: the label the turtle now has                                             |
| `InventoryUpdate`   | `inventory_update` | turtle → server | `turtleId`, `selectedSlot`, `slots` (`InventorySlot[]`: `slot`, `name`, `count`, `nbt?`), `ts` |
| `SeedMessage`       | `seed`             | server → turtle | `seed` (a `PositionSeed`)                                                                      |
| `RenameMessage`     | `rename`           | server → turtle | `label?` (null clears the label)                                                               |
| `SelectSlotMessage` | `select_slot`      | server → turtle | `slot` (1-16)                                                                                  |
| `ErrorMessage`      | `error`            | server → turtle | `error`: why the last message was rejected                                                     |

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
2. The server pushes it to the turtle straight away, or on its next `hello` if it is offline. The turtle applies
   it, replacing any position it had. Seeding again is also how a drifted position is corrected.
3. The turtle then sends a position update. Every position update carries the `seedId` it is based on, and the
   first one to arrive acknowledges the seed and clears it on the server. Until then the server resends it on every
   connect, so the turtle ignores a seed whose `seedId` it has already applied (but still reports its position).

A turtle that has never been seeded doesn't move: the nav functions return `false, "not seeded"`.

## Renaming

A turtle's name is its ComputerCraft label, and the turtle sends it in every `hello`. So the server can't simply
store a new name, because the next `hello` would overwrite it. Renames go through the turtle instead:

1. The dashboard sends `PUT /api/turtles/{id}/label`. The server stores a `PendingLabel` in `data/labels.json` and
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
for that turtle and saves it to `data/inventories.json`, so `GET /api/turtles/{id}/inventory` still answers after a
server restart. The response's `ts` says how old it is.

`POST /api/turtles/{id}/inventory/selected` sends a `select_slot` to the turtle straight away. It isn't queued like
seeds and renames, because a selection made while the turtle is offline would likely be stale when it connects, so
an offline turtle gets `409`. The `202` only means the message was sent; the stored inventory shows the new
`selectedSlot` once the turtle's `inventory_update` arrives.

## Position storage

`PositionStore` keeps one `TurtlePosition` per `turtleId`. It stores them in a `ConcurrentHashMap` and saves them
to `data/positions.json`, relative to the working directory:

- On startup, it loads existing data from the file. A missing file is ignored, and so is one that is empty or can't be
  parsed.
- `upsert(id, pos)` writes the whole file to disk on every call. There is a note in the code to debounce or batch this
  later.
- `get(id)` returns one position, and `ids()` returns every turtle ID that has a position.
- The file is a JSON object keyed by turtle ID, for example `{"7": {"x": 10, ...}}`.

`TurtleRegistry` keeps each turtle's label and when it was last seen, in memory only. `TurtleSessions` keeps each
turtle's open WebSocket. `FleetService` combines them with the position, seed and label stores into the
`TurtleStatus` objects that `/api` returns. `data/` is git-ignored.

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

Not yet connected:

- [ ] `/ws/dashboard` live event stream for the frontend
- [ ] Dashboard UI for seeding (the API exists)
- [ ] Report position while moving (nav tracks it, but only seeds trigger a report so far)
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
