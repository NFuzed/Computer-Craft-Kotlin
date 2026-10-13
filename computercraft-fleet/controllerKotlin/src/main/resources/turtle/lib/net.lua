-- turtle/lib/net.lua
-- Keeps a WebSocket open to the Kotlin server's /ws/turtle. The turtle opens the connection (it can't accept
-- incoming ones); after that the server pushes seeds, renames and slot selections down it, and we send positions,
-- labels, the inventory and equipment up.
local M = {}

local PROTOCOL_VERSION = 1
local RETRY_DELAY = 5 -- seconds between reconnect attempts
-- Reconnect if nothing arrives from the server for this many seconds. The server sends a heartbeat every 10 s
-- (Protocol.HEARTBEAT_INTERVAL_SECONDS); without one, a connection that died without closing (e.g. the server's PC
-- went to sleep) would look open forever.
local WATCHDOG_TIMEOUT = 30

local socket = nil -- the open websocket, or nil while disconnected

-- http(s)://host -> ws(s)://host/ws/turtle
local function socketUrl(server)
    return (server:gsub("^http", "ws")) .. "/ws/turtle"
end

-- Sends a message table as JSON. Returns false and a reason if we're not connected or the send failed.
function M.send(msg)
    if not socket then
        return false, "not connected"
    end
    msg.v = PROTOCOL_VERSION
    msg.turtleId = os.getComputerID()
    local ok, err = pcall(socket.send, textutils.serializeJSON(msg))
    if not ok then
        return false, err
    end
    return true
end

function M.isConnected()
    return socket ~= nil
end

-- Reports a position snapshot from nav.position(). Its seedId acknowledges the seed it is based on.
function M.reportPosition(pos)
    return M.send({
        type = "position_update",
        x = pos.x,
        y = pos.y,
        z = pos.z,
        heading = pos.heading,
        world = pos.world,
        dimension = pos.dimension,
        seedId = pos.seedId,
        ts = math.floor(os.epoch("utc") / 1000)
    })
end

-- Tells the server our current label, which acknowledges a rename.
function M.reportLabel()
    return M.send({ type = "label_update", label = os.getComputerLabel() })
end

-- Reports every non-empty inventory slot, the selected slot and the fuel level. The server replaces what it had
-- with this.
function M.reportInventory()
    local slots = {}
    for slot = 1, 16 do
        local item = turtle.getItemDetail(slot)
        if item then
            slots[#slots + 1] = { slot = slot, name = item.name, count = item.count, nbt = item.nbt }
        end
    end
    local level = turtle.getFuelLevel()
    return M.send({
        type = "inventory_update",
        selectedSlot = turtle.getSelectedSlot(),
        -- an empty Lua table would be sent as {}, not []
        slots = #slots > 0 and slots or textutils.empty_json_array,
        -- level and limit are left out (null) when fuel is unlimited
        fuel = level == "unlimited" and {} or { level = level, limit = turtle.getFuelLimit() },
        ts = math.floor(os.epoch("utc") / 1000)
    })
end

-- What is equipped on one side: { item = id or nil, peripheralTypes = {...} }, or nil if nothing is.
-- getEquipped is turtle.getEquippedLeft/Right, which older CC: Tweaked versions don't have; without it we can only
-- see peripheral upgrades, and an adjacent block's peripheral on that side looks the same as one.
local function equippedSide(side, getEquipped)
    local item
    if getEquipped then
        local detail = getEquipped()
        if not detail then
            -- nothing equipped; a peripheral on this side belongs to the block next to us
            return nil
        end
        item = detail.name
    end
    local types = { peripheral.getType(side) }
    if not item and #types == 0 then
        return nil
    end
    return { item = item, peripheralTypes = types }
end

-- The turtle's upgrades, as sent in an equipment_update (without type, turtleId or ts).
function M.equipment()
    return {
        left = equippedSide("left", turtle.getEquippedLeft),
        right = equippedSide("right", turtle.getEquippedRight),
        itemsKnown = turtle.getEquippedLeft ~= nil
    }
end

-- Reports equipment from M.equipment(). The server works out what the turtle can do from it.
function M.reportEquipment(equipment)
    local function side(s)
        -- an empty Lua table would be sent as {}, not []
        return s and { item = s.item, peripheralTypes = #s.peripheralTypes > 0 and s.peripheralTypes or textutils.empty_json_array }
    end
    return M.send({
        type = "equipment_update",
        left = side(equipment.left),
        right = side(equipment.right),
        itemsKnown = equipment.itemsKnown,
        ts = math.floor(os.epoch("utc") / 1000)
    })
end

-- Tells the server how a command went. err is the turtle API's reason when success is false.
function M.reportCommandResult(commandId, command, success, err)
    return M.send({
        type = "command_result",
        commandId = commandId,
        command = command,
        success = success,
        error = err,
        ts = math.floor(os.epoch("utc") / 1000)
    })
end

-- Tells the server how a refuel went. fuelAdded is nil when fuel is unlimited.
function M.reportRefuelResult(refuelId, refueled, err, fuelAdded)
    return M.send({
        type = "refuel_result",
        refuelId = refuelId,
        refueled = refueled,
        error = err,
        fuelAdded = fuelAdded,
        ts = math.floor(os.epoch("utc") / 1000)
    })
end

-- Runs forever: connects, says hello, and hands every message from the server to handlers.onMessage(msg).
-- handlers.onConnect() runs after each (re)connect, and every other event goes to handlers.onEvent(event, ...). If the connection drops or the server is down, it retries
-- every RETRY_DELAY seconds, so a server outage never crashes the turtle. A connection the server has gone quiet on
-- for WATCHDOG_TIMEOUT seconds counts as dropped.
function M.run(server, handlers)
    local url = socketUrl(server)
    while true do
        local ws, err = http.websocket(url)
        if ws then
            socket = ws
            if M.send({ type = "hello", label = os.getComputerLabel() }) then
                print(("NET: connected as turtle %d"):format(os.getComputerID()))
                if handlers.onConnect then
                    handlers.onConnect()
                end
            end
            -- restarted on every message, so it only fires after WATCHDOG_TIMEOUT seconds of silence. Restarting also
            -- covers a turtle command run by onMessage dropping the timer event while it waits.
            local watchdog = os.startTimer(WATCHDOG_TIMEOUT)
            while socket do
                local event, eventUrl, arg = os.pullEvent()
                if eventUrl == url and event == "websocket_message" then
                    local msg = textutils.unserializeJSON(arg)
                    if type(msg) == "table" and msg.type ~= "heartbeat" and handlers.onMessage then
                        handlers.onMessage(msg)
                    end
                    watchdog = os.startTimer(WATCHDOG_TIMEOUT)
                elseif eventUrl == url and event == "websocket_closed" then
                    socket = nil
                    print("NET: disconnected (" .. tostring(arg) .. ")")
                elseif event == "timer" and eventUrl == watchdog then
                    pcall(ws.close)
                    socket = nil
                    print(("NET: nothing from the server for %d s, reconnecting"):format(WATCHDOG_TIMEOUT))
                elseif handlers.onEvent then
                    handlers.onEvent(event, eventUrl, arg)
                end
            end
        else
            print("NET: " .. tostring(err))
        end
        sleep(RETRY_DELAY)
    end
end

return M
