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
    -- while a turtle call runs, other events (including turtle_inventory) are dropped, so report it here
    reportInventory()
    local sent, err = net.reportCommandResult(msg.commandId, msg.command, success == true,
            not success and tostring(reason) or nil)
    if not sent then
        print("NET: " .. tostring(err))
    end
end

local function onConnect()
    reportPosition()
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
    elseif msg.type == "error" then
        print("NET: server error: " .. tostring(msg.error))
    end
end

-- The connection keeps the turtle online on the dashboard while it runs; Ctrl+T to stop.
net.run(SERVER, {
    onConnect = onConnect,
    onMessage = onMessage,
    onEvent = function(event)
        if not net.isConnected() then
            return
        end
        -- fires when items are added, removed or moved (but not when the selected slot changes), and when equipping
        -- swaps an item between the inventory and a side
        if event == "turtle_inventory" then
            reportInventory()
            reportEquipment()
        elseif event == "peripheral" or event == "peripheral_detach" then
            -- a peripheral upgrade can attach a tick after the turtle_inventory; unchanged equipment isn't resent
            reportEquipment()
        end
    end
})
