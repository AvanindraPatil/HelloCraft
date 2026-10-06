// The game's invisible walls (hidden components that block) are solid for Minecraft only below the player's feet:
// they hold the player up but never stop them walking or flying. Visible blockers are always solid, overlap-only
// components never are.
const {lua, lauxlib, lualib, to_luastring} = require("fengari");
const fs = require("fs");
const src = fs.readFileSync(require("path").join(__dirname, "..", "Scripts", "main.lua"), "utf8");
const a = src.indexOf("function voxAhead.blocks"), b = src.indexOf("-- The reused position and extent tables");
const test = `
local voxAhead = { OUT = {} }
local voxQueries = 0
local function comp(addr, block, hidden)
    return { GetAddress = function() return addr end,
        GetCollisionResponseToChannel = function(self, ch) return (block and ch == 2) and 2 or 1 end,
        bHiddenInGame = hidden, IsVisible = function() return not hidden end,
        GetOwner = function() return { bHidden = false } end }
end
local current
KSL = { BoxOverlapComponents = function(self, pawn, p, ext, types, cls, ignore, out) out[1] = current return true end }
` + src.slice(a, b) + `
local ext = { X = 10, Y = 10, Z = 10 }
voxAhead.feetZ = 100
local function q(c, z) current = c voxAhead.blockCache = {} return voxAhead.query(nil, { X = 0, Y = 0, Z = z }, ext) end
local wall, invisible, trigger = comp(1, true, false), comp(2, true, true), comp(3, false, false)
assert(q(wall, 200) and q(wall, 50), 'visible wall solid above and below the feet')
assert(not q(invisible, 200), 'invisible wall passes at body height')
assert(q(invisible, 50), 'invisible blocker below the feet holds the player')
assert(not q(trigger, 50), 'trigger zone never solid')
voxAhead.blockCache = {}
assert(voxAhead.blocks(invisible) == "hidden" and voxAhead.blocks(wall) == true, 'classified')
print('hidden OK')
`;
const L = lauxlib.luaL_newstate(); lualib.luaL_openlibs(L);
if (lauxlib.luaL_loadstring(L, to_luastring(test)) !== 0 || lua.lua_pcall(L, 0, 0, 0) !== 0) { console.log("FAIL:", lua.lua_tojsstring(L, -1)); process.exit(1); }
