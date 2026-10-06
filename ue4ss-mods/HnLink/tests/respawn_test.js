const {lua, lauxlib, lualib, to_luastring} = require("fengari");
const fs = require("fs");
const src = fs.readFileSync(require("path").join(__dirname, "..", "Scripts", "main.lua"), "utf8");
const a = src.indexOf("-- Where a Minecraft death goes back to"), b = src.indexOf("-- Returns inGame(0/1)");
const test = `
local files = { ["hn_respawn.txt"] = "/Game/Maps/Old 1.0 2.0 3.0 90.0\\n" }
io = { open = function(name, mode)
    if mode == "r" then
        local data = files[name]
        if not data then return nil end
        return { lines = function() return data:gmatch("[^\\n]+") end, close = function() end }
    end
    local buf = {}
    return { write = function(self, s) buf[#buf + 1] = s end, close = function() files[name] = table.concat(buf) end }
end }
local function log(f, ...) print("LOG " .. string.format(f, ...)) end
` + src.slice(a, b) + `
assert(spawnPt.resp["/Game/Maps/Old"] and spawnPt.resp["/Game/Maps/Old"].Yaw == 90, "loaded from file")
local M = "/Game/Maps/Main_Level"
spawnPt.learn(M, { X = 100, Y = 200, Z = 50 }, 10)
assert(spawnPt.resp[M].X == 100, "first catch taken at once")
assert(files["hn_respawn.txt"]:find("Main_Level 100.0 200.0 50.0 10.0", 1, true), "saved")
assert(files["hn_respawn.txt"]:find("/Game/Maps/Old", 1, true), "other maps kept in the file")
spawnPt.learn(M, { X = 120, Y = 200, Z = 50 }, 10)
assert(spawnPt.resp[M].X == 100, "same place (within 100 uu): unchanged")
spawnPt.learn(M, { X = 5000, Y = 5000, Z = 50 }, 0)   -- dream sequence once
assert(spawnPt.resp[M].X == 100, "one-off: not used")
spawnPt.learn(M, { X = 100, Y = 200, Z = 50 }, 10)    -- normal again
spawnPt.learn(M, { X = 5000, Y = 5000, Z = 50 }, 0)   -- once more: still one-off (the candidate was reset)
assert(spawnPt.resp[M].X == 100, "still the old one")
spawnPt.learn(M, { X = 5010, Y = 5000, Z = 50 }, 0)   -- twice in a row now
assert(spawnPt.resp[M].X == 5010, "twice in a row: the new respawn point")
print("respawn OK")
`;
const L = lauxlib.luaL_newstate(); lualib.luaL_openlibs(L);
if (lauxlib.luaL_loadstring(L, to_luastring(test)) !== 0 || lua.lua_pcall(L, 0, 0, 0) !== 0) { console.log("FAIL:", lua.lua_tojsstring(L, -1)); process.exit(1); }
