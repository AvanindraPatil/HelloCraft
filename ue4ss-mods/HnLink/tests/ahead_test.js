const {lua, lauxlib, lualib, to_luastring} = require("fengari");
const fs = require("fs");
const src = fs.readFileSync(require("path").join(__dirname, "..", "Scripts", "main.lua"), "utf8");
const a = src.indexOf("st.AHEAD_S, st.AHEAD_MIN_SPEED"), b = src.indexOf("-- The player died in Minecraft");
const test = `
local st = {}
local MC_DRIVING = 16
local clock = 0
os.clock = function() return clock end
local asked, n = {}, 0
local function voxRequest(x, y, z) local k = x .. "," .. y .. "," .. z if not asked[k] then asked[k] = true n = n + 1 end end
local mc
local voxAhead = { list = {}, head = 1, scanned = 0 }
function voxAhead.key(x, y, z) return (x + 65536) * 17179869184.0 + (y + 65536) * 131072.0 + (z + 65536) end
local function sync() for _, p in ipairs(voxAhead.list) do voxRequest(p[1], p[2], p[3]) end end
` + src.slice(a, b) + `
-- walking (4 b/s): nothing asked
mc = { flags = 16, x = 0.5, y = 2, z = 0.5 } st.scanAhead() sync()
clock = 0.05 mc = { flags = 16, x = 0.7, y = 2, z = 0.5 } st.scanAhead() sync()
assert(n == 0, "walking asks nothing, got " .. n)
-- elytra along +x at 30 b/s
clock = 0.10 mc = { flags = 16, x = 2.2, y = 2, z = 0.5 } st.scanAhead() sync()
assert(n > 0, "flying asks cells")
assert(asked["40,2,0"], "cell ~1.25 s ahead asked")
assert(asked["46,2,0"], "cell 1.5 s ahead asked")
assert(not asked["60,2,0"], "not beyond 1.5 s")
assert(asked["10,1,-1"] and asked["10,4,1"], "neighbours and head height")
local before = n
st.scanAhead() sync() -- same cell, same direction: no re-ask
assert(n == before, "no re-ask")
-- not driving: resets
mc = { flags = 0, x = 0, y = 0, z = 0 } st.scanAhead() sync()
assert(st.prevMc == nil, "reset when not driving")
print("ahead OK " .. n .. " cells")
`;
const L = lauxlib.luaL_newstate(); lualib.luaL_openlibs(L);
if (lauxlib.luaL_loadstring(L, to_luastring(test)) !== 0 || lua.lua_pcall(L, 0, 0, 0) !== 0) { console.log("FAIL:", lua.lua_tojsstring(L, -1)); process.exit(1); }
