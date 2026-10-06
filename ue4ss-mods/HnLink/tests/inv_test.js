const {lua, lauxlib, lualib, to_luastring} = require("fengari");
const fs = require("fs");
const src = fs.readFileSync(require("path").join(__dirname, "..", "Scripts", "main.lua"), "utf8");
const a = src.indexOf("ia.CMD_HN_ITEM, ia.EV_HN_SELECT"), b = src.indexOf("local function readReply()");
const test = `
local function log(f, ...) print("LOG " .. string.format(f, ...)) end
local ia = {}
local st = {}
local interact = {}
local clock = 0
os.clock = function() return clock end
local function risky(n, fn, ...) return fn(...) end
local function arrayOut(ret, out) local l = {} for _, x in ipairs(ret) do l[#l+1] = x end return l end
local cmds = {}
local function queueCmd(t, a, b, c, d) cmds[#cmds + 1] = { t, a, b, c, d } end
local function mkActor(cls, addr) local o = { hidden = false } o.GetAddress = function() return addr end
  o.GetClass = function() return { GetFName = function() return { ToString = function() return cls end } end } end
  o.SetActorHiddenInGame = function(self, h) self.hidden = h end return o end
local function describe(o) return o:GetClass():GetFName():ToString() end
local A, B = mkActor("BP_TV_apartments_C", 100), mkActor("BP_Video_Recorder_apartments_C", 200)
local slots, current = { A }, A
local inv = { GetActorsInSlots = function() return slots end, GetCurrentObject = function() return current end }
local pawn = { m_pInventory = inv, GetHoldingItem = function() return current end,
  SelectInventoryObject = function(self, n) current = slots[n + 1] end }
local function playerController() return { Pawn = pawn } end
` + src.slice(a, b) + `
local function step(dt) clock = clock + (dt or 0.25) ia.invTick() end
step()
assert(#cmds == 1 and cmds[1][1] == 11 and cmds[1][2] == (1 | (1 << 24)), "A sent")
print("A cmd", cmds[1][3], cmds[1][4], cmds[1][5])
assert(A.hidden == true, "want 0 hides the held item")
ia.want = 1 step()
assert(A.hidden == false, "want A shows it")
slots = { A, B } current = B step()   -- picked up B: HN holds B
assert(ia.byId[2] and ia.byId[2].name == "Video Recorder", "B named " .. tostring(ia.byId[2] and ia.byId[2].name))
-- want still 1 (A): selection must switch back to A
clock = clock + 1 step()
assert(current == A, "selected A again")
ia.want = 2 clock = clock + 1 step()
assert(current == B, "selected B")
ia.want = 0 step()
assert(B.hidden == true, "B hidden")
slots = { A } current = A step()   -- B thrown while hidden
assert(B.hidden == false, "thrown item visible again")
local last = cmds[#cmds]
print("last cmds", #cmds) for i = 1, #cmds do print(table.unpack(cmds[i])) end
ia.forget()
assert(cmds[#cmds][2] == 0)
print("ALL OK")
`;
const L = lauxlib.luaL_newstate(); lualib.luaL_openlibs(L);
if (lauxlib.luaL_loadstring(L, to_luastring(test)) !== 0 || lua.lua_pcall(L, 0, 0, 0) !== 0) { console.log("FAIL:", lua.lua_tojsstring(L, -1)); process.exit(1); }
// decode name of A from the logged words
