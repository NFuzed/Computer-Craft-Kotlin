-- turtle/lib/net.lua
-- Talks to the Kotlin server's /api routes: register, heartbeat (which delivers seeds), position.
local M = {}

local PROTOCOL_VERSION = 1
local HEARTBEAT_INTERVAL = 10 -- seconds; the server marks a turtle offline after 30s
local JSON_HEADERS = { ["Content-Type"] = "application/json" }

local function turtleUrl(server, suffix)
    return ("%s/api/turtles/%d/%s"):format(server, os.getComputerID(), suffix)
end

-- Returns status code and body, or nil and an error message if the request never got a response.
local function post(url, body)
    local h, err, failH = http.post(url, body or "", JSON_HEADERS)
    h = h or failH
    if not h then
        return nil, err
    end
    local code = h.getResponseCode()
    local text = h.readAll()
    h.close()
    return code, text
end

function M.register(server)
    local code, body = post(turtleUrl(server, "register"), textutils.serializeJSON({
        v = PROTOCOL_VERSION,
        type = "register",
        turtleId = os.getComputerID(),
        label = os.getComputerLabel()
    }))
    if code ~= 200 then
        return false, ("register failed: %s %s"):format(tostring(code), tostring(body))
    end
    return true
end

-- Returns true and the decoded response if the server still knows this turtle.
-- The response may carry a position seed. A 404 means the server forgot us (restart).
function M.heartbeat(server)
    local code, body = post(turtleUrl(server, "heartbeat"))
    if code == 200 then
        return true, textutils.unserializeJSON(body) or {}
    elseif code == 404 then
        return false, "not registered"
    end
    return false, ("heartbeat failed: %s %s"):format(tostring(code), tostring(body))
end

-- Reports a position snapshot from nav.position(). Its seedId (if any) acknowledges the seed.
function M.reportPosition(server, pos)
    local code, body = post(turtleUrl(server, "position"), textutils.serializeJSON({
        v = PROTOCOL_VERSION,
        type = "position_update",
        turtleId = os.getComputerID(),
        x = pos.x,
        y = pos.y,
        z = pos.z,
        heading = pos.heading,
        world = pos.world,
        dimension = pos.dimension,
        seedId = pos.seedId,
        ts = math.floor(os.epoch("utc") / 1000)
    }))
    if code ~= 204 then
        return false, ("position failed: %s %s"):format(tostring(code), tostring(body))
    end
    return true
end

-- Runs forever: heartbeats every HEARTBEAT_INTERVAL seconds and re-registers if the server forgot us.
-- onTick(response) is called after every heartbeat, with the response if it succeeded or nil if not.
-- Network errors are logged and retried on the next tick, so a server outage never crashes the turtle.
function M.heartbeatLoop(server, onTick)
    while true do
        local ok, res = M.heartbeat(server)
        if not ok and res == "not registered" then
            ok, res = M.register(server)
            if ok then
                print("NET: re-registered with server")
                res = nil -- pick up any seed on the next heartbeat
            end
        end
        if not ok then
            print("NET: " .. tostring(res))
            res = nil
        end
        if onTick then
            onTick(res)
        end
        sleep(HEARTBEAT_INTERVAL)
    end
end

return M
