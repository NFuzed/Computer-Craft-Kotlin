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

-- 1A) Ensure sha1 converter exists
if not fs.exists(SHA1_CONVERTER_PATH) then
    local body, err = httpGet(SERVER .. "/files/bootstrap/sha1converter.lua")
    if not body then
        error(err)
    end
    writeFile(SHA1_CONVERTER_PATH, body)
end

-- 1B) Ensure updater exists
if not fs.exists(UPDATER_PATH) then
    local body, err = httpGet(SERVER .. "/files/bootstrap/updater.lua")
    if not body then
        error(err)
    end
    writeFile(UPDATER_PATH, body)
end

-- 2) Run updater (it will update only what changed)
local ok, entryOrErr = pcall(function()
    return dofile(UPDATER_PATH).update(MANIFEST_URL, SERVER)
end)

if not ok then
    print("Updater error: " .. tostring(entryOrErr))
    print("Continuing anyway...")
else
    -- 3) Run entry
    if entryOrErr and fs.exists(entryOrErr) then
        dofile(entryOrErr)
    else
        print("No entry returned; check manifest.")
    end
end

