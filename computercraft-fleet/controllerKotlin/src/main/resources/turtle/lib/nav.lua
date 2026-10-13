-- turtle/lib/nav.lua
-- Dead-reckoning navigation: tracks position without GPS by counting successful moves.
-- The starting position comes from a seed (set from the dashboard). After that, every move
-- must go through this module so the saved position keeps matching the world.
--
-- Interrupted moves (chunk unload / reboot mid-move) are recovered with fuel: a successful
-- move costs exactly 1 fuel, so comparing fuel before and after tells us whether it happened.
local M = {}

local STATE_FILE = "state/nav.json"
local HEADINGS = { "N", "E", "S", "W" } -- clockwise, so turnRight = +1
local DELTA = { N = { 0, -1 }, E = { 1, 0 }, S = { 0, 1 }, W = { -1, 0 } } -- {dx, dz} per heading

-- {x, y, z, heading, world, dimension, seedId, pending = {move, fuel}}; nil until seeded
local state = nil
local changes = 0  -- bumped on every position change
local reported = 0 -- value of `changes` at the last successful report

local function save()
    local tmp = STATE_FILE .. ".tmp"
    local f = fs.open(tmp, "w")
    f.write(textutils.serializeJSON(state))
    f.close()
    if fs.exists(STATE_FILE) then
        fs.delete(STATE_FILE)
    end
    fs.move(tmp, STATE_FILE)
end

local function readState(path)
    if not fs.exists(path) then
        return nil
    end
    local f = fs.open(path, "r")
    local text = f.readAll()
    f.close()
    return textutils.unserializeJSON(text)
end

local function headingIndex(heading)
    for i, h in ipairs(HEADINGS) do
        if h == heading then
            return i
        end
    end
    error("nav: invalid heading " .. tostring(heading))
end

local function applyMove(move)
    local d = DELTA[state.heading]
    if move == "forward" then
        state.x, state.z = state.x + d[1], state.z + d[2]
    elseif move == "back" then
        state.x, state.z = state.x - d[1], state.z - d[2]
    elseif move == "up" then
        state.y = state.y + 1
    elseif move == "down" then
        state.y = state.y - 1
    end
    changes = changes + 1
end

-- Finishes a move that was in progress when the turtle shut down.
local function recoverPending()
    local p = state.pending
    state.pending = nil
    local fuel = turtle.getFuelLevel()
    if fuel == "unlimited" or p.fuel == "unlimited" then
        print(("NAV: can't verify interrupted '%s' (unlimited fuel); position may be off by one"):format(p.move))
    elseif fuel == p.fuel - 1 then
        applyMove(p.move)
        print(("NAV: recovered interrupted '%s'"):format(p.move))
    elseif fuel ~= p.fuel then
        print(("NAV: fuel changed by %d during '%s'; position may be wrong"):format(fuel - p.fuel, p.move))
    end
    save()
end

-- Loads the saved state. Call once at startup, before any movement.
function M.load()
    -- save() deletes then moves, so a shutdown in between leaves only the .tmp file
    state = readState(STATE_FILE) or readState(STATE_FILE .. ".tmp")
    if state and state.pending then
        recoverPending()
    end
    changes, reported = 1, 0 -- report whatever we have on boot
end

function M.isSeeded()
    return state ~= nil
end

-- Adopts a seed from the server. Returns false if it's the seed we already applied, because the
-- server resends it on every connect until our position report acknowledges it, and we may have moved since.
function M.applySeed(seed)
    if state and state.seedId == seed.seedId then
        return false
    end
    headingIndex(seed.heading) -- validate
    state = {
        x = seed.x,
        y = seed.y,
        z = seed.z,
        heading = seed.heading,
        world = seed.world,
        dimension = seed.dimension,
        seedId = seed.seedId
    }
    changes = changes + 1
    save()
    return true
end

-- Returns a snapshot of the position for reporting, or nil if not seeded.
-- Includes the seedId it is based on; the server ignores acks for a seed it no longer has.
function M.position()
    if not state then
        return nil
    end
    return {
        x = state.x,
        y = state.y,
        z = state.z,
        heading = state.heading,
        world = state.world,
        dimension = state.dimension,
        seedId = state.seedId,
        changes = changes
    }
end

-- The cells in front of, above and below the turtle, as { forward = {x, y, z}, up = ..., down = ... },
-- or nil if not seeded.
function M.adjacent()
    if not state then
        return nil
    end
    local d = DELTA[state.heading]
    return {
        forward = { x = state.x + d[1], y = state.y, z = state.z + d[2] },
        up = { x = state.x, y = state.y + 1, z = state.z },
        down = { x = state.x, y = state.y - 1, z = state.z }
    }
end

-- True if the position changed since the last successful report.
function M.isDirty()
    return state ~= nil and changes ~= reported
end

-- Call after a position snapshot was reported successfully.
function M.markReported(snapshot)
    reported = math.max(reported, snapshot.changes)
end

local function move(name, fn)
    if not state then
        return false, "not seeded"
    end
    state.pending = { move = name, fuel = turtle.getFuelLevel() }
    save()
    local ok, err = fn()
    state.pending = nil
    if ok then
        applyMove(name)
    end
    save()
    return ok, err
end

-- Same return values as the turtle API: true, or false and a reason.
function M.forward()
    return move("forward", turtle.forward)
end
function M.back()
    return move("back", turtle.back)
end
function M.up()
    return move("up", turtle.up)
end
function M.down()
    return move("down", turtle.down)
end

-- Turns cost no fuel, so they can't be recovered like moves. The new heading is saved before
-- turning; a shutdown mid-turn leaves the heading off by 90 degrees until the turtle is re-seeded.
local function turn(step, fn)
    if not state then
        return false, "not seeded"
    end
    state.heading = HEADINGS[(headingIndex(state.heading) - 1 + step) % 4 + 1]
    changes = changes + 1
    save()
    return fn()
end

function M.turnRight()
    return turn(1, turtle.turnRight)
end
function M.turnLeft()
    return turn(-1, turtle.turnLeft)
end

return M
