const {lua, lauxlib, lualib, to_luastring} = require("fengari");
const fs = require("fs");
const src = fs.readFileSync(require("path").join(__dirname, "..", "Scripts", "main.lua"), "utf8");
const a = src.indexOf("-- Doors (yours or the neighbour's)"), b = src.indexOf("-- The player died in Minecraft");
const test = `
local clock = 0
os.clock = function() return clock end
local st, ia = { endHookOn = true }, {}
local UNITS_PER_BLOCK, yOff = 84.0, 0.0
local MC_DRIVING = 16
local mc = { flags = 16 }
local voxAhead = { urgent = {} }
local function log(f, ...) print("LOG " .. string.format(f, ...)) end
local function describe(o) return "door" end
local function exists(o) return o ~= nil end
local doorX = 300.0                       -- the door's bounds centre moves while it swings
local door = { GetAddress = function() return 77 end, K2_GetActorLocation = function() return { X = 300, Y = 0, Z = 100 } end }
local far = { GetAddress = function() return 78 end, K2_GetActorLocation = function() return { X = 5000, Y = 0, Z = 100 } end }
function st.allOf() return { door, far } end
function ia.bounds(actor, only) return { X = doorX, Y = 0.0, Z = 100.0 }, { X = 50.0, Y = 5.0, Z = 100.0 } end
local pc = { Pawn = { K2_GetActorLocation = function() return { X = 0, Y = 0, Z = 100 } end } }
local function playerController() return pc end
` + src.slice(a, b) + `
local function frame() st.doorTick() clock = clock + 0.016 end
frame()
assert(st.doors[77] and not st.doors[78], "only the near door is watched")
frame() frame()
assert(#voxAhead.urgent == 0, "still door: nothing asked")
-- the door swings for 0.5 s
for i = 1, 30 do doorX = doorX + 1.5 frame() end
local n = #voxAhead.urgent
assert(n > 0, "moving door: cells asked")
for _, p in ipairs(voxAhead.urgent) do assert(p[4] == true, "forced") end
assert(n < 30 * 64, "not every frame (rate limited): " .. n)
-- stops: one final ask about 0.2 s later, then nothing more
for i = 1, 20 do frame() end
local after = #voxAhead.urgent
assert(after > n, "final ask after it stopped")
for i = 1, 30 do frame() end
assert(#voxAhead.urgent == after, "nothing more once settled")
print("door OK: " .. after .. " forced cell requests")
`;
const L = lauxlib.luaL_newstate(); lualib.luaL_openlibs(L);
if (lauxlib.luaL_loadstring(L, to_luastring(test)) !== 0 || lua.lua_pcall(L, 0, 0, 0) !== 0) { console.log("FAIL:", lua.lua_tojsstring(L, -1)); process.exit(1); }
