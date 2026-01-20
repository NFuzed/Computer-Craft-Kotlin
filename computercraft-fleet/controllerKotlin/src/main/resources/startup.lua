-- startup.lua
-- Fetch latest boot.lua from Kotlin server; fall back to cached boot.lua if offline.

local BASE = "http://192.168.1.95:8000"
local BOOT_URL = BASE .. "/bootstrap/boot.lua"
local CACHED_BOOT = "boot.lua"

local function httpGet(url)
    local res = http.get(url, { ["Cache-Control"] = "no-cache" })
    if not res then
        return nil
    end
    local body = res.readAll()
    res.close()
    return body
end

local function readFile(path)
    if not fs.exists(path) then
        return nil
    end
    local f = fs.open(path, "r")
    local c = f.readAll()
    f.close()
    return c
end

local function writeFileAtomic(path, content)
    local tmp = path .. ".tmp"
    local dir = fs.getDir(path)
    if dir ~= "" and not fs.exists(dir) then
        fs.makeDir(dir)
    end

    local f = fs.open(tmp, "w")
    f.write(content)
    f.close()

    if fs.exists(path) then
        fs.delete(path)
    end
    fs.move(tmp, path)
end

local function runCode(code, name, param)
    local fn, err = load(code, name, "t", _ENV)
    if not fn then
        error(err)
    end
    return fn(param)
end

print("STARTUP: fetching boot.lua...")
local bootCode = httpGet(BOOT_URL)

if bootCode then
    print("STARTUP: boot.lua downloaded, caching.")
    writeFileAtomic(CACHED_BOOT, bootCode)
else
    print("STARTUP: server unreachable, using cached boot.lua if available.")
    bootCode = readFile(CACHED_BOOT)
end

if not bootCode then
    error("STARTUP: No boot.lua available (server down + no cached boot.lua).")
end

return runCode(bootCode, "boot.lua", BASE)
