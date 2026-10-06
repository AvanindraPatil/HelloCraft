const {lua, lauxlib, lualib, to_luastring} = require("fengari");
const src = require("fs").readFileSync(require("path").join(__dirname, "..", "Scripts", "main.lua"), "utf8");
const a = src.indexOf("local function bodyMask("), b = src.indexOf("-- Occupancy of Minecraft cell");
const t = src.slice(a, b) + `
-- body box inside cell (10, 2, 5): x 10.0..10.25 (qx 0), y 2.0..2.5 (qy 0,1), z 5.0..5.25 (qz 0) -> bits 0 and 4
local m = bodyMask(10, 2, 5, { x0 = 9.9, x1 = 10.1, y0 = 2.05, y1 = 2.4, z0 = 4.9, z1 = 5.1 })
assert(m == ((1 << 0) | (1 << 4)), "mask " .. m)
-- no overlap -> 0; nil body -> 0
assert(bodyMask(10, 2, 5, { x0 = 0, x1 = 1, y0 = 0, y1 = 1, z0 = 0, z1 = 1 }) == 0, "far")
assert(bodyMask(10, 2, 5, nil) == 0, "nil")
-- touching exactly at a boundary does NOT count (x1 == 10.0)
assert(bodyMask(10, 2, 5, { x0 = 9.5, x1 = 10.0, y0 = 2.0, y1 = 2.25, z0 = 5.0, z1 = 5.25 }) == 0, "touching")
-- a scan result with the body bits cleared, the rest kept
local scan = 0x3F
assert((scan & ~m) == 0x2E, "and-not")
print("bodyMask OK")`;
const L = lauxlib.luaL_newstate(); lualib.luaL_openlibs(L);
if (lauxlib.luaL_dostring(L, to_luastring(t)) !== 0) { console.log("FAIL:", lua.lua_tojsstring(L, -1)); process.exit(1); }
