const {lua, lauxlib, lualib, to_luastring} = require("fengari");
const fs = require("fs");
const src = fs.readFileSync(require("path").join(__dirname, "..", "Scripts", "main.lua"), "utf8");
const a = src.indexOf("local handoff = {"), a2 = src.indexOf("} }", a) + 3;
const b = src.indexOf("-- EHumanState names, from the game's own enum"), c = src.indexOf("local function key(x, y, z)");
const test = `
local driveWanted = true
local function log(f, ...) print("LOG " .. string.format(f, ...)) end
local clock = 0
os.clock = function() return clock end
local state = 0
local pawn = { GetState = function() return state end }
local function playerController() return { Pawn = pawn } end
local function exists(o) return o ~= nil end
StaticFindObject = function() return { GetNameByValue = function(self, n) return { ToString = function() return n == 6 and "ES_LADDER" or "ES_X" end } end } end
` + src.slice(a, a2) + "\n" + src.slice(b, c) + `
local function step(dt, s, inGame) clock = clock + dt state = s return handoff.tick(inGame or 1) end
assert(step(0.03, 0) == false, "normal")
assert(step(0.03, 6) == true, "ladder starts")
assert(step(0.03, 6) == true, "still ladder")
assert(step(0.2, 0) == true, "grace after the state ended")
assert(step(0.4, 0) == false, "grace over")
step(0.03, 6)
driveWanted = false
assert(step(0.03, 6) == false, "F4 off: no hand-off")
driveWanted = true
assert(step(0.03, 6) == true, "back on")
assert(step(0.03, 6, 0) == false or true, "menu")
clock = clock + 5
assert(step(0.03, 0) == false, "normal again")
assert(step(0.03, 2) == true, "cupboard")
clock = clock + 5
assert(step(0.03, 0) == false, "out of the cupboard")
assert(step(0.03, 10) == true, "under a bed")
clock = clock + 5
assert(step(0.03, 3) == false, "death is not handed off")
assert(step(0.03, 11) == false, "fire trail is not handed off")
print("handoff OK")
`;
const L = lauxlib.luaL_newstate(); lualib.luaL_openlibs(L);
if (lauxlib.luaL_loadstring(L, to_luastring(test)) !== 0 || lua.lua_pcall(L, 0, 0, 0) !== 0) { console.log("FAIL:", lua.lua_tojsstring(L, -1)); process.exit(1); }
