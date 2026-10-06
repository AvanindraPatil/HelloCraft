const {lua, lauxlib, lualib, to_luastring} = require("fengari");
const fs = require("fs");
const src = fs.readFileSync(require("path").join(__dirname, "..", "Scripts", "main.lua"), "utf8");
const a = src.indexOf("st.WINDOW, st.invAddrs"), b = src.indexOf("-- a,b,c: centre (Minecraft * 1000); d: radius * 100 (blocks).");
const test = `
local st = {}
local function exists(o) return o ~= nil end
local function risky(n, f, ...) return f(...) end
local function arrayOut(r, o) return r end
function FName(s) return s end
local thrown = {}
local function throwItemAt(p, dir, speed, back) thrown[#thrown+1] = { p, dir } return true end
local function comp(x, y, z) return { GetAddress = function() return 1 end, K2_GetComponentLocation = function() return { X = x, Y = y, Z = z } end } end
local W1 = { GlassMesh1 = comp(100, 0, 0), GlassMesh2 = comp(110, 0, 0), bCrashed2 = true }
local W2 = { GlassMesh1 = comp(5000, 0, 0) }
local imp = {}
local function item(addr, x, sim) local root = { sim = sim, IsSimulatingPhysics = function(self) return self.sim end, SetSimulatePhysics = function(self, v) self.sim = v end,
   AddImpulse = function(self, v) imp[#imp+1] = { addr, v } end }
  return { GetAddress = function() return addr end, K2_GetActorLocation = function() return { X = x, Y = 0, Z = 0 } end, K2_GetRootComponent = function() return root end, root = root } end
local I1, I2, I3 = item(11, 150, false), item(12, 9000, true), item(13, 50, true)
StaticFindObject = function(p) return { path = p } end
st.GAMEPLAY_STATICS = { GetAllActorsOfClass = function(self, pc, cls, out) if cls.path:find("Window3") then return { W1, W2 } end return { I1, I2, I3 } end }
` + src.slice(a, b) + `
st.invAddrs = { [13] = true }
local panes, windows = st.blastWindows({}, { X = 0, Y = 0, Z = 0 }, 500)
assert(panes == 1 and windows == 1, "panes " .. panes .. " windows " .. windows)
local n = st.blastProps({}, { X = 0, Y = 0, Z = 0 }, 500, 10)
assert(n == 1 and imp[1][1] == 11 and I1.root.sim == true, "props " .. n)
print("blast OK", panes, windows, n, imp[1][2].X, imp[1][2].Z)
`;
const L = lauxlib.luaL_newstate(); lualib.luaL_openlibs(L);
if (lauxlib.luaL_loadstring(L, to_luastring(test)) !== 0 || lua.lua_pcall(L, 0, 0, 0) !== 0) { console.log("FAIL:", lua.lua_tojsstring(L, -1)); process.exit(1); }
