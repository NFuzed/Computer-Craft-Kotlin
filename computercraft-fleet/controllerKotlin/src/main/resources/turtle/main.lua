-- turtle/main.lua (entry program, run by boot.lua with the server base URL)
local SERVER = ...
local net = dofile("turtle/lib/net.lua")
local nav = dofile("turtle/lib/nav.lua")

nav.load()
if nav.isSeeded() then
    local p = nav.position()
    print(("NAV: at %d %d %d facing %s"):format(p.x, p.y, p.z, p.heading))
else
    print("NAV: no position yet - seed this turtle from the dashboard")
end

-- Sends the position if it changed since the last report (or `force` is set). If we're offline it stays dirty,
-- and goes out when we reconnect.
local function reportPosition(force)
    if not (nav.isDirty() or (force and nav.isSeeded())) then
        return
    end
    local pos = nav.position()
    local ok, err = net.reportPosition(pos)
    if ok then
        nav.markReported(pos)
    else
        print("NET: " .. tostring(err))
    end
end

local function reportInventory()
    local ok, err = net.reportInventory()
    if not ok then
        print("NET: " .. tostring(err))
    end
end

-- Inspects the blocks in front of, above and below the turtle and sends them for the server's world map. Each
-- inspect takes a tick, and other events are dropped meanwhile, like any turtle call. Does nothing until seeded,
-- since the blocks' coordinates come from our position.
local INSPECT = { forward = turtle.inspect, up = turtle.inspectUp, down = turtle.inspectDown }

local function reportSurroundings()
    local adjacent = nav.adjacent()
    if not adjacent then
        return
    end
    local blocks = {}
    for dir, inspect in pairs(INSPECT) do
        local found, block = inspect()
        local p = adjacent[dir]
        blocks[#blocks + 1] = { x = p.x, y = p.y, z = p.z, name = found and block.name or "minecraft:air" }
    end
    local pos = nav.position()
    local ok, err = net.reportBlocks(pos.world, pos.dimension, blocks)
    if not ok then
        print("NET: " .. tostring(err))
    end
end

-- Equipment last sent, as "left|right" item and peripheral names, so events that don't change it aren't resent.
local sentEquipment

local function equipmentKey(equipment)
    local function side(s)
        return s and (tostring(s.item) .. ":" .. table.concat(s.peripheralTypes, ",")) or "-"
    end
    return side(equipment.left) .. "|" .. side(equipment.right)
end

-- Sends the equipment if it changed since the last report (or `force` is set, e.g. after reconnecting).
local function reportEquipment(force)
    local equipment = net.equipment()
    local key = equipmentKey(equipment)
    if key == sentEquipment and not force then
        return
    end
    local ok, err = net.reportEquipment(equipment)
    if ok then
        sentEquipment = key
    else
        print("NET: " .. tostring(err))
    end
end

-- Burns msg.count items from the selected slot (nil = the whole stack) and tells the server how it went.
local function refuel(msg)
    local before = turtle.getFuelLevel()
    -- turtle.refuel returns false and a reason if nothing burned, and errors on a bad count
    local ok, refueled, reason = pcall(turtle.refuel, msg.count)
    if not ok then
        refueled, reason = false, refueled
    end
    local after = turtle.getFuelLevel()
    local added = type(before) == "number" and type(after) == "number" and after - before or nil

    if refueled then
        print("FUEL: refuelled to " .. tostring(after))
    else
        print("FUEL: can't refuel: " .. tostring(reason))
    end
    local sent, err = net.reportRefuelResult(msg.refuelId, refueled, not refueled and tostring(reason) or nil, added)
    if not sent then
        print("NET: " .. tostring(err))
    end
    -- burning items fires turtle_inventory, but report anyway so the fuel level goes out even if nothing burned
    reportInventory()
end

-- Commands the dashboard can send (protocol.api.Commands). Moves and turns go through nav so dead reckoning stays
-- right; nav refuses them until the turtle is seeded.
local COMMANDS = {
    forward = nav.forward, back = nav.back, up = nav.up, down = nav.down,
    turnLeft = nav.turnLeft, turnRight = nav.turnRight,
    dig = turtle.dig, digUp = turtle.digUp, digDown = turtle.digDown,
    place = turtle.place, placeUp = turtle.placeUp, placeDown = turtle.placeDown,
    drop = turtle.drop, dropUp = turtle.dropUp, dropDown = turtle.dropDown,
    suck = turtle.suck, suckUp = turtle.suckUp, suckDown = turtle.suckDown,
    attack = turtle.attack, attackUp = turtle.attackUp, attackDown = turtle.attackDown
}

-- Runs msg.command and tells the server how it went. The position and inventory go out first, so the dashboard
-- already has them when it sees the result.
local function runCommand(msg)
    local fn = COMMANDS[msg.command]
    local ok, success, reason
    if fn then
        -- the turtle API returns false and a reason when it can't; pcall catches anything that errors instead
        ok, success, reason = pcall(fn)
        if not ok then
            success, reason = false, success
        end
    else
        success, reason = false, "Unknown command"
    end
    print(("CMD: %s %s"):format(tostring(msg.command), success and "ok" or ("failed: " .. tostring(reason))))

    reportPosition()
    -- moves and turns change what's around us, digs and places change the blocks themselves, and a failed move shows
    -- what's in the way
    reportSurroundings()
    -- while a turtle call runs, other events (including turtle_inventory) are dropped, so report it here
    reportInventory()
    local sent, err = net.reportCommandResult(msg.commandId, msg.command, success == true,
            not success and tostring(reason) or nil)
    if not sent then
        print("NET: " .. tostring(err))
    end
end

-- Paths from the server (protocol.messages.PathMessage). They run in their own coroutine (runPaths, below) so that
-- messages keep arriving while the turtle moves: a turtle call drops other events while it waits, but only in the
-- coroutine that made it. onMessage sets currentPath; setting it to another path (or nil) stops the running one
-- before its next step.
local currentPath = nil
local PATH_QUEUED = "fleet_path_queued" -- event that wakes runPaths

-- action -> { move, dig, detect }, all going through nav for moves so dead reckoning stays right
local MOVES = {
    forward = { nav.forward, turtle.dig, turtle.detect },
    up = { nav.up, turtle.digUp, turtle.detectUp },
    down = { nav.down, turtle.digDown, turtle.detectDown },
    back = { nav.back } -- can't see or dig behind, so never digs
}
local TURNS = { turnLeft = nav.turnLeft, turnRight = nav.turnRight }
local MAX_DIGS = 16 -- falling sand and gravel refill a dug cell; give up after this many

-- Runs one step. Returns true, or false and the turtle API's reason.
local function runStep(step)
    local turn = TURNS[step.action]
    if turn then
        return turn()
    end
    local move = MOVES[step.action]
    if not move then
        return false, "Unknown action " .. tostring(step.action)
    end
    local go, dig, detect = move[1], move[2], move[3]
    if step.dig and dig then
        -- if it can't be dug (no tool, or unbreakable), the move fails and the server replans
        for _ = 1, MAX_DIGS do
            if not detect() or not dig() then
                break
            end
        end
    end
    return go()
end

local function finishPath(path, status, stepsDone, reason)
    if currentPath == path then
        currentPath = nil
    end
    print(("PATH: %s after %d of %d steps%s"):format(status, stepsDone, #path.steps,
            reason and (" (" .. tostring(reason) .. ")") or ""))
    reportInventory() -- moves burn fuel
    local ok, err = net.reportPathResult(path.pathId, status, stepsDone, reason)
    if not ok then
        print("NET: " .. tostring(err))
    end
end

-- Follows path.steps, reporting the position and what's around after each one. Stops when a step fails, when the
-- path is cancelled or replaced, or when the connection it came on drops, even if we've connected again since (the
-- server fails the trip when that connection closes, so a result would be ignored).
local function followPath(path)
    local pos = nav.position()
    local start = path.start
    if not pos or pos.x ~= start.x or pos.y ~= start.y or pos.z ~= start.z or pos.heading ~= start.heading then
        -- planned from somewhere else (we moved after the server planned it), so it would lead somewhere else
        reportPosition(true)
        return finishPath(path, "blocked", 0, "Not where the path starts")
    end
    for i, step in ipairs(path.steps) do
        if not net.isConnected() or net.connection() ~= path.connection then
            if currentPath == path then
                -- not a path that came on the new connection
                currentPath = nil
            end
            print("PATH: stopped, disconnected")
            return
        end
        if currentPath ~= path then
            return finishPath(path, "cancelled", i - 1)
        end
        local ok, reason = runStep(step)
        reportPosition()
        reportSurroundings()
        if not ok then
            return finishPath(path, reason == "Out of fuel" and "failed" or "blocked", i - 1, reason)
        end
    end
    finishPath(path, "done", #path.steps)
end

local function runPaths()
    while true do
        if currentPath then
            followPath(currentPath)
        else
            os.pullEvent(PATH_QUEUED)
        end
    end
end

local function onConnect()
    reportPosition()
    reportSurroundings()
    reportInventory()
    reportEquipment(true)
end

local function onMessage(msg)
    if msg.type == "rename" then
        -- msg.label is nil when the label is being cleared
        os.setComputerLabel(msg.label)
        net.reportLabel()
        print("NET: renamed to " .. (os.getComputerLabel() or "(no label)"))
    elseif msg.type == "seed" then
        if nav.applySeed(msg.seed) then
            local s = msg.seed
            print(("NAV: seeded by %s at %d %d %d facing %s"):format(s.source, s.x, s.y, s.z, s.heading))
            reportSurroundings()
        end
        -- report even for a seed we already applied: the server resends it until a report acknowledges it
        reportPosition(true)
    elseif msg.type == "select_slot" then
        -- turtle.select errors on a slot outside 1-16; report either way so the dashboard shows the real selection
        local ok, err = pcall(turtle.select, msg.slot)
        if not ok then
            print("NET: can't select slot " .. tostring(msg.slot) .. ": " .. tostring(err))
        end
        reportInventory()
    elseif msg.type == "refuel" then
        refuel(msg)
    elseif msg.type == "command" then
        runCommand(msg)
    elseif msg.type == "path" then
        -- replaces a path that's still running; runPaths finishes that one first
        msg.connection = net.connection()
        currentPath = msg
        os.queueEvent(PATH_QUEUED)
    elseif msg.type == "path_cancel" then
        if currentPath and currentPath.pathId == msg.pathId then
            currentPath = nil
        end
    elseif msg.type == "error" then
        print("NET: server error: " .. tostring(msg.error))
    end
end

-- The connection keeps the turtle online on the dashboard while it runs; Ctrl+T to stop.
parallel.waitForAny(runPaths, function()
    net.run(SERVER, {
        onConnect = onConnect,
        onMessage = onMessage,
        onEvent = function(event)
            if not net.isConnected() then
                return
            end
            -- fires when items are added, removed or moved (but not when the selected slot changes), and when
            -- equipping swaps an item between the inventory and a side
            if event == "turtle_inventory" then
                reportInventory()
                reportEquipment()
            elseif event == "peripheral" or event == "peripheral_detach" then
                -- a peripheral upgrade can attach a tick after the turtle_inventory; unchanged equipment isn't resent
                reportEquipment()
            end
        end
    })
end)
