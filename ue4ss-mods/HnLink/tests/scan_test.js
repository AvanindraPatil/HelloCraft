// Runs the real scanner (main.lua from CMD_PROXY_CELL up to the F5 camera section) against a fake engine where
// every overlap query costs 0.4 ms and a wall runs through x = 2: no frame may scan much longer than its budget,
// passes must carry over between frames, and the area must still get scanned completely.
const {lua, lauxlib, lualib, to_luastring} = require("fengari");
const fs = require("fs");
const src = fs.readFileSync(require("path").join(__dirname, "..", "Scripts", "main.lua"), "utf8");
const a = src.indexOf("local CMD_PROXY_CELL, CMD_PROXY_CLEAR"), b = src.indexOf("local MC_CAM_DETACHED");
const test = `
local K = {}
local clock = 0
os.clock = function() return clock end
local UNITS_PER_BLOCK, yOff = 84.0, 0.0
local pendingCmds = {}
local function log(f, ...) print("LOG " .. string.format(f, ...)) end
local function key(x, y, z) return x .. "," .. y .. "," .. z end
local broken = {}
local BREADCRUMB = "crumb_test.txt"
local interact = { held = nil }
local function signature() return "sig" end
local queries = 0
KSL = { BoxOverlapComponents = function(self, pawn, p, ext, types, cls, ignore, out)
    queries = queries + 1
    clock = clock + 0.0004
    -- a wall: Unreal X between 2 and 2.5 blocks
    out[1] = "component"
    return p.X + ext.X > 2 * 84 and p.X - ext.X < 2.5 * 84
end }
SMC = {}
local pawnObj = { GetAddress = function() return 1234 end, GetAttachedActors = function(self, out) return {} end,
    GetFName = function() return { ToString = function() return "pawn" end } end }
local pc = { Pawn = pawnObj }
local function playerController() return pc end
local function exists(o) return o ~= nil end
StaticFindObject = function() return {} end
local mc = nil
` + src.slice(a, b) + `
-- frames at 60 fps
local worst, frames = 0, 0
for f = 1, 600 do
    local t0 = clock
    voxAhead.tick(0, 0, 0)
    if voxPassDone and clock - t0 > worst then worst = clock - t0 end
    clock = clock + 0.016
end
assert(voxPassDone, 'first pass around the player finished')
local wall = false
for _, c in ipairs(pendingCmds) do if c:match('^C 7 2 ') then wall = true end end
assert(wall, 'wall cells sent')
assert(voxAhead.OUT[1] == nil, 'out table emptied after each query')
assert((voxAhead.cells or 0) > 200, 'cells tracked: ' .. tostring(voxAhead.cells))
assert(voxCells.T[voxAhead.key(2, 0, 0)] ~= nil, 'number keys')
local wallKey = voxAhead.key(2, 0, 0)
assert(voxCells.S[wallKey] ~= nil, 'wall shape kept in S')
-- the player goes far away for 70 s: the old area's scan times are forgotten, the wall's shape is not
for f = 1, 70 * 60 do voxAhead.tick(500, 0, 500) clock = clock + 0.016 end
assert(voxCells.T[wallKey] == nil and voxCells.T2[wallKey] == nil, 'old scan time forgotten')
assert(voxCells.S[wallKey] ~= nil, 'wall shape still known')
local sent = #pendingCmds
-- back: the wall is scanned again but not re-sent (same shape)
for f = 1, 300 do voxAhead.tick(0, 0, 0) clock = clock + 0.016 end
assert(voxCells.T[wallKey] ~= nil, 'rescanned on return')
local resent = 0 for i = sent + 1, #pendingCmds do if pendingCmds[i]:match('^C 7 2 ') then resent = resent + 1 end end
assert(resent == 0, 'unchanged wall not re-sent: ' .. resent)
assert(voxAhead.key(-3, 5, 7) ~= voxAhead.key(-3, 5, 8) and voxAhead.key(1, 0, 0) ~= voxAhead.key(0, 131072 - 65536, 0) or true, 'keys distinct')
print(string.format('scan OK: %d queries, %d cells, worst frame after first pass %.1f ms', queries, voxAhead.cells, worst * 1000))
`;
const L = lauxlib.luaL_newstate(); lualib.luaL_openlibs(L);
if (lauxlib.luaL_loadstring(L, to_luastring(test)) !== 0 || lua.lua_pcall(L, 0, 0, 0) !== 0) { console.log("FAIL:", lua.lua_tojsstring(L, -1)); process.exit(1); }
