local M = {}
local SHA1_PATH = "bootstrap/sha1converter.lua"
local sha1 = loadfile(SHA1_PATH)

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

local function readFile(path)
    if not fs.exists(path) then
        return nil
    end
    local f = fs.open(path, "r")
    local c = f.readAll()
    f.close()
    return c
end

local function writeFile(path, content)
    ensureDir(path)
    local f = fs.open(path, "w")
    f.write(content)
    f.close()
end

function M.update(manifestUrl, serverBase)
    print("Fetching manifest...")
    local manifestText, err = httpGet(manifestUrl)
    if not manifestText then
        error(err)
    end

    local manifest = textutils.unserializeJSON(manifestText)
    if not manifest or not manifest.files then
        error("Bad manifest")
    end

    local baseUrl = manifest.baseUrl
    if not baseUrl then
        -- fallback: use serverBase + /files
        baseUrl = serverBase .. "/files"
    end

    local updated = 0
    for _, file in ipairs(manifest.files) do
        local path = file.path
        local expected = file.sha1

        local current = readFile(path)
        local currentHash = current and sha1(current) or nil

        if currentHash ~= expected then
            print("Updating: " .. path)
            local body, ferr = httpGet(baseUrl .. "/" .. path)
            if not body then
                error(ferr)
            end
            writeFile(path, body)

            -- verify after write
            local verify = sha1(body)
            if verify ~= expected then
                error("Hash mismatch after download for " .. path)
            end

            updated = updated + 1
        else
            -- print("OK: " .. path) -- optional
        end
    end

    print(("Update complete. %d file(s) updated."):format(updated))
    return manifest.entry
end

return M
--TEST
