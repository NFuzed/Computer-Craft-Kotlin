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

local ok, err = net.register(SERVER)
if ok then
    print(("NET: registered as turtle %d"):format(os.getComputerID()))
else
    -- the heartbeat loop keeps retrying, so we carry on
    print("NET: " .. tostring(err))
end

-- Runs after every heartbeat: applies a new seed, then reports the position if it changed.
local function sync(res)
    if not res then
        return -- server unreachable or just re-registered; try again next tick
    end
    if res.seed and nav.applySeed(res.seed) then
        local s = res.seed
        print(("NAV: seeded by %s at %d %d %d facing %s"):format(s.source, s.x, s.y, s.z, s.heading))
    end
    if nav.isDirty() then
        local pos = nav.position()
        local posOk, posErr = net.reportPosition(SERVER, pos)
        if posOk then
            nav.markReported(pos)
        else
            print("NET: " .. tostring(posErr))
        end
    end
end

-- The heartbeat keeps the turtle online on the dashboard while it runs; Ctrl+T to stop.
net.heartbeatLoop(SERVER, sync)
