const {lua, lauxlib, lualib, to_luastring} = require("fengari");
const fs = require("fs");
const src = fs.readFileSync(require("path").join(__dirname, "..", "Scripts", "main.lua"), "utf8");
// Extract the M-line parsing block exactly as it is in main.lua and run it on a real bridge line.
const a = src.indexOf("            -- v[1] = \"M\""), b = src.indexOf("            if mc.tick < prevTick");
const block = src.slice(a, b);
const test = `
local mc
local line = "M 49 -74.612345 2.190000 21.440000 12.500 -3.250 7 2 123456 1.6200 0.0000 1.6200 0.0000 -77.500 3.250 70.000"
` + block + `
assert(mc.flags == 49 and math.type(mc.flags) == "integer", "flags")
assert(mc.flags & 16 == 16, "driving bit")
assert(math.abs(mc.x + 74.612345) < 1e-9 and mc.y == 2.19 and mc.z == 21.44, "position")
assert(mc.ack == 7 and mc.slot == 2 and mc.tick == 123456, "ack/slot/tick")
assert(mc.eye == 1.62 and mc.camDY == 1.62 and mc.camYaw == -77.5 and mc.camPitch == 3.25 and mc.camFov == 70, "camera")
print("M-line parse OK: flags", mc.flags, "x", mc.x, "eye", mc.eye, "fov", mc.camFov)
-- old 10-field line (older bridge) still parses
line = "M 1 1.0 2.0 3.0 0 0 0 0 5 1.62"
` + block + `
assert(mc.flags == 1 and mc.tick == 5 and mc.camFov == nil, "old line")
print("old-format line OK")
`;
const L = lauxlib.luaL_newstate(); lualib.luaL_openlibs(L);
if (lauxlib.luaL_dostring(L, to_luastring(test)) !== 0) { console.log("FAIL:", lua.lua_tojsstring(L, -1)); process.exit(1); }
