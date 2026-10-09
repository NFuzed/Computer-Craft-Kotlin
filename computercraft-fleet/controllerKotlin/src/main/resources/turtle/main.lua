-- turtle/main.lua (entry program, run by boot.lua with the server base URL)
local SERVER = ...
local net = dofile("turtle/lib/net.lua")

local ok, err = net.register(SERVER)
if ok then
    print(("NET: registered as turtle %d"):format(os.getComputerID()))
else
    -- the heartbeat loop keeps retrying, so we carry on
    print("NET: " .. tostring(err))
end

if ok then
    local posOk, posErr = net.reportPosition(SERVER)
    if not posOk then
        print("NET: " .. tostring(posErr))
    end
end

-- The heartbeat keeps the turtle online on the dashboard while it runs; Ctrl+T to stop.
net.heartbeatLoop(SERVER)
