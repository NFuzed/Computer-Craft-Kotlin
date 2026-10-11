-- /startup.lua (downloaded from /bootstrap/boot.lua)
local SERVER = ...
local MANIFEST_URL = SERVER .. "/manifest.json"
local UPDATER_PATH = "bootstrap/updater.lua"
local SHA1_CONVERTER_PATH = "bootstrap/sha1converter.lua"

local function ensureDir(path)
    local dir = fs.getDir(path)
    if dir and dir ~= "" and not fs.exists(dir) then
        fs.makeDir(dir)
    end
end

local function httpGet(url)
    local h = http.get(url)
    if not h then
        return nil, "http.get failed: " .. url
    end
    local body = h.readAll()
    h.close()
    return body
end

local function writeFile(path, content)
    ensureDir(path)
    local f = fs.open(path, "w")
    f.write(content)
    f.close()
end

local RETRY_DELAY = 5

-- Keep calling fn until it succeeds, so a turtle booted while the server is down waits for it.
local function retryUntilOk(label, fn)
    while true do
        local ok, result = pcall(fn)
        if ok then
            return result
        end
        print(label .. " failed: " .. tostring(result))
        print(("Server not ready; retrying in %ds..."):format(RETRY_DELAY))
        sleep(RETRY_DELAY)
    end
end

local function ensureFile(path)
    if fs.exists(path) then
        return
    end
    retryUntilOk("Download " .. path, function()
        local body, err = httpGet(SERVER .. "/files/" .. path)
        if not body then
            error(err, 0)
        end
        writeFile(path, body)
    end)
end

-- 1) Ensure sha1 converter and updater exist
ensureFile(SHA1_CONVERTER_PATH)
ensureFile(UPDATER_PATH)

-- 2) Run updater (it will update only what changed)
local entryPath = retryUntilOk("Updater", function()
    return dofile(UPDATER_PATH).update(MANIFEST_URL, SERVER)
end)

-- 3) Run entry, passing the server base URL as its argument
if entryPath and fs.exists(entryPath) then
    local entry = assert(loadfile(entryPath, nil, _ENV))
    entry(SERVER)
else
    print("No entry returned; check manifest.")
end

