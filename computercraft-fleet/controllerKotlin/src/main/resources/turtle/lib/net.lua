-- turtle/lib/net.lua
-- Talks to the Kotlin server's /api routes: register, heartbeat, position.
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

-- Returns true if the server still knows this turtle. A 404 means the server forgot us (restart).
function M.heartbeat(server)
    local code, body = post(turtleUrl(server, "heartbeat"))
    if code == 204 then
        return true
    elseif code == 404 then
        return false, "not registered"
    end
    return false, ("heartbeat failed: %s %s"):format(tostring(code), tostring(body))
end

-- Reports a GPS position if a GPS network is in range. Returns false if there is no fix.
function M.reportPosition(server, heading)
    local x, y, z = gps.locate(2)
    if not x then
        return false, "no GPS fix"
    end
    local code, body = post(turtleUrl(server, "position"), textutils.serializeJSON({
        v = PROTOCOL_VERSION,
        type = "position_update",
        turtleId = os.getComputerID(),
        x = math.floor(x),
        y = math.floor(y),
        z = math.floor(z),
        heading = heading,
        ts = math.floor(os.epoch("utc") / 1000)
    }))
    if code ~= 204 then
        return false, ("position failed: %s %s"):format(tostring(code), tostring(body))
    end
    return true
end

-- Runs forever: heartbeats every HEARTBEAT_INTERVAL seconds and re-registers if the server forgot us.
-- Network errors are logged and retried on the next tick, so a server outage never crashes the turtle.
function M.heartbeatLoop(server)
    while true do
        sleep(HEARTBEAT_INTERVAL)
        local ok, err = M.heartbeat(server)
        if not ok and err == "not registered" then
            ok, err = M.register(server)
            if ok then
                print("NET: re-registered with server")
            end
        end
        if not ok then
            print("NET: " .. tostring(err))
        end
    end
end

return M
