const {lua, lauxlib, lualib, to_luastring} = require("fengari");
const fs = require("fs");
const src = fs.readFileSync(require("path").join(__dirname, "..", "Scripts", "main.lua"), "utf8");
const a = src.indexOf("local function exists(o)"), b = src.indexOf("local function key(x, y, z)");
const test = `
local handoff = { STATES = {} }
local driveWanted = true
local PCC = { tag = "pcclass", GetFullName = function() return "Class /Script/Engine.PlayerController" end }
local function obj(name, isPc, pawn) return { GetFullName = function() return name end, IsA = function(self, c) return isPc and c == PCC end, Pawn = pawn } end
local pawn = obj("BP_Human_C /Game/Act3.BP_Human_C_0", false)
local realPc = obj("PlayerController /Game/Act3.PlayerController_0", true, pawn)
local junk = obj("Texture2D /Engine/EngineMaterials/BlendFunc", false, obj("whatever", false))
local engine = { GetFullName = function() return "GameEngine /Engine/Transient.GameEngine_0" end, GameInstance = { LocalPlayers = { { PlayerController = realPc } } } }
StaticFindObject = function(p) if p == "/Script/Engine.PlayerController" then return { GetFullName = function() return "Class /Script/Engine.PlayerController" end } end if p == "/Engine/Transient.GameEngine_0" then return engine end end
FindFirstOf = function() return junk end
FindAllOf = function() return { junk } end
` + src.slice(a, b).replace('local PC_CLASS = StaticFindObject("/Script/Engine.PlayerController")', 'local PC_CLASS = PCC') + `
assert(playerController() == realPc, "engine path")
engine.GameInstance.LocalPlayers[1].PlayerController = nil
pcCache = nil
assert(playerController() == nil, "junk must not be returned")
print("pc OK")
`;
const L = lauxlib.luaL_newstate(); lualib.luaL_openlibs(L);
if (lauxlib.luaL_loadstring(L, to_luastring(test)) !== 0 || lua.lua_pcall(L, 0, 0, 0) !== 0) { console.log("FAIL:", lua.lua_tojsstring(L, -1)); process.exit(1); }
