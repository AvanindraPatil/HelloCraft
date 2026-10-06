const {lua, lauxlib, lualib, to_luastring} = require("fengari");
const luaparse = require("luaparse");
const fs = require("fs");
const src = fs.readFileSync(require("path").join(__dirname, "..", "Scripts", "main.lua"), "utf8");
luaparse.parse(src, {luaVersion: "5.3"});
console.log("main.lua parses");
const a = src.indexOf("    local s, p, yw\n"), b = src.indexOf("    local e = { X = cam.X");
const b2 = src.indexOf("\n", src.indexOf("Z = s.Z + math.sin(p) * TRACE_UU }"));
let block = src.slice(a, b2 + 1);
block = block.replace("local e = {", "e = {").replace("Y = cam.Y +", "Y = s.Y +").replace("Z = cam.Z +", "Z = s.Z +");
console.log(block);
const test = `
local UNITS_PER_BLOCK, yOff, TRACE_UU = 84.0, 10.0, 100.0
local pc = nil
local e
local function run(mc)
` + block + `
return s, e
end
local s, e = run({ flags = 17, x = 2, y = 3, z = 4, yaw = 0, pitch = 0, eye = 1.62 })
assert(math.abs(s.X - 168) < 1e-9 and math.abs(s.Y - 336) < 1e-9 and math.abs(s.Z - (4.62*84 - 10)) < 1e-9, "eye")
-- MC yaw 0 looks south (+z) = host +Y
assert(math.abs(e.Y - s.Y - 100) < 1e-6 and math.abs(e.X - s.X) < 1e-6, "yaw 0 -> +Y")
-- MC yaw 90 looks west (-x) = host -X
s, e = run({ flags = 16, x = 0, y = 0, z = 0, yaw = 90, pitch = 0 })
assert(math.abs(e.X - s.X + 100) < 1e-6, "yaw 90 -> -X")
-- MC pitch 90 looks down = host -Z
s, e = run({ flags = 16, x = 0, y = 0, z = 0, yaw = 0, pitch = 90 })
assert(math.abs(e.Z - s.Z + 100) < 1e-6, "pitch 90 -> down")
print("aim from Minecraft's eye OK")
`;
const L = lauxlib.luaL_newstate(); lualib.luaL_openlibs(L);
if (lauxlib.luaL_dostring(L, to_luastring(test)) !== 0) { console.log("FAIL:", lua.lua_tojsstring(L, -1)); process.exit(1); }
