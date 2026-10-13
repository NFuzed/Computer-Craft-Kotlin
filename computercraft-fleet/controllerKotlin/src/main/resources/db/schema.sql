-- Everything the server keeps between restarts. Times are unix seconds, as in the protocol.
-- Run on every startup: it creates any table that doesn't exist yet and leaves existing ones alone, so changing a table
-- here doesn't change it in a database that already has it. Drop the table (or the database) to recreate it.

-- The last position each turtle reported (PositionStore).
CREATE TABLE IF NOT EXISTS turtle_position
(
    turtle_id
    INTEGER
    PRIMARY
    KEY,
    x
    INTEGER
    NOT
    NULL,
    y
    INTEGER
    NOT
    NULL,
    z
    INTEGER
    NOT
    NULL,
    heading
    TEXT,
    world
    TEXT,
    dimension
    TEXT,
    ts
    BIGINT
    NOT
    NULL
);

-- Seeds waiting to be picked up, at most one per turtle (SeedStore).
CREATE TABLE IF NOT EXISTS pending_seed
(
    turtle_id
    INTEGER
    PRIMARY
    KEY,
    seed_id
    BIGINT
    NOT
    NULL,
    x
    INTEGER
    NOT
    NULL,
    y
    INTEGER
    NOT
    NULL,
    z
    INTEGER
    NOT
    NULL,
    heading
    TEXT
    NOT
    NULL,
    world
    TEXT,
    dimension
    TEXT,
    source
    TEXT
    NOT
    NULL,
    ts
    BIGINT
    NOT
    NULL
);

-- Renames waiting to be picked up, at most one per turtle (LabelStore). A null label clears the turtle's label.
CREATE TABLE IF NOT EXISTS pending_label
(
    turtle_id
    INTEGER
    PRIMARY
    KEY,
    label
    TEXT,
    ts
    BIGINT
    NOT
    NULL
);

-- The last inventory each turtle reported (InventoryStore). Empty slots have no row.
CREATE TABLE IF NOT EXISTS turtle_inventory
(
    turtle_id
    INTEGER
    PRIMARY
    KEY,
    selected_slot
    INTEGER
    NOT
    NULL,
    fuel_reported
    BOOLEAN
    NOT
    NULL,    -- false for older turtle code that doesn't report fuel
    fuel_level
    INTEGER, -- null when fuel is unlimited
    fuel_limit
    INTEGER,
    ts
    BIGINT
    NOT
    NULL
);

CREATE TABLE IF NOT EXISTS inventory_slot
(
    turtle_id
    INTEGER
    NOT
    NULL
    REFERENCES
    turtle_inventory
(
    turtle_id
) ON DELETE CASCADE,
    slot INTEGER NOT NULL,
    name TEXT NOT NULL,
    count INTEGER NOT NULL,
    nbt TEXT,
    PRIMARY KEY
(
    turtle_id,
    slot
)
    );

-- The upgrades each turtle last reported or was seeded with (EquipmentStore). left/right are EquippedUpgrade as JSON,
-- null when empty.
CREATE TABLE IF NOT EXISTS turtle_equipment
(
    turtle_id
    INTEGER
    PRIMARY
    KEY,
    left_side
    JSONB,
    right_side
    JSONB,
    items_known
    BOOLEAN
    NOT
    NULL,
    capabilities
    TEXT
[]
    NOT
    NULL,
    seeded
    BOOLEAN
    NOT
    NULL
    DEFAULT
    FALSE,
    ts
    BIGINT
    NOT
    NULL
);
-- Added after the table was first released, so databases created before it get the column too
ALTER TABLE turtle_equipment
    ADD COLUMN IF NOT EXISTS seeded BOOLEAN NOT NULL DEFAULT FALSE;

-- What the turtles have seen of the world (WorldMapStore): the last block reported at each cell.
-- world and dimension are '' rather than null, since they're part of the primary key. The key starts with the chunk
-- (16x16 columns, like Minecraft's), so a box query looks up just the chunks it covers instead of every block at its
-- heights; within a chunk it matches how region queries sort (y, then z, then x). >> rounds down for negative
-- coordinates too, matching Minecraft.
CREATE TABLE IF NOT EXISTS world_block
(
    world
    TEXT
    NOT
    NULL,
    dimension
    TEXT
    NOT
    NULL,
    chunk_x
    INTEGER
    GENERATED
    ALWAYS AS
(
    x
    >>
    4
) STORED,
    chunk_z INTEGER GENERATED ALWAYS AS
(
    z
    >>
    4
) STORED,
    y INTEGER NOT NULL,
    z INTEGER NOT NULL,
    x INTEGER NOT NULL,
    name TEXT NOT NULL,
    turtle_id INTEGER,
    ts BIGINT NOT NULL,
    PRIMARY KEY
(
    world,
    dimension,
    chunk_x,
    chunk_z,
    y,
    z,
    x
)
    );

-- Places turtles go to refuel, drop items off or pick them up (StationStore). x, y, z is the cell the turtle stands
-- in; side ('up', 'down' or 'front') says where the chest is from there, and heading is which way the turtle faces.
CREATE TABLE IF NOT EXISTS station
(
    station_id
    BIGSERIAL
    PRIMARY
    KEY,
    name
    TEXT
    NOT
    NULL,
    type
    TEXT
    NOT
    NULL,
    x
    INTEGER
    NOT
    NULL,
    y
    INTEGER
    NOT
    NULL,
    z
    INTEGER
    NOT
    NULL,
    heading
    TEXT,
    side
    TEXT
    NOT
    NULL,
    world
    TEXT,
    dimension
    TEXT,
    ts
    BIGINT
    NOT
    NULL
);
