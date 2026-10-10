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

local function onConnect()
    reportPosition()
    reportInventory()
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
    elseif msg.type == "error" then
        print("NET: server error: " .. tostring(msg.error))
    end
end

-- The connection keeps the turtle online on the dashboard while it runs; Ctrl+T to stop.
net.run(SERVER, {
    onConnect = onConnect,
    onMessage = onMessage,
    onEvent = function(event)
        -- fires when items are added, removed or moved (but not when the selected slot changes)
        if event == "turtle_inventory" and net.isConnected() then
            reportInventory()
        end
    end
})
