-- HnLink: host side of the Minecraft-in-Hello-Neighbor link (phases 2-3).
-- UE4SS Lua cannot map shared memory, so this talks to hn_bridge.exe over two named pipes
-- (protocol/tools/hn_bridge.cpp documents the text protocol). Every ~33 ms, on the game thread:
--   1. sample the local pawn (location, yaw, map)
--   2. write any queued "C ..." commands plus one "S ..." line
--   3. read the reply: "E ..." events, then one "M ..." line with Minecraft's state
-- Minecraft owns the blocks: a cube appears or disappears here only when Minecraft reports a BlockChange event.
-- Keys: F4 Minecraft movement on/off, F6 resync all blocks from Minecraft, F9 what blocks the cells ahead.
-- If the bridge is not running the mod just retries once a second; the game is never affected.

-- Constants used in one place each, kept in one table (Lua allows only 200 locals in the main chunk).
local K = {}
K.PIPE_TO   = [[\\.\pipe\hnmc_to_bridge]]    -- we write
K.PIPE_FROM = [[\\.\pipe\hnmc_from_bridge]]  -- we read
local TICK_MS = 33
K.TELEPORT_DIST = 5000.0   -- uu jumped in one sample => count as a teleport (Act1 intro does this)
local MENU_MAP = "/Game/Maps/Start.Start"

-- Must match protocol/hn_coords.h and hn_protocol.h
local UNITS_PER_BLOCK = 84.0
local CMD_RESYNC, CMD_SURFACE = 5, 6
local EV_BLOCK_CHANGE, EV_RESYNC_DONE = 1, 4
local BLOCK_NAMES = { [1] = "stone", [2] = "planks", [3] = "dirt", [4] = "cobblestone", [5] = "bricks", [6] = "glass", [255] = "other" }
K.MC_EYE_UU = 1.62 * UNITS_PER_BLOCK   -- Minecraft eye height above its feet, in Unreal units
local TRACE_UU = 6 * UNITS_PER_BLOCK          -- camera trace length (Minecraft reach is 4.5 blocks)

local wf, rf = nil, nil
local nextConnectAt = 0
local teleportSeq = 0
local lastPos, lastMap = nil, nil
local mc = nil              -- last M line: {flags,x,y,z,yaw,pitch,ack,slot,tick}
local eventsSeen, lastLog = 0, 0
local t0 = os.clock()
local pendingCmds = {}      -- "C ..." lines waiting for the next tick
-- Height alignment: Minecraft y = (Unreal z + yOff) / 84. Chosen per map so the floor you start on is a block
-- boundary, otherwise every block placed on the floor would float or sink by up to a block; plus Y_LIFT_UU, which
-- puts every Hello Neighbor level well inside Minecraft's height range (-64..320): the deep secret areas landed
-- below y = -64, where no block can exist, so their floors never appeared and the player fell forever.
-- Some secret areas sit so deep inside a level (Act 1's, ~170 blocks under the house, loaded as a level change of
-- the same map) that even the lift leaves them below -64: whenever the player's feet would be outside
-- MC_Y_LOW..MC_Y_HIGH, the grid is moved so they stand at y = 100 again (a new grid: Minecraft's copy is rebuilt).
-- Otherwise the grid stays as it is for the whole map: re-aligning it on every level load moved it again a few
-- seconds after a respawn (Act 2 first stands you in the basement, then in the house), and the player fell through
-- the floor being rebuilt.
local Y_LIFT_UU = 100 * 84.0
K.MC_Y_LOW, K.MC_Y_HIGH = -40, 300
K.REBASE_EVERY_S = 15        -- at most one such move per this many seconds (a player falling out of the level)
local yOff, yOffMap = Y_LIFT_UU, nil
local rebasedAt = -100       -- os.clock() of the last move for heights outside the range
local surface = nil         -- last camera trace hit, for the log
-- Step 3b (Minecraft moves the player). Declared early: sample() reads and sets them.
local driveWanted = true     -- F4 toggles; Minecraft movement is the default
local driveFlag = false      -- kHostMcDrives currently sent (only once the geometry around us is scanned)
local gridDirty = true       -- map or grid offset changed: Minecraft's copy of the geometry must be rebuilt
-- Hello Neighbor handles some player states itself (ladder, ...): while the player is in one, it drives (like F4 off) and
-- Minecraft follows; see handoff.tick below.
-- EHumanState values (from the game's enum, in order). Not handed off: 0 normal, 3 death (caught: Hello Neighbor
-- teleports the player and Minecraft follows, which works), 11 fire trail (a running effect, the player still walks).
local handoff = { on = false, since = 0, lastSeen = 0, state = -1, STATES = {
    [1] = "bear trap", [2] = "cupboard", [4] = "banana slide", [5] = "stunned", [6] = "ladder", [7] = "peeping",
    [8] = "window", [9] = "evade", [10] = "under a bed", [12] = "hiding object", [13] = "vehicle" } }

local function log(fmt, ...) print(string.format("[HnLink] " .. fmt .. "\n", ...)) end

-- Lua's garbage collector, incremental, in small slices: a new cycle starts when memory reaches 1.5x what survived
-- the last one, and each slice does 3x the default work, so a cycle finishes long before garbage piles up.
-- (Generational mode freed ~30 MB at once every 4 s: a 44 ms stop. The very first long stops, 100-250 ms, came from
-- the scanner's garbage and its 200k per-cell tables, both gone now.)
do
    local ok, prev = pcall(collectgarbage, "incremental", 150, 300)
    log("Lua garbage collector: incremental, pause 150, step 300 (%s)", ok and ("was " .. tostring(prev)) or "not available")
end

-- ======================================================================================
-- Block actors. Recipe proven by HnSpawn v5 (notes/skycraft-reference.md, "Phase 3 feasibility").
-- UE4SS IsValid() lies about freshly spawned actors in this game, so existence = "has a real name".
-- ======================================================================================
local blocks = {}           -- "x,y,z" -> actor
local blockCount = 0
local cubesHidden = false    -- step 4: true while hn_gfx draws the real Minecraft blocks (cubes = collision only)

local function exists(o)
    if o == nil then return false end
    local ok, n = pcall(function() return o:GetFullName() end)
    return ok and type(n) == "string" and n ~= "" and not n:find("^None")
end

-- The local PlayerController. FindFirstOf can return a stale one (e.g. the main menu's, not yet garbage
-- collected) that has no pawn, so prefer one that has a pawn. Logs whenever the situation changes.
local pcCache, pcWhy = nil, nil
local function playerController()
    -- By name, not IsValid(): UE4SS IsValid is unreliable for world actors in this game.
    local function hasPawn(c)
        local ok, r = pcall(function() return exists(c) and exists(c.Pawn) end)
        return ok and r and true or false
    end
    if pcCache and hasPawn(pcCache) then return pcCache end
    local why
    -- 1. The engine's own chain, which survives level changes: GameEngine -> GameInstance -> LocalPlayers[1] ->
    --    PlayerController. FindFirstOf/FindAllOf("PlayerController") look objects up by NAME and, after some level
    --    loads (Act 3), returned a texture, a menu widget, a material: then nothing worked in any level.
    local PC_CLASS = StaticFindObject("/Script/Engine.PlayerController")
    local function isPc(c)
        local ok, r = pcall(function() return exists(c) and exists(PC_CLASS) and c:IsA(PC_CLASS) end)
        return ok and r == true
    end
    local okE, viaEngine = pcall(function()
        local eng = StaticFindObject("/Engine/Transient.GameEngine_0")
        if not exists(eng) then return nil end
        local lp = eng.GameInstance.LocalPlayers[1]
        return lp and lp.PlayerController or nil
    end)
    if okE and isPc(viaEngine) and hasPawn(viaEngine) then
        pcCache = viaEngine
        if pcWhy ~= "engine" then pcWhy = "engine" print("[HnLink] player controller found (GameEngine -> LocalPlayers)\n") end
        return pcCache
    end
    -- 2. Fallback: by name, but only real PlayerControllers.
    local first = FindFirstOf("PlayerController")
    if not isPc(first) then first = nil end
    if hasPawn(first) then
        pcCache = first
    else
        pcCache = nil
        local all = FindAllOf("PlayerController") or {}
        local idx = 0
        for i, c in ipairs(all) do
            if isPc(c) and hasPawn(c) then pcCache, idx = c, i break end
        end
        if pcCache then
            why = string.format("using player controller %d of %d (the first one has no pawn)", idx, #all)
        else
            local detail = {}
            for _, c in ipairs(all) do
                local d = "?"
                pcall(function() d = c:GetFullName():sub(1, 90) .. " pawn=" .. (c.Pawn ~= nil and tostring(c.Pawn:GetAddress() ~= 0) or "nil") end)
                detail[#detail + 1] = d
            end
            why = string.format("no player controller with a pawn (%d controller(s): %s)", #all, table.concat(detail, " | "))
        end
    end
    if why ~= pcWhy then
        pcWhy = why
        if why then print("[HnLink] " .. why .. "\n") else print("[HnLink] player controller found\n") end
    end
    return pcCache or first or (okE and isPc(viaEngine) and viaEngine) or nil
end

-- EHumanState names, from the game's own enum (logged when a state is first seen).
function handoff.nameOf(n)
    local name
    pcall(function()
        if not exists(handoff.enum) then handoff.enum = StaticFindObject("/Script/HelloNeighbor.EHumanState") end
        name = handoff.enum:GetNameByValue(n):ToString()
    end)
    return name or "?"
end

-- Every tick: is the player in a state Hello Neighbor must handle itself (ladder: its own climbing, animation and exit
-- at the top)? Then Minecraft stops driving until the state is over (0.5 s of grace against flicker at the edges of a
-- ladder's trigger box); Minecraft then continues from wherever the player ended up. Only while Minecraft drives
-- (F4 on): with F4 off Hello Neighbor drives anyway.
function handoff.tick(inGame)
    local now = os.clock()
    local inState = false
    if inGame == 1 and driveWanted then
        local ok, s = pcall(function() return playerController().Pawn:GetState() end)
        if ok and type(s) == "number" then
            if s ~= handoff.state then
                handoff.state = s
                log("player state %d (%s)", s, handoff.nameOf(s))
            end
            if s == 3 then handoff.deathAt = now end   -- caught: the move that follows goes to the respawn point

            inState = handoff.STATES[s] ~= nil
        end
    end
    if inState then handoff.lastSeen = now end
    local want = inState or (handoff.on and driveWanted and now - handoff.lastSeen < 0.5)
    if want ~= handoff.on then
        handoff.on, handoff.since = want, now
        log("%s", want and ("Hello Neighbor takes over (" .. tostring(handoff.STATES[handoff.state]) .. ")") or "Minecraft takes over again")
    end
    return handoff.on
end

local function key(x, y, z) return x .. "," .. y .. "," .. z end

-- Per-type look: a dynamic instance of the engine's BasicShapeMaterial tinted with its "Color" parameter.
-- (Real Minecraft textures need a textured material built with the Mod Kit; see notes.)
K.BASIC_MATERIAL = "/Engine/BasicShapes/BasicShapeMaterial.BasicShapeMaterial"
K.BLOCK_COLORS = {
    [1] = { R = 0.45, G = 0.45, B = 0.45 },   -- stone
    [2] = { R = 0.62, G = 0.45, B = 0.25 },   -- planks
    [3] = { R = 0.40, G = 0.26, B = 0.15 },   -- dirt
    [4] = { R = 0.28, G = 0.28, B = 0.28 },   -- cobblestone
    [5] = { R = 0.55, G = 0.20, B = 0.15 },   -- bricks
    [6] = { R = 0.70, G = 0.85, B = 0.95 },   -- glass (opaque for now)
    [255] = { R = 0.80, G = 0.10, B = 0.80 }, -- anything else
}
local lookLogged = {}

local function applyLook(comp, id)
    local first = not lookLogged[id]
    lookLogged[id] = true
    local parent = StaticFindObject(K.BASIC_MATERIAL)
    if not exists(parent) then if first then log("look: BasicShapeMaterial not loaded") end return end
    if first then log("look[%d]: creating dynamic material", id) end
    local mid = comp:CreateAndSetMaterialInstanceDynamicFromMaterial(0, parent)
    if not exists(mid) then if first then log("look[%d]: no dynamic material returned", id) end return end
    local c = K.BLOCK_COLORS[id] or K.BLOCK_COLORS[255]
    if first then log("look[%d]: setting Color", id) end
    mid:SetVectorParameterValue(FName("Color"), { R = c.R, G = c.G, B = c.B, A = 1.0 })
    if first then log("look[%d]: ok (%s)", id, mid:GetFullName()) end
end

local function spawnBlockActor(bx, by, bz, id)
    local gs = StaticFindObject("/Script/Engine.Default__GameplayStatics")
    local cls = StaticFindObject("/Script/Engine.StaticMeshActor")
    local mesh = StaticFindObject("/Engine/BasicShapes/Cube.Cube")
    local ctx = playerController()   -- the level the player is in (FindFirstOf("GameModeBase") can be the menu's)
    if not exists(ctx) then ctx = FindFirstOf("GameModeBase") end
    if not (exists(gs) and exists(cls) and exists(mesh) and exists(ctx)) then return nil end

    -- Block centre in Unreal space: mc (x, y, z) -> host (x, z, y) * 84; the engine cube's pivot is its centre.
    local s = UNITS_PER_BLOCK / 100.0
    local xf = {
        Rotation = { X = 0.0, Y = 0.0, Z = 0.0, W = 1.0 },
        Translation = { X = (bx + 0.5) * UNITS_PER_BLOCK, Y = (bz + 0.5) * UNITS_PER_BLOCK, Z = (by + 0.5) * UNITS_PER_BLOCK - yOff },
        Scale3D = { X = s, Y = s, Z = s },
    }
    local actor = gs:BeginDeferredActorSpawnFromClass(ctx, cls, xf, 1, nil)   -- 1 = AlwaysSpawn
    if not exists(actor) then return nil end
    local comp = actor.StaticMeshComponent
    if exists(comp) then
        comp.Mobility = 2            -- Movable; must be set before SetStaticMesh (SetMobility is not callable)
        comp:SetStaticMesh(mesh)
    end
    gs:FinishSpawningActor(actor, xf)
    actor:SetActorScale3D({ X = s, Y = s, Z = s })
    if exists(comp) then
        local ok, e = pcall(applyLook, comp, id)
        if not ok then log("look[%d] failed: %s", id, tostring(e)) end
    end
    return actor
end

local function removeBlockActor(k)
    local a = blocks[k]
    if a then
        if exists(a) then pcall(function() a:K2_DestroyActor() end) end
        blocks[k] = nil
        blockCount = blockCount - 1
    end
end

-- Minecraft says cell (x, y, z) now holds block `id` (0 = air).
local function applyBlockChange(x, y, z, id)
    local k = key(x, y, z)
    removeBlockActor(k)
    -- Nothing to build on the main menu (and those actors would die with it); a level resyncs when it loads.
    if id ~= 0 and lastMap == MENU_MAP then return end
    if id ~= 0 then
        local ok, a = pcall(spawnBlockActor, x, y, z, id)
        if ok and a then
            blocks[k] = a
            blockCount = blockCount + 1
            if cubesHidden then pcall(function() a:SetActorHiddenInGame(true) end) end
        else
            log("could not spawn block at %s: %s", k, ok and "spawn returned nothing" or tostring(a))
        end
    end
end

local function clearAllBlocks(why)
    local n = 0
    for k in pairs(blocks) do removeBlockActor(k) n = n + 1 end
    blocks, blockCount = {}, 0
    if n > 0 then log("cleared %d block actor(s) (%s)", n, why) end
end

local function queueCmd(t, a, b, c, d)
    table.insert(pendingCmds, string.format("C %d %d %d %d %d\n", t, a, b, c, d))
end

local function requestResync(why)
    clearAllBlocks(why)
    queueCmd(CMD_RESYNC, 0, 0, 0, 0)
end

-- ======================================================================================
-- Link
-- ======================================================================================
local function disconnect()
    if wf then pcall(function() wf:close() end) end
    if rf then pcall(function() rf:close() end) end
    wf, rf = nil, nil
end

local function connect()
    wf = io.open(K.PIPE_TO, "wb")
    if not wf then return false end
    rf = io.open(K.PIPE_FROM, "rb")
    if not rf then pcall(function() wf:close() end) wf = nil return false end
    wf:setvbuf("no")
    log("connected to hn_bridge")
    requestResync("connected")   -- show whatever Minecraft already has
    return true
end

local function hash(s)
    local h = 5381
    for i = 1, #s do h = (h * 33 + s:byte(i)) % 2147483647 end
    return h
end

-- Where a Minecraft death goes back to. resp[map] = Hello Neighbor's own respawn point: where the game puts the
-- player after the neighbour catches them, learnt from catches on each map (spawnPt.learn) and kept in RESPAWN_FILE across
-- sessions. Until a map has one: its starting point { map, X, Y, Z, Yaw }, the first spot stood on (see sample), which
-- is the last save and can be outside the playable area (Act 1).
local spawnPt = { resp = {}, RESPAWN_FILE = "hn_respawn.txt" }
do
    local f = io.open(spawnPt.RESPAWN_FILE, "r")
    if f then
        for line in f:lines() do
            local m, x, y, z, yaw = line:match("^(%S+) (%S+) (%S+) (%S+) (%S+)$")
            if m then spawnPt.resp[m] = { X = tonumber(x), Y = tonumber(y), Z = tonumber(z), Yaw = tonumber(yaw) } end
        end
        f:close()
    end
end
-- After a catch the game put the player at loc. A map's first one is taken at once; a different one replaces it only
-- once the game has used it twice in a row (a one-off move after a catch, like the dream sequence, must not stick).
function spawnPt.learn(map, loc, yaw)
    local function near(p) return p and math.abs(p.X - loc.X) + math.abs(p.Y - loc.Y) + math.abs(p.Z - loc.Z) < 100 end
    local r = spawnPt.resp[map]
    if near(r) then spawnPt.candidate = nil return end
    if r then
        local c = spawnPt.candidate
        if not (c and c.map == map and near(c)) then
            spawnPt.candidate = { map = map, X = loc.X, Y = loc.Y, Z = loc.Z }
            log("after a catch the player is at (%.0f, %.0f, %.0f), not the known respawn point: noted, not used yet", loc.X, loc.Y, loc.Z)
            return
        end
        spawnPt.candidate = nil
    end
    spawnPt.resp[map] = { X = loc.X, Y = loc.Y, Z = loc.Z, Yaw = yaw }
    local f = io.open(spawnPt.RESPAWN_FILE, "w")
    if f then
        for m, p in pairs(spawnPt.resp) do f:write(string.format("%s %.1f %.1f %.1f %.1f\n", m, p.X, p.Y, p.Z, p.Yaw)) end
        f:close()
    end
    log("Hello Neighbor's respawn point for %s: (%.0f, %.0f, %.0f) - a Minecraft death goes there now", map, loc.X, loc.Y, loc.Z)
end

-- Returns inGame(0/1), worldId, x, y, z, yaw, pitch, onGround(0/1) or nil if there is no pawn yet.
local function sample()
    local pc = playerController()
    if not exists(pc) then return nil end
    local pawn = pc.Pawn
    if not exists(pawn) then return nil end

    local loc = pawn:K2_GetActorLocation()
    local rot = pawn:K2_GetActorRotation()
    local map = pawn:GetFullName():match("(/Game/[^:]+)") or lastMap or "?"   -- unreadable name: keep the last map
    local ground = 1
    pcall(function()
        local cm = pawn.CharacterMovement
        if cm and cm:IsValid() then ground = cm:IsMovingOnGround() and 1 or 0 end
    end)
    local inGame = (map ~= MENU_MAP) and 1 or 0

    -- teleport detection: map change or a big jump between samples
    if lastMap and map ~= lastMap then
        teleportSeq = teleportSeq + 1
        -- the old level's block actors are gone with it; ask Minecraft for the blocks again
        if inGame == 1 then requestResync("map changed") else clearAllBlocks("left the level") end
        gridDirty = true
    end
    if lastPos then
        local dx, dy, dz = loc.X - lastPos[1], loc.Y - lastPos[2], loc.Z - lastPos[3]
        if math.sqrt(dx * dx + dy * dy + dz * dz) > K.TELEPORT_DIST then teleportSeq = teleportSeq + 1 end
    end
    lastPos, lastMap = { loc.X, loc.Y, loc.Z }, map
    -- The level's starting point (a Minecraft death goes back there): the first spot in this level where the player
    -- stands on the ground outside a cutscene.
    if inGame == 1 and ground == 1 and spawnPt.map ~= map then
        local okc, cut = pcall(function() return pawn:IsPlayCutScene() == true end)
        if okc and not cut then
            spawnPt.map, spawnPt.X, spawnPt.Y, spawnPt.Z, spawnPt.Yaw = map, loc.X, loc.Y, loc.Z, rot.Yaw
            log("starting point of %s: (%.0f, %.0f, %.0f) yaw %.0f", map, loc.X, loc.Y, loc.Z, rot.Yaw)
        end
    end

    -- K2_GetActorLocation is the capsule CENTRE; Minecraft wants the FEET. Measured with HnProbe:
    -- scaled half-height is 75.6 standing / 59.5 crouched, feet stay put at centre - half-height.
    local half = 75.6
    pcall(function()
        local cap = pawn.CapsuleComponent
        if cap and cap:IsValid() then half = cap:GetScaledCapsuleHalfHeight() end
    end)

    -- Height alignment, once per map, the first time we stand on the ground there; and at once
    -- when the player is outside Minecraft's usable heights with the current grid (too high only counts while Hello
    -- Neighbor moves the player: Minecraft's own flights may go up there).
    local feetY = (loc.Z - half + yOff) / UNITS_PER_BLOCK
    local outside = inGame == 1 and (feetY < K.MC_Y_LOW or (feetY > K.MC_Y_HIGH and not driveFlag))
        and os.clock() - rebasedAt > K.REBASE_EVERY_S
    if outside then rebasedAt = os.clock() end
    if inGame == 1 and ((yOffMap ~= map and ground == 1) or outside) then
        local newOff = (-(loc.Z - half)) % UNITS_PER_BLOCK + Y_LIFT_UU
        local y = (loc.Z - half + newOff) / UNITS_PER_BLOCK
        if y < K.MC_Y_LOW or y > K.MC_Y_HIGH then newOff = Y_LIFT_UU - (loc.Z - half) end   -- feet at exactly y = 100
        yOffMap = map
        if math.abs(newOff - yOff) > 0.5 then
            yOff = newOff
            teleportSeq = teleportSeq + 1
            requestResync(string.format("floor alignment %.1f uu", yOff))   -- cubes move with the grid
            gridDirty = true
        end
        log("map %s: floor at z=%.1f, grid offset %.1f uu", map, loc.Z - half, yOff)
    end

    -- Aim: Minecraft's crosshair must point where Hello Neighbor's CAMERA points. So send the camera's position
    -- and rotation (control rotation; pitch may come as 0..360, Minecraft side normalises and flips the sign),
    -- with the "feet" placed so Minecraft's eye (1.62 blocks above its feet) sits exactly at the camera.
    -- Hello Neighbor's own eye is only ~1.36 blocks above its feet, so without this Minecraft would aim from
    -- ~22 cm too high.
    local x, y, z = loc.X, loc.Y, loc.Z - half
    local yaw, pitch = rot.Yaw, rot.Pitch
    pcall(function()
        local cam = pc.PlayerCameraManager:GetCameraLocation()
        local cr = pc:GetControlRotation()
        x, y, z = cam.X, cam.Y, cam.Z - K.MC_EYE_UU
        yaw, pitch = cr.Yaw, cr.Pitch
    end)
    z = z + yOff
    -- While Minecraft drives, the position only matters when Hello Neighbor moves us itself (teleport): then
    -- Minecraft is put at our real feet (1 uu up).
    if driveFlag then x, y, z = loc.X, loc.Y, loc.Z - half + yOff + 1.0 end

    return inGame, hash(map), x, y, z, yaw, pitch, ground
end

-- ======================================================================================
-- Camera trace: what Hello Neighbor surface the crosshair is on. Minecraft uses it as its crosshair target when
-- its own (void) world has nothing closer, so right-click places against HN walls and floors directly.
-- ======================================================================================
local KSL = nil
local traceFailLogged = false
local traceMs, traceN = 0.0, 0   -- cost of the camera trace, for the log
local surfaceOff = false   -- set when no usable trace method exists (or one crashed the game before)

-- Crash guard. Calling an engine function from Lua can crash the whole game inside UE4SS (KismetSystemLibrary
-- LineTraceSingle did: converting its FHitResult out-param, 2026-10-02 17:50). So until a method has proven itself,
-- its name is written to a breadcrumb file before each call and removed after. If the game dies mid-call, the next
-- load finds the breadcrumb, records the method as broken, and never calls it again.
local BREADCRUMB = "hnlink_trace_breadcrumb.txt"     -- relative to the game's cwd (Binaries\Win64)
local BROKEN = "hnlink_trace_broken.txt"
local TRUST_AFTER = 300                               -- calls WITH hits before a method is trusted
local broken = { hitresult = true }                   -- LineTraceSingle: crashes (see above)
local trusted = 0

do
    local f = io.open(BREADCRUMB, "r")
    if f then
        local m = f:read("l") f:close() os.remove(BREADCRUMB)
        if m and m ~= "" then
            local w = io.open(BROKEN, "a") if w then w:write(m .. "\n") w:close() end
            log("the game crashed inside trace method '%s' last time; it is now disabled", m)
        end
    end
    f = io.open(BROKEN, "r")
    if f then for line in f:lines() do if line ~= "" then broken[line] = true end end f:close() end
end

local function guarded(method, fn, ...)
    if trusted >= TRUST_AFTER then return fn(...) end
    local f = io.open(BREADCRUMB, "w") if f then f:write(method) f:close() end
    local r = table.pack(pcall(fn, ...))
    os.remove(BREADCRUMB)
    if not r[1] then error(r[2], 0) end
    return table.unpack(r, 2, r.n)
end

-- Parameter list of a UFunction, from reflection only (safe): "Name:Type ..."
local function signature(path)
    local fn = StaticFindObject(path)
    if not exists(fn) then return nil end
    local parts = {}
    pcall(function()
        fn:ForEachProperty(function(p)
            parts[#parts + 1] = p:GetFName():ToString() .. ":" .. p:GetClass():GetFName():ToString()
        end)
    end)
    return table.concat(parts, " ")
end

-- Method "probe": every engine trace returns an FHitResult (unusable from Lua here, see above), so the surface is
-- found with yes/no questions instead: KismetSystemLibrary.SphereOverlapComponents, filtered to static meshes,
-- whose bool return value says "is any solid mesh within R of this point". March a sphere along the view ray,
-- bisect the first contact, then find which side is free to get the face. All answers are bools.
local PROBE_R = 16.0          -- uu; contact means the surface is ~PROBE_R from the sphere centre
local PROBE_STEP = 2 * PROBE_R
K.BISECT = 6              -- 32 uu -> 0.5 uu
K.OBJ_TYPES = { 0, 1 }    -- ObjectTypeQuery1/2 = WorldStatic, WorldDynamic
local SMC = nil               -- StaticMeshComponent class (also matches instanced static meshes)
local method = nil
local probeCalls = 0

local function chooseMethod()
    if broken.probe then log("trace: method 'probe' is marked broken") return nil end
    local so = signature("/Script/Engine.KismetSystemLibrary:SphereOverlapComponents")
    if not so or not so:find("ReturnValue:BoolProperty") then log("trace: SphereOverlapComponents not usable (%s)", tostring(so)) return nil end
    SMC = StaticFindObject("/Script/Engine.StaticMeshComponent")
    if not exists(SMC) then log("trace: StaticMeshComponent class not found") return nil end
    return "probe"
end

-- Right-click to interact: the trace below asks interact.classify (defined with the interaction code) whether what it
-- hit can be used in Hello Neighbor. interact.held = the item in the player's hand (in front of the camera; the
-- crosshair must look past it).
local interact = { held = nil }

local function solidAt(pawn, p, r)
    probeCalls = probeCalls + 1
    return KSL:SphereOverlapComponents(pawn, p, r, K.OBJ_TYPES, SMC, interact.held and { pawn, interact.held } or { pawn }, {}) == true
end

local function along(s, d, t) return { X = s.X + d.X * t, Y = s.Y + d.Y * t, Z = s.Z + d.Z * t } end

-- Returns surface point and axis-aligned normal (Unreal space), or nil.
local function traceProbe(pawn, s, e)
    local d = { X = e.X - s.X, Y = e.Y - s.Y, Z = e.Z - s.Z }
    local len = math.sqrt(d.X * d.X + d.Y * d.Y + d.Z * d.Z)
    d = { X = d.X / len, Y = d.Y / len, Z = d.Z / len }

    -- 1. march: first sphere centre that touches something
    local free, hitT = 0.0, nil
    local t = PROBE_STEP
    while t <= len do
        if solidAt(pawn, along(s, d, t), PROBE_R) then hitT = t break end
        free = t
        t = t + PROBE_STEP
    end
    if not hitT then return nil end
    if free == 0.0 and solidAt(pawn, s, PROBE_R) then return nil end   -- camera itself is against something

    -- 2. bisect to the first contact
    local lo, hi = free, hitT
    for _ = 1, K.BISECT do
        local mid = (lo + hi) / 2
        if solidAt(pawn, along(s, d, mid), PROBE_R) then hi = mid else lo = mid end
    end
    local c = along(s, d, hi)

    -- 3. face: the axis (facing the camera) along which stepping back off the surface frees the sphere.
    -- Try the axes in order of how directly the ray hits them.
    local axes = {
        { k = "X", v = d.X }, { k = "Y", v = d.Y }, { k = "Z", v = d.Z },
    }
    table.sort(axes, function(a, b) return math.abs(a.v) > math.abs(b.v) end)
    for _, a in ipairs(axes) do
        if math.abs(a.v) > 0.02 then
            local n = { X = 0.0, Y = 0.0, Z = 0.0 }
            n[a.k] = a.v > 0 and -1.0 or 1.0
            if not solidAt(pawn, along(c, n, PROBE_R * 0.5), PROBE_R) then
                -- The surface is PROBE_R behind the touching centre along the normal.
                return along(c, n, -PROBE_R), n
            end
        end
    end
    return nil   -- a corner or something round; no clean face
end

-- Returns the kCmdSurface line, or nil if the trace cannot run at all.
local function traceSurface()
    if surfaceOff then return nil end
    local pc = playerController()
    if not exists(pc) or not exists(pc.Pawn) then return nil end
    if not exists(KSL) then KSL = StaticFindObject("/Script/Engine.Default__KismetSystemLibrary") end
    if not exists(KSL) then return nil end
    if method == nil then
        method = chooseMethod() or false
        if not method then surfaceOff = true log("trace: no safe method; right-click on Hello Neighbor surfaces is off") return nil end
    end

    local s, p, yw
    if mc and (mc.flags & 16) ~= 0 and mc.x then   -- 16 = MC_DRIVING (declared further down)
        -- Minecraft drives: aim exactly like Minecraft's crosshair (its eye and look direction), not from Hello
        -- Neighbor's camera, which in third person sits behind the player and would aim somewhere else.
        s = { X = mc.x * UNITS_PER_BLOCK, Y = mc.z * UNITS_PER_BLOCK, Z = (mc.y + (mc.eye or 1.62)) * UNITS_PER_BLOCK - yOff }
        p, yw = math.rad(-mc.pitch), math.rad(mc.yaw + 90.0)
    else
        local cam = pc.PlayerCameraManager:GetCameraLocation()
        local cr = pc:GetControlRotation()
        p, yw = math.rad(cr.Pitch), math.rad(cr.Yaw)
        s = { X = cam.X, Y = cam.Y, Z = cam.Z }
    end
    local e = { X = s.X + math.cos(p) * math.cos(yw) * TRACE_UU, Y = s.Y + math.cos(p) * math.sin(yw) * TRACE_UU,
                Z = s.Z + math.sin(p) * TRACE_UU }

    local t0c = os.clock()
    local ip, n = guarded(method, traceProbe, pc.Pawn, s, e)
    traceMs = traceMs + (os.clock() - t0c) * 1000
    traceN = traceN + 1
    if not ip then
        surface = nil
        return string.format("C %d 0 0 0 0\n", CMD_SURFACE)
    end
    if trusted < TRUST_AFTER then
        trusted = trusted + 1
        if trusted == 1 then log("trace: first hit at (%.0f, %.0f, %.0f) normal (%.2f, %.2f, %.2f)", ip.X, ip.Y, ip.Z, n.X, n.Y, n.Z) end
        if trusted == TRUST_AFTER then log("trace: method '%s' trusted after %d hits", method, TRUST_AFTER) end
    end
    -- Unreal (x, y, z) -> Minecraft (x, z, y); same for the normal (no offset, no scale).
    local mx, my, mz = ip.X / UNITS_PER_BLOCK, (ip.Z + yOff) / UNITS_PER_BLOCK, ip.Y / UNITS_PER_BLOCK
    local nx, ny, nz = n.X, n.Z, n.Y
    local ax, ay, az = math.abs(nx), math.abs(ny), math.abs(nz)
    local face   -- Minecraft Direction ordinal + 1: 1 down, 2 up, 3 north (-z), 4 south (+z), 5 west (-x), 6 east (+x)
    if ay >= ax and ay >= az then face = ny > 0 and 2 or 1
    elseif ax >= az then face = nx > 0 and 6 or 5
    else face = nz > 0 and 4 or 3 end
    surface = { mx, my, mz, face }
    -- Within Hello Neighbor's reach (its ActiveDistance, 220 uu) and something it can use: kCmdSurface d | 0x100.
    local reach = math.sqrt((ip.X - s.X) ^ 2 + (ip.Y - s.Y) ^ 2 + (ip.Z - s.Z) ^ 2)
    if reach <= 230.0 and interact.classify then
        local ok, yes = pcall(interact.classify, pc, ip, n)
        if ok and yes then face = face | 0x100 end
    end
    return string.format("C %d %d %d %d %d\n", CMD_SURFACE,
        math.floor(mx * 1000 + 0.5), math.floor(my * 1000 + 0.5), math.floor(mz * 1000 + 0.5), face)
end

-- ======================================================================================
-- Step 4a: tell hn_gfx (the native renderer) where Unreal keeps the camera, so it can draw in the 3D scene.
-- hn_camera.txt = "<PlayerCameraManager address> <offset of CameraCache.POV.Location> <...Rotation> <...FOV>",
-- offsets found by reflection (no hard-coded engine layout). hn_gfx reads those floats every frame.
-- ======================================================================================
local CAM_FILE, CUBE_FILE = "hn_camera.txt", "hn_testcube.txt"
local camWrittenFor = nil
local camOffsets = nil
local camFailed = false

local function propOffset(path, names)
    local s = StaticFindObject(path)
    if not exists(s) then return nil end
    local off
    s:ForEachProperty(function(p)
        local n = p:GetFName():ToString()
        for _, want in ipairs(names) do if n == want then off = p:GetOffset_Internal() end end
    end)
    return off
end

local function writeFile(name, text)
    local f = io.open(name, "w")
    if f then f:write(text) f:close() end
end

local function publishCamera(inGame)
    if camFailed then return end
    local addr = 0
    if inGame == 1 then
        local pc = playerController()
        if exists(pc) and exists(pc.PlayerCameraManager) then addr = pc.PlayerCameraManager:GetAddress() end
    end
    local key = string.format("%d %.3f", addr, yOff)
    if key == camWrittenFor then return end
    if addr ~= 0 and not camOffsets then
        local cc = propOffset("/Script/Engine.PlayerCameraManager", { "CameraCache", "CameraCachePrivate" })
        local pov = propOffset("/Script/Engine.CameraCacheEntry", { "POV" })
        local loc = propOffset("/Script/Engine.MinimalViewInfo", { "Location" })
        local rot = propOffset("/Script/Engine.MinimalViewInfo", { "Rotation" })
        local fov = propOffset("/Script/Engine.MinimalViewInfo", { "FOV" })
        log("camera offsets: CameraCache %s, POV %s, Location %s, Rotation %s, FOV %s",
            tostring(cc), tostring(pov), tostring(loc), tostring(rot), tostring(fov))
        if not (cc and pov and loc and rot and fov) then camFailed = true log("camera: reflection incomplete; step 4 drawing off") return end
        camOffsets = { cc + pov + loc, cc + pov + rot, cc + pov + fov }
    end
    camWrittenFor = key
    if addr == 0 then writeFile(CAM_FILE, "0 0 0 0\n") return end
    -- 5th field: the grid offset, so hn_gfx puts Minecraft's blocks at the same height as the cubes.
    writeFile(CAM_FILE, string.format("%d %d %d %d %.3f\n", addr, camOffsets[1], camOffsets[2], camOffsets[3], yOff))
    log("camera published for hn_gfx: PlayerCameraManager at 0x%x", addr)
end


-- Step 4: while hn_gfx draws Minecraft's real blocks, the cube actors are only invisible collision (the neighbour
-- still bumps into builds, the surface trace still finds them). hn_world_status.txt = "<frame> <vertices> <depthOk>".
local worldDrawing = false
local lastStatusFrame, lastStatusCheck = nil, 0

local function setCubesHidden(hide)
    cubesHidden = hide
    local n = 0
    for _, a in pairs(blocks) do
        if exists(a) then pcall(function() a:SetActorHiddenInGame(hide) end) n = n + 1 end
    end
    log("%s %d cube actor(s): Minecraft blocks are %s", hide and "hid" or "showed", n, hide and "drawn by hn_gfx" or "not being drawn")
end

local function checkWorldStatus()
    local now = os.clock()
    if now - lastStatusCheck < 1.0 then return end
    lastStatusCheck = now
    local f = io.open("hn_world_status.txt", "r")
    local line = f and f:read("l")
    if f then f:close() end
    local frame, verts = nil, 0
    if line then
        local a, b = line:match("^(%d+) (%d+)")
        frame, verts = tonumber(a), tonumber(b) or 0
    end
    -- hn_gfx is drawing frames = it draws Minecraft's blocks, so the stand-in cubes hide. Not "only with block
    -- vertices": a chest, shulker box, bed... is drawn as an entity, not in the block mesh, and with only those
    -- around the mesh is empty, which showed the magenta "other" cube over the real chest.
    local drawing = frame ~= nil and frame ~= lastStatusFrame
    lastStatusFrame = frame
    if drawing ~= worldDrawing then
        worldDrawing = drawing
        setCubesHidden(drawing)
    end
end

-- ======================================================================================
-- Step 3b: Minecraft's physics moves the player.
--  1. Scanner: Hello Neighbor's geometry around the player, per Minecraft cell at quarter-block resolution, using
--     only yes/no BoxOverlapComponents questions (no FHitResult). Sent as kCmdProxyCell; Minecraft turns each
--     non-empty cell into an invisible collision block of exactly that shape.
--  2. Once the area around us is scanned, kHostMcDrives goes up; Minecraft takes over (W A S D, Space, Shift, Ctrl
--     are routed to it by hn_gfx) and reports kMcDriving.
--  3. Every tick the pawn is teleported to where Minecraft's eye is, so Hello Neighbor's camera (and the neighbour)
--     follow. If Hello Neighbor moves the pawn itself (caught, cutscene), Minecraft is teleported there instead.
-- ======================================================================================
local CMD_PROXY_CELL, CMD_PROXY_CLEAR = 7, 8
local HOST_MC_DRIVES, MC_DRIVING = 8, 16
K.HOST_MENU_OPEN = 2
local lastPaused = false
local VOX_R, VOX_DOWN, VOX_UP = 3, 2, 3       -- scanned region around the player (cells)
-- Rescans. Walls and floors never move, so cells with only those wait long; cells near a door, furniture or a prop
-- (D) are rescanned every 4 s, and every 0.5 s right around the player. Doors themselves are watched (st.doorTick).
-- (Rescanning everything every 4 s, and those near props every 0.3 s, kept the scanner busy all the time, and its
-- garbage made Lua stop the game for ~40 ms every 4 s.)
local VOX_RESCAN_S = 15.0                     -- cells with only walls/floors (parts of a level may stream in later)
local VOX_DYN_RESCAN_S = 0.5                  -- near a door/furniture/prop and close to the player (doors: st.doorTick)
local VOX_BUDGET = 96                         -- overlap queries per tick
local VOX_FRAME_MAX_S = 0.005                 -- scanning time per frame; a pass that needs more continues next frame
local VOX_FORGET_S = 30.0                     -- scan times of cells not looked at for 30-60 s are forgotten
K.HN_MOVED_UU = 150.0                     -- pawn found this far from where we put it = Hello Neighbor moved it

-- What the scanner knows, keyed by voxAhead.key(x, y, z), as plain numbers (no table per cell: 200k small tables
-- made Lua's garbage collector stop the game for 50-250 ms):
--   S[k] = the cell's shape (bit mask) as Minecraft has it, only for non-empty cells; kept for the whole level, so a
--          cell that empties later is always cleared in Minecraft too.
--   T[k] = when it was last scanned, D[k] = true if a door/furniture/prop is near (rescanned often). These two are
--          forgotten for cells not looked at for VOX_FORGET_S..2x (two generations, swapped), so memory stays bounded
--          however far the player goes; a forgotten cell is simply scanned again when needed.
local voxCells = { S = {}, T = {}, D = {}, T2 = {}, D2 = {}, rotAt = 0 }
local voxOffsets = nil
local voxCursor, voxPassDone, voxQueries, voxSent = 1, false, 0, 0
local voxLogged = false
-- Cells an arrow will fly through (kEvScanCell), scanned before anything else; see voxPass.
local VOX_PATH_FRESH_S = 30.0
local voxPriority, voxPrioritySet, voxPathScanned, voxPathAsked = {}, {}, 0, 0
-- The way ahead of a fast-moving player (st.scanAhead fills it, replacing it whenever the path changes): scanned
-- before everything else, with its own larger budget, or walls appeared only after the player had flown through.
-- urgent: cells a projectile (ender pearl, arrow) is flying through (kEvScanCell with d = 1), first in first out,
-- before the asked-for cells of walking mobs (that queue is long and drops requests when full).
local voxAhead = { list = {}, head = 1, scanned = 0, urgent = {}, uhead = 1, uscanned = 0,
    -- Reused by every scanner query: a new table per query (and a new "x,y,z" string per cell lookup) made ~5 MB of
    -- garbage a second while flying, and Lua's garbage collector then stopped the game for 50-250 ms every few seconds.
    P = { X = 0.0, Y = 0.0, Z = 0.0 }, E = { X = 0.0, Y = 0.0, Z = 0.0 }, OUT = {},
    DYN_FAR_S = 4.0,                                 -- rescan of cells near props, not close to the player
    EMPTY_S = 2.0 }                                  -- rescan of empty cells (geometry that streams in later)
-- A cell's key as a number, no string made (cells within +-65536 blocks; exact in a double).
function voxAhead.key(x, y, z) return (x + 65536) * 17179869184.0 + (y + 65536) * 131072.0 + (z + 65536) end
-- When cell k was last scanned (nil = not known); a cell from the older generation moves back to the current one.
function voxAhead.seen(k)
    local V = voxCells
    local t = V.T[k]
    if t then return t end
    t = V.T2[k]
    if t then
        V.T[k], V.D[k], V.T2[k], V.D2[k] = t, V.D2[k], nil, nil
        voxAhead.cells = (voxAhead.cells or 0) + 1
    end
    return t
end
local function voxRequest(x, y, z)
    local k = voxAhead.key(x, y, z)
    if voxPrioritySet[k] or #voxPriority >= 4000 then return end
    voxPrioritySet[k] = true
    voxPriority[#voxPriority + 1] = { x, y, z }
    voxPathAsked = voxPathAsked + 1
end
local followOn = false                        -- pawn movement disabled, pawn follows Minecraft
local camOffZ = 0.0                           -- camera height above the capsule centre (measured)
local lastSet = nil                           -- where we last put the pawn
local waitAck = nil                           -- teleportSeq Minecraft must acknowledge before we follow again
local followFails, follows = 0, 0
local voxErrLogged, followErrLogged, camErrLogged = false, false, false
local lastTraceAt = 0

local function s32(v)
    v = v & 0xFFFFFFFF
    if v >= 0x80000000 then v = v - 0x100000000 end
    return v
end

local function buildOffsets()
    local list = {}
    for dy = -VOX_DOWN, VOX_UP do
        for dx = -VOX_R, VOX_R do
            for dz = -VOX_R, VOX_R do
                -- the floor under us first, then outwards
                local w = dx * dx + dz * dz + (dy < 0 and (dy + 1) * (dy + 1) or dy * dy * 1.5)
                list[#list + 1] = { dx, dy, dz, w, math.abs(dx) <= 2 and math.abs(dz) <= 2 and dy >= -1 and dy <= 2 }
            end
        end
    end
    table.sort(list, function(a, b) return a[4] < b[4] end)
    return list
end

-- Solid = any WorldStatic geometry of ANY kind (BSP brushes, static meshes, landscape: Hello Neighbor's floors are
-- not all static meshes; the first version missed them and the player fell through), or a WorldDynamic static mesh
-- (doors, furniture), or a PhysicsBody static mesh (boxes, chairs and other props that can be knocked over or picked
-- up: without them Minecraft flew and walked straight through them). Triggers are WorldDynamic non-mesh components,
-- so they stay out; a prop the player holds is attached to the pawn, so ignoredActors leaves it out.
local STATIC_ONLY, DYNAMIC_ONLY = { 0 }, { 1, 3 }

-- Never scan the player itself: the pawn AND every actor attached to it (arms, held items, accessories). Those sit
-- around the hidden pawn's head; as proxies they collapsed Minecraft's third-person camera onto the face (its
-- camera rays start within 0.1 blocks of the eye) and gave odd bumps. Refreshed every 2 s.
local ignoreList, ignoreAt, ignoreLogged = nil, -100, false
local function ignoredActors(pawn)
    local now = os.clock()
    -- The item in the hand moves every frame: re-check it every call (it is a prop, scanned now that props count).
    local held = interact.held
    if ignoreList and now - ignoreAt < 2.0 and held == interact.ignoredHeld then return ignoreList end
    ignoreAt = now
    local tIgn = os.clock()
    local list = { pawn }
    local ok, err = pcall(function()
        if broken.attached then error("disabled: it crashed the game before") end
        local out = {}
        -- Crash marker around every call (only every 2 s): a crash here disables it on the next load.
        local f = io.open(BREADCRUMB, "w") if f then f:write("attached") f:close() end
        local okc, ret = pcall(function() return pawn:GetAttachedActors(out) end)
        os.remove(BREADCRUMB)
        if not okc then error(ret, 0) end
        local src = (type(ret) == "table" and #ret > 0) and ret or out
        for _, a in ipairs(src) do
            local actor = a
            if type(a) == "userdata" and a.get then pcall(function() actor = a:get() end) end
            if exists(actor) then list[#list + 1] = actor end
        end
    end)
    if not ignoreLogged then
        ignoreLogged = true
        local names = {}
        for i = 2, #list do names[#names + 1] = list[i]:GetFName():ToString() end
        log("scanner ignores the player + %d attached actor(s): %s%s", #list - 1, table.concat(names, ", "),
            ok and "" or (" (GetAttachedActors failed: " .. tostring(err) .. ")"))
    end
    if held ~= nil and exists(held) then list[#list + 1] = held end
    interact.ignoredHeld = held
    voxAhead.ignS = (voxAhead.ignS or 0) + os.clock() - tIgn
    ignoreList = list
    return list
end

-- One overlap query of the scanner (15 us in open space, up to ~1 ms in dense geometry; a cell needs up to ~70).
-- Only what BLOCKS the player in Hello Neighbor counts: an overlap query also finds trigger zones (the door's own
-- "Bound"/"CountPass" boxes, analytics trigger boxes) that the player walks through there, and those made doorways
-- solid for Minecraft (a secret room's door opened but could not be passed). Per component, cached by address.
function voxAhead.blocks(c)
    local addr = c:GetAddress()
    local b = voxAhead.blockCache[addr]
    if b == nil then
        -- Blocks ANY collision channel (0 WorldStatic .. 7 Destructible): trigger zones block none of them (they only
        -- overlap). Window glass blocks neither the player nor sight (it stops thrown objects), so a check on just
        -- those let arrows and the player through windows. ECR_Block = 2; an unreadable response counts as solid.
        local ok, any = pcall(function()
            for ch = 0, 7 do if c:GetCollisionResponseToChannel(ch) == 2 then return true end end
            return false
        end)
        b = not ok or any
        -- The game's invisible walls (blocking volumes, hidden collision meshes: Act 2 fences the player in with
        -- them) are "hidden": they only hold the player up (see query), so Minecraft can fly or walk out where the
        -- game's own player could not.
        if b and ok then
            local okH, hidden = pcall(function()
                if c.bHiddenInGame == true or c:IsVisible() == false then return true end
                local o = c:GetOwner()
                return o ~= nil and o.bHidden == true
            end)
            if okH and hidden then b = "hidden" end
        end
        voxAhead.blockCache[addr] = b
    end
    return b
end
voxAhead.blockCache = {}

function voxAhead.query(pawn, p, ext, types, cls, ignore)
    voxQueries = voxQueries + 1
    local t0 = os.clock()
    local out = voxAhead.OUT
    local r = KSL:BoxOverlapComponents(pawn, p, ext, types, cls, ignore, out) == true
    if r and out[1] ~= nil then
        r = false
        for i = 1, #out do
            local c = out[i]
            if type(c) == "userdata" and c.get then pcall(function() c = c:get() end) end
            local okB, b = pcall(voxAhead.blocks, c)
            -- an invisible blocker counts only below the player's feet (a floor), never as a wall
            if not okB or b == true or (b == "hidden" and p.Z + ext.Z <= (voxAhead.feetZ or -1e30)) then r = true break end
        end
    end
    if out[1] ~= nil then for i = #out, 1, -1 do out[i] = nil end end   -- the engine may fill it: empty again
    local dt = os.clock() - t0
    if dt > (voxAhead.maxQ or 0) then voxAhead.maxQ = dt end
    return r
end
-- The reused position and extent tables, filled in.
function voxAhead.at(x, y, z) local P = voxAhead.P P.X, P.Y, P.Z = x, y, z return P end
function voxAhead.ext(e) local E = voxAhead.E E.X, E.Y, E.Z = e, e, e return E end

local function boxSolid(pawn, cx, cy, cz, e)
    local p, ext = voxAhead.at(cx, cy, cz), voxAhead.ext(e)
    local ignore = ignoredActors(pawn)
    if voxAhead.query(pawn, p, ext, STATIC_ONLY, nil, ignore) then return true end
    return voxAhead.query(pawn, p, ext, DYNAMIC_ONLY, SMC, ignore)
end

-- Sub-cells of Minecraft cell (x, y, z) that overlap the player's own body (Minecraft box, feet up to just above
-- the eye). Never solid: Minecraft's physics never lets the player stand inside a real wall, so anything found
-- there is the player's own stuff.
local function bodyMask(x, y, z, body)
    if not body then return 0 end
    local m = 0
    for qz = 0, 3 do for qy = 0, 3 do for qx = 0, 3 do
        local x0, y0, z0 = x + qx / 4, y + qy / 4, z + qz / 4
        if x0 < body.x1 and x0 + 0.25 > body.x0 and y0 < body.y1 and y0 + 0.25 > body.y0 and z0 < body.z1 and z0 + 0.25 > body.z0 then
            m = m | (1 << (qx + 4 * qy + 16 * qz))
        end
    end end end
    return m
end

-- Occupancy of Minecraft cell (x, y, z): bit (sx + 4*sy + 16*sz). Minecraft x = Unreal X, y = Unreal Z (+yOff),
-- z = Unreal Y. Empty cells cost 2 queries, others 9 + 8 per occupied octant.
-- Second result: a moving-capable mesh (door, furniture) is in or within half a block of the cell, so voxPass
-- rescans it often (an opened door must stop blocking right away, a closed one must start).
local function scanCell(pawn, x, y, z, body)
    local B, Q = UNITS_PER_BLOCK, UNITS_PER_BLOCK / 4
    local function ue(sx, sy, sz, size)   -- Unreal centre of a sub-box starting at quarter (sx, sy, sz)
        return x * B + (sx + size / 2) * Q, z * B + (sz + size / 2) * Q, y * B + (sy + size / 2) * Q - yOff
    end
    local cx, cy, cz = ue(0, 0, 0, 4)
    local ignore = ignoredActors(pawn)
    local p = voxAhead.at(cx, cy, cz)
    local dyn = voxAhead.query(pawn, p, voxAhead.ext(B), DYNAMIC_ONLY, SMC, ignore)
    local e = B / 2 - 1
    local solid = voxAhead.query(pawn, p, voxAhead.ext(e), STATIC_ONLY, nil, ignore)
    if not solid and dyn then
        solid = voxAhead.query(pawn, p, voxAhead.ext(e), DYNAMIC_ONLY, SMC, ignore)
    end
    if not solid then return 0, dyn end
    local mask = 0
    for oz = 0, 2, 2 do for oy = 0, 2, 2 do for ox = 0, 2, 2 do
        local ax, ay, az = ue(ox, oy, oz, 2)
        if boxSolid(pawn, ax, ay, az, Q - 0.5) then
            for qz = oz, oz + 1 do for qy = oy, oy + 1 do for qx = ox, ox + 1 do
                local bx, by, bz = ue(qx, qy, qz, 1)
                if boxSolid(pawn, bx, by, bz, Q / 2 - 0.5) then mask = mask | (1 << (qx + 4 * qy + 16 * qz)) end
            end end end
        end
    end end end
    return mask & ~bodyMask(x, y, z, body), dyn
end

local function voxReset(why)
    voxCells, voxCursor, voxPassDone = { S = {}, T = {}, D = {}, T2 = {}, D2 = {}, rotAt = 0 }, 1, false
    voxAhead.cells = 0
    voxPriority, voxPrioritySet = {}, {}
    voxAhead.list, voxAhead.head = {}, 1
    voxAhead.urgent, voxAhead.uhead = {}, 1
    table.insert(pendingCmds, string.format("C %d 0 0 0 0\n", CMD_PROXY_CLEAR))
    log("Hello Neighbor geometry for Minecraft: reset (%s)", why)
end

-- One pass of scanning around Minecraft cell (px, py, pz), within the query budgets and this frame's time.
local function voxPass(px, py, pz)
    if not voxOffsets then voxOffsets = buildOffsets() end
    local pc = playerController()
    if not exists(pc) or not exists(pc.Pawn) then return end
    if not exists(KSL) then KSL = StaticFindObject("/Script/Engine.Default__KismetSystemLibrary") end
    if not exists(SMC) then SMC = StaticFindObject("/Script/Engine.StaticMeshComponent") end
    if not exists(KSL) or not exists(SMC) then return end
    if not voxLogged then
        voxLogged = true
        log("scanner: BoxOverlapComponents(%s)", tostring(signature("/Script/Engine.KismetSystemLibrary:BoxOverlapComponents")))
    end
    local pawn = pc.Pawn
    local now = os.clock()
    local start = voxQueries
    -- The player's body while Minecraft drives (0.3 wide like Minecraft's box, a little margin), from above the knees:
    -- what is found there is the player's own arms / held item. NOT the legs: when the game put the player a little
    -- INTO a floor (leaving the Act 2 basement), the floor there was left out as "the player's own", so Minecraft fell,
    -- was put back on the same spot, and fell again forever. Now the floor stays solid and Minecraft lifts the player
    -- out of it (HnWorld.unstick).
    local body = nil
    if mc and (mc.flags & MC_DRIVING) ~= 0 and mc.x then
        local eye = mc.eye or 1.62
        body = { x0 = mc.x - 0.32, x1 = mc.x + 0.32, z0 = mc.z - 0.32, z1 = mc.z + 0.32, y0 = mc.y + 0.6, y1 = mc.y + eye + 0.3 }
    end
    -- Scan one cell and tell Minecraft when what it holds changed.
    local function scanOne(x, y, z)
        local k = voxAhead.key(x, y, z)
        local V = voxCells
        local old = V.S[k] or 0
        local mask, dyn = scanCell(pawn, x, y, z, body)
        if old ~= mask then
            table.insert(pendingCmds, string.format("C %d %d %d %d %d\n", CMD_PROXY_CELL, x,
                s32((y & 0xFFFF) | ((z & 0xFFFF) << 16)), s32(mask), s32(mask >> 32)))
            voxSent = voxSent + 1
            V.S[k] = mask ~= 0 and mask or nil
        end
        if not V.T[k] and not voxAhead.seen(k) then voxAhead.cells = (voxAhead.cells or 0) + 1 end
        V.T[k], V.D[k] = now, dyn or nil
    end
    -- Each queue has a query budget, and all of them stop once this frame's scanning time (voxAhead.frameEnd) is used.
    local fe = voxAhead.frameEnd or (now + VOX_FRAME_MAX_S)
    -- First projectiles in flight (urgent), with up to 4x the budget.
    local urgentStart = voxQueries
    while voxAhead.uhead <= #voxAhead.urgent and voxQueries - urgentStart < 4 * VOX_BUDGET and os.clock() < fe do
        local p = voxAhead.urgent[voxAhead.uhead]
        voxAhead.uhead = voxAhead.uhead + 1
        local t = voxAhead.seen(voxAhead.key(p[1], p[2], p[3]))
        -- p[4]: forced (a door moving there), scanned even if seen a moment ago
        if p[4] or not t or now - t > VOX_PATH_FRESH_S then scanOne(p[1], p[2], p[3]) voxAhead.uscanned = voxAhead.uscanned + 1 end
    end
    if voxAhead.uhead > #voxAhead.urgent then voxAhead.urgent, voxAhead.uhead = {}, 1 end
    -- Then the way ahead of a fast player (st.scanAhead), nearest first, with up to 4x the budget.
    local aheadStart = voxQueries
    while voxAhead.head <= #voxAhead.list and voxQueries - aheadStart < 4 * VOX_BUDGET and os.clock() < fe do
        local p = voxAhead.list[voxAhead.head]
        voxAhead.head = voxAhead.head + 1
        local t = voxAhead.seen(voxAhead.key(p[1], p[2], p[3]))
        if not t or now - t > VOX_PATH_FRESH_S then scanOne(p[1], p[2], p[3]) voxAhead.scanned = voxAhead.scanned + 1 end
    end
    -- Then the cells an arrow is about to fly through (kEvScanCell), with up to twice the budget: the arrow is
    -- fast. Cells scanned in the last VOX_PATH_FRESH_S seconds are known already.
    local head = 1
    while head <= #voxPriority and voxQueries - start < 2 * VOX_BUDGET and os.clock() < fe do
        local p = voxPriority[head]
        head = head + 1
        local k = voxAhead.key(p[1], p[2], p[3])
        voxPrioritySet[k] = nil
        local t = voxAhead.seen(k)
        if not t or now - t > VOX_PATH_FRESH_S then
            scanOne(p[1], p[2], p[3])
            voxPathScanned = voxPathScanned + 1
        end
    end
    if head > 1 then
        table.move(voxPriority, head, #voxPriority, 1)
        for i = #voxPriority, #voxPriority - head + 2, -1 do voxPriority[i] = nil end
    end
    -- The area around the player always gets its own full budget: the asked-for cells (arrows, and mobs walking
    -- around, which never stops while the neighbour moves) must not starve it, or Minecraft never takes over (F4).
    local areaStart = voxQueries
    local n = #voxOffsets
    -- The area gets its own share after the queues above (at least a few cells per frame, so it never starves).
    local areaEnd = math.max(fe, os.clock() + 0.002)
    for _ = 1, n do
        if voxQueries - areaStart >= VOX_BUDGET or os.clock() > areaEnd then break end
        local o = voxOffsets[voxCursor]
        local x, y, z = px + o[1], py + o[2], pz + o[3]
        local k = voxAhead.key(x, y, z)
        local t = voxAhead.seen(k)
        -- Doors and furniture right around the player: every VOX_DYN_RESCAN_S, so an opened door lets us through.
        -- Empty cells are cheap (2 queries) and are checked again every EMPTY_S: parts of a level load in after it
        -- starts (leaving the Act 2 basement: the floor was not there yet when scanned, and Minecraft fell into the
        -- void until the next rescan). Walls and floors already found are the costly ones: VOX_RESCAN_S.
        local age = (t and voxCells.D[k]) and (o[5] and VOX_DYN_RESCAN_S or voxAhead.DYN_FAR_S)
            or (voxCells.S[k] and VOX_RESCAN_S or voxAhead.EMPTY_S)
        if not t or now - t > age then scanOne(x, y, z) end
        voxCursor = voxCursor + 1
        if voxCursor > n then
            voxCursor = 1
            if not voxPassDone then voxPassDone = true log("scanner: area around the player done (%d cells sent so far)", voxSent) end
        end
    end
end

-- Every frame: one pass, for at most VOX_FRAME_MAX_S (more until the first pass around the player is done, so
-- Minecraft can take over sooner). (A version that paused passes mid-cell in a coroutine failed: the mod loader does
-- not allow engine calls from one.)
function voxAhead.tick(px, py, pz)
    local now = os.clock()
    -- Up to here (Unreal z) invisible blockers are solid: the cells below the player's feet cell, plus its lowest
    -- quarter (a floor whose top is not on a block boundary).
    voxAhead.feetZ = (py + 0.25) * UNITS_PER_BLOCK - yOff
    voxAhead.frameEnd = now + (voxPassDone and VOX_FRAME_MAX_S or 0.012)
    -- What blocks may change (a door switching its collision as it opens): ask again every 3 s.
    if now > (voxAhead.bcAt or 0) then voxAhead.blockCache, voxAhead.bcAt = {}, now + 3.0 end
    -- Swap generations: what was not looked at for a whole period is dropped (the shapes in S stay).
    local V = voxCells
    if now > V.rotAt then
        if V.rotAt > 0 then V.T2, V.D2, V.T, V.D = V.T, V.D, {}, {} end
        V.rotAt = now + VOX_FORGET_S
        voxAhead.cells = 0
    end
    voxPass(px, py, pz)
end

-- Minecraft's F5 camera. In third person Hello Neighbor's view moves to a CameraActor placed every frame exactly
-- where Minecraft's camera is (behind or in front of the player, pulled in by walls as Minecraft decides), so the
-- Minecraft player model that hn_gfx draws is in view. First person: back to the pawn's own camera.
local MC_CAM_DETACHED = 32
local camActor = nil
local viewOnCam = false
local camSpawnFailLogged = false

local function ensureCamActor()
    if exists(camActor) then return camActor end
    local gs = StaticFindObject("/Script/Engine.Default__GameplayStatics")
    local cls = StaticFindObject("/Script/Engine.CameraActor")
    local ctx = playerController()   -- the level the player is in (FindFirstOf("GameModeBase") can be the menu's)
    if not exists(ctx) then ctx = FindFirstOf("GameModeBase") end
    if not (exists(gs) and exists(cls) and exists(ctx)) then return nil end
    local xf = { Rotation = { X = 0.0, Y = 0.0, Z = 0.0, W = 1.0 }, Translation = { X = 0.0, Y = 0.0, Z = 0.0 }, Scale3D = { X = 1.0, Y = 1.0, Z = 1.0 } }
    local a = gs:BeginDeferredActorSpawnFromClass(ctx, cls, xf, 1, nil)
    if not exists(a) then return nil end
    gs:FinishSpawningActor(a, xf)
    camActor = a
    log("third-person camera actor spawned")
    return a
end

local function setView(pc, pawn, third)
    if third == viewOnCam then return end
    local target = pawn
    if third then
        target = ensureCamActor()
        -- No camera actor = stay as we are. (Falling back to the pawn here put the view inside the head, right
        -- where hn_gfx draws the Minecraft player: a screen full of face.)
        if not exists(target) then
            if not camSpawnFailLogged then camSpawnFailLogged = true log("third-person camera actor could not be spawned") end
            return
        end
    end
    if not exists(target) then return end
    pc:SetViewTargetWithBlend(target, 0.0, 0, 0.0, false)
    viewOnCam = third
    log("view: %s", third and "Minecraft third-person camera" or "first person")
end

local function placeCamActor()
    if not exists(camActor) or not mc.camFov then return end
    local B = UNITS_PER_BLOCK
    local pos = { X = (mc.x + mc.camDX) * B, Y = (mc.z + mc.camDZ) * B, Z = (mc.y + mc.camDY) * B - yOff }
    -- Minecraft yaw -> Unreal yaw = yaw + 90; Minecraft pitch is positive DOWN.
    camActor:K2_TeleportTo(pos, { Pitch = -mc.camPitch, Yaw = mc.camYaw + 90.0, Roll = 0.0 })
    -- Minecraft's FOV is vertical; Unreal's horizontal (16:9 back buffer).
    local v = math.rad(mc.camFov)
    pcall(function() camActor.CameraComponent.FieldOfView = math.deg(2 * math.atan(math.tan(v / 2) * 16 / 9)) end)
end

local function setPawnMovement(pawn, enabled)
    pcall(function()
        local cm = pawn.CharacterMovement
        if not exists(cm) then return end
        if enabled then cm:SetMovementMode(1, 0) else cm:DisableMovement() end   -- 1 = MOVE_Walking
    end)
end

-- While Minecraft drives, Hello Neighbor's capsule does not BLOCK on world geometry (it still overlaps: triggers,
-- ladders, catching keep working). Minecraft decides where the player can go; a blocking capsule made K2_TeleportTo
-- refuse spots at the map's invisible walls (the view stuck there while the elytra flew on) and shift spots near
-- geometry (read as "Hello Neighbor moved the player": a jump on landing). Blocking again whenever Hello Neighbor
-- drives (F4 off, hand-offs: ladders, cupboards).
-- Channels: 0 WorldStatic, 1 WorldDynamic, 5 PhysicsBody (props: boxes, chairs, now solid in Minecraft too; the
-- capsule is wider than Minecraft's player, so next to a prop the game refused the move and the view jumped), 6 Vehicle,
-- 7 Destructible. Their original responses are kept and put back exactly.
K.PAWN_GHOST_CHANNELS = { 0, 1, 5, 6, 7 }
local function setPawnBlocking(pawn, block)
    pcall(function()
        local cap = pawn.CapsuleComponent
        if not exists(cap) then return end
        interact.capsuleSaved = interact.capsuleSaved or {}
        local saved = interact.capsuleSaved
        for _, ch in ipairs(K.PAWN_GHOST_CHANNELS) do
            if block then
                if saved[ch] then cap:SetCollisionResponseToChannel(ch, saved[ch]) end
            else
                if not saved[ch] then
                    local okR, r = pcall(function() return cap:GetCollisionResponseToChannel(ch) end)
                    saved[ch] = (okR and type(r) == "number") and r or 2   -- default: ECR_Block
                end
                if saved[ch] == 2 then cap:SetCollisionResponseToChannel(ch, 1) end   -- Block -> Overlap
            end
        end
        if block then interact.capsuleSaved = nil end
    end)
end

-- After the reply: follow Minecraft, or hand control back.
local function follow()
    local pc = playerController()
    if not exists(pc) or not exists(pc.Pawn) then return end
    local pawn = pc.Pawn
    local driving = mc and (mc.flags & MC_DRIVING) ~= 0 and driveFlag
    if not driving then
        if followOn then
            followOn = false
            pcall(setView, pc, pawn, false)
            setPawnBlocking(pawn, true)
            if not handoff.on then   -- a hand-off keeps the movement mode the game set (ladder) and the body hidden
                setPawnMovement(pawn, true)
                pcall(function() pawn:SetActorHiddenInGame(false) end)
            end
            lastSet, waitAck = nil, nil
            log("Hello Neighbor moves the player again (followed %d times, %d teleports refused)", follows, followFails)
        end
        return
    end
    if not followOn then
        followOn = true
        setPawnMovement(pawn, false)
        setPawnBlocking(pawn, false)
        -- Minecraft's hand and body replace Hello Neighbor's: hide its arms/body (collision and AI sight unaffected).
        pcall(function() pawn:SetActorHiddenInGame(true) end)
        lastSet, waitAck = nil, nil
        log("Minecraft moves the player now")
    end

    local loc = pawn:K2_GetActorLocation()
    if waitAck then
        if mc.ack < waitAck then return end      -- Minecraft has not arrived yet
        waitAck = nil
    elseif lastSet then
        local dx, dy, dz = loc.X - lastSet.X, loc.Y - lastSet.Y, loc.Z - lastSet.Z
        if dx * dx + dy * dy + dz * dz > K.HN_MOVED_UU * K.HN_MOVED_UU then
            local dist = math.sqrt(dx * dx + dy * dy + dz * dz)
            -- Moved by the game shortly after the neighbour caught us: that is its respawn point for this map.
            if os.clock() - (handoff.deathAt or -100) < 15 then
                pcall(spawnPt.learn, lastMap, loc, pawn:K2_GetActorRotation().Yaw)
                handoff.deathAt = nil
            end
            teleportSeq = teleportSeq + 1          -- the next S line carries our feet: Minecraft goes there
            waitAck = teleportSeq
            lastSet = nil
            if dist > 10 * UNITS_PER_BLOCK then
                -- Far (a dream sequence, a checkpoint, a respawn): nothing is scanned there yet, and Minecraft fell
                -- into the void (again and again: the fall rescue put it back on the same empty spot). Like at the
                -- start of a level: Hello Neighbor holds the player on its own floor until the area is scanned,
                -- then Minecraft takes over from there.
                voxPassDone, voxCursor = false, 1
                log("Hello Neighbor moved the player far (%.0f uu): it keeps the player until the area there is scanned", dist)
            else
                log("Hello Neighbor moved the player (%.0f uu): Minecraft follows", dist)
            end
            return
        end
    end
    -- Keep movement off (some Hello Neighbor actions switch it back on).
    pcall(function() if pawn.CharacterMovement.MovementMode ~= 0 then pawn.CharacterMovement:DisableMovement() end end)
    -- Keep Hello Neighbor's body hidden: its catch sequences and cutscenes show the player again when they end (Act 1's
    -- opening catch, leaving the basement in Act 2), and its arms then floated in view next to Minecraft's hand.
    local nowH = os.clock()
    if nowH - (interact.hideAt or -100) > 0.5 then
        interact.hideAt = nowH
        pcall(function()
            local shown = pawn.bHidden == false
            pawn:SetActorHiddenInGame(true)
            if shown then
                interact.reHides = (interact.reHides or 0) + 1
                if interact.reHides <= 5 then log("Hello Neighbor showed the player's body again: hidden (%d)", interact.reHides) end
            end
        end)
    end

    -- Put the pawn so that its CAMERA lands on Minecraft's eye. The camera hangs off the animated head, so its
    -- offset from the capsule changes (bob, landing); use this frame's offset, in all three axes.
    local off = { X = 0.0, Y = 0.0, Z = 37.6 }
    pcall(function()
        local cam = pc.PlayerCameraManager:GetCameraLocation()
        off = { X = cam.X - loc.X, Y = cam.Y - loc.Y, Z = cam.Z - loc.Z }
    end)
    if math.abs(off.X) > 60 or math.abs(off.Y) > 60 or off.Z < 0 or off.Z > 120 then off = { X = 0.0, Y = 0.0, Z = 37.6 } end
    local eyeZ = (mc.y + (mc.eye or 1.62)) * UNITS_PER_BLOCK - yOff
    local target = { X = mc.x * UNITS_PER_BLOCK - off.X, Y = mc.z * UNITS_PER_BLOCK - off.Y, Z = eyeZ - off.Z }
    if pawn:K2_TeleportTo(target, pawn:K2_GetActorRotation()) then
        follows = follows + 1
        lastSet = target
    else
        followFails = followFails + 1
        if followFails == 1 or followFails % 300 == 0 then log("pawn teleport refused %d time(s) (blocked by Hello Neighbor geometry)", followFails) end
        lastSet = pawn:K2_GetActorLocation()
    end

    -- F5: third person puts Hello Neighbor's view on Minecraft's camera.
    local third = (mc.flags & MC_CAM_DETACHED) ~= 0
    local ok, e = pcall(function()
        setView(pc, pawn, third)
        if third then placeCamActor() end
    end)
    if not ok and not camErrLogged then camErrLogged = true log("third-person camera failed: %s", tostring(e)) end
end

-- ======================================================================================
-- The neighbour: Minecraft gets his position and size (an invisible target entity there), and a Minecraft hit
-- on him comes back as EV_NEIGHBOR_HIT: he is knocked back. He is looked up among the LIVE characters every time
-- (never kept between ticks), so a level change or a respawn can never leave us holding a dead actor.
-- ======================================================================================
local CMD_NEIGHBOR, CMD_NEIGHBOR_SIZE, EV_NEIGHBOR_HIT = 9, 10, 5
local st = {}   -- neighbour and impact state (a table: Lua allows only 200 locals in the main chunk)
st.neighbourLoggedFor, st.neighbourSent, st.neighbourSizeKey, st.neighbourSizeAt = nil, nil, nil, -100
st.neighbourErrLogged = false

-- Engine calls that are new and fill output arrays: a crash inside one must not repeat. Each call leaves a
-- breadcrumb (see the trace section); a crash there marks the name broken for the next start. After 20 clean
-- calls a name is trusted and no file is written any more.
st.riskyTrust = {}
local function risky(name, fn, ...)
    if broken[name] then error("disabled: it crashed the game before", 0) end
    local n = st.riskyTrust[name] or 0
    if n >= 20 and not name:find("^item%.") then return fn(...) end   -- item steps: always guarded
    local f = io.open(BREADCRUMB, "w") if f then f:write(name) f:close() end
    local r = table.pack(pcall(fn, ...))
    os.remove(BREADCRUMB)
    if not r[1] then error(r[2], 0) end
    st.riskyTrust[name] = n + 1
    return table.unpack(r, 2, r.n)
end

-- UE4SS hands output arrays back either as the return value or by filling the table passed in; elements may be
-- wrapped (:get()).
local function arrayOut(ret, out)
    local src = (type(ret) == "table" and #ret > 0) and ret or out
    local list = {}
    for _, a in ipairs(src or {}) do
        local o = a
        if type(a) == "userdata" and a.get then pcall(function() o = a:get() end) end
        if o ~= nil then list[#list + 1] = o end
    end
    return list
end

-- The neighbour = a Character in the level that is not the player's pawn and is controlled by something that is
-- not a PlayerController, or none at all (Hello Neighbor's BP_Sosed_C + SosedAIController; in scripted states his
-- controller reads none). Characters come
-- from the engine's own GameplayStatics.GetAllActorsOfClass(Character) (FindAllOf("Character") returned
-- unrelated assets with that name in this build). Refreshed every second; forgotten on level change.
st.neighbourRef, st.neighbourAt = nil, -100
st.GAMEPLAY_STATICS, st.CHARACTER, st.PLAYER_CONTROLLER = nil, nil, nil

local function describe(o)
    local ok, s = pcall(function() return o:GetClass():GetFName():ToString() end)
    return ok and s or "?"
end

local function findNeighbour()
    local now = os.clock()
    if st.neighbourRef and now - st.neighbourAt < 1.0 then
        return st.neighbourRef   -- refreshed every second; the level-change hooks drop it
    end
    st.neighbourAt, st.neighbourRef = now, nil
    local pc = playerController()
    if not exists(pc) then return nil end
    if not exists(st.GAMEPLAY_STATICS) then st.GAMEPLAY_STATICS = StaticFindObject("/Script/Engine.Default__GameplayStatics") end
    if not exists(st.CHARACTER) then st.CHARACTER = StaticFindObject("/Script/Engine.Character") end
    if not exists(st.PLAYER_CONTROLLER) then st.PLAYER_CONTROLLER = StaticFindObject("/Script/Engine.PlayerController") end
    if not (exists(st.GAMEPLAY_STATICS) and exists(st.CHARACTER)) then return nil end
    local pawnAddr = 0
    pcall(function() pawnAddr = pc.Pawn:GetAddress() end)
    local chars = {}
    local ok, err = pcall(function()
        chars = risky("allactors", function()
            local out = {}
            local ret = st.GAMEPLAY_STATICS:GetAllActorsOfClass(pc, st.CHARACTER, out)
            return arrayOut(ret, out)
        end)
    end)
    local names, found, errs = {}, nil, {}
    for _, c in ipairs(chars) do
        -- No IsValid() here: it is unreliable for world actors in this game (it made him flicker in and out).
        local okc, desc, isNeighbour = pcall(function()
            if c:GetAddress() == pawnAddr then return nil, false end
            local cls = describe(c)
            local ctlDesc, isPlayer = "none", false
            pcall(function()
                local ctl = c.Controller
                if ctl ~= nil and ctl:GetAddress() ~= 0 then
                    ctlDesc = describe(ctl)
                    isPlayer = exists(st.PLAYER_CONTROLLER) and ctl:IsA(st.PLAYER_CONTROLLER)
                end
            end)
            -- Hello Neighbor's neighbour is BP_Sosed_C; otherwise any character no player controls (his
            -- controller reads "none" in scripted states, e.g. a catch).
            return string.format("%s (controller %s)", cls, ctlDesc), cls:find("Sosed", 1, true) ~= nil or not isPlayer
        end)
        if okc and desc then names[#names + 1] = desc
        elseif not okc then errs[#errs + 1] = tostring(desc) end
        if okc and isNeighbour and (not found or describe(c):find("Sosed", 1, true)) then found = c end
    end
    local key = (lastMap or "?") .. "|" .. table.concat(names, ",") .. "|" .. #errs
    if key ~= st.neighbourLoggedFor then
        st.neighbourLoggedFor = key
        log("characters besides the player (%d from GetAllActorsOfClass%s): %s%s%s", #chars, ok and "" or (", failed: " .. tostring(err)),
            #names > 0 and table.concat(names, ", ") or "none", found and ("; the neighbour is " .. describe(found)) or "; no neighbour",
            #errs > 0 and ("; errors: " .. table.concat(errs, " | ")) or "")
    end
    st.neighbourRef = found
    return found
end

-- Every tick in a level: tell Minecraft where he is (when he moved) and how big (now and then).
local function neighbourTick()
    local n = findNeighbour()
    if not n then
        -- Only after 3 s without him: a lookup that misses once must not take his target away in Minecraft.
        st.neighbourMissingSince = st.neighbourMissingSince or os.clock()
        if os.clock() - st.neighbourMissingSince < 3.0 then return end
        if st.neighbourSizeKey ~= "gone" then
            st.neighbourSizeKey, st.neighbourSent = "gone", nil
            queueCmd(CMD_NEIGHBOR_SIZE, 0, 0, 0, 0)
        end
        return
    end
    st.neighbourMissingSince = nil
    local half, radius = 96.0, 34.0
    pcall(function()
        local cap = n.CapsuleComponent
        half, radius = cap:GetScaledCapsuleHalfHeight(), cap:GetScaledCapsuleRadius()
    end)
    local loc, rot = n:K2_GetActorLocation(), n:K2_GetActorRotation()
    -- Unreal (x, y, z) -> Minecraft (x, z, y); his feet are the capsule centre minus its half height.
    local mx, my, mz = loc.X / UNITS_PER_BLOCK, (loc.Z - half + yOff) / UNITS_PER_BLOCK, loc.Y / UNITS_PER_BLOCK
    local myaw = rot.Yaw - 90.0
    local now = os.clock()
    local sizeKey = string.format("%.0f %.0f", radius, half)
    if sizeKey ~= st.neighbourSizeKey or now - st.neighbourSizeAt > 2.0 then   -- repeated: Minecraft may have restarted
        st.neighbourSizeKey, st.neighbourSizeAt = sizeKey, now
        queueCmd(CMD_NEIGHBOR_SIZE, math.floor(radius / UNITS_PER_BLOCK * 1000 + 0.5), math.floor(2 * half / UNITS_PER_BLOCK * 1000 + 0.5), 1, 0)
    end
    local s = st.neighbourSent
    if not s or math.abs(s[1] - mx) + math.abs(s[2] - my) + math.abs(s[3] - mz) > 0.005 or math.abs(s[4] - myaw) > 1 or now - s[5] > 1.0 then
        st.neighbourSent = { mx, my, mz, myaw, now }
        queueCmd(CMD_NEIGHBOR, math.floor(mx * 1000 + 0.5), math.floor(my * 1000 + 0.5), math.floor(mz * 1000 + 0.5), math.floor(myaw * 100 + 0.5))
    end
end

-- Minecraft hit him: a = strength * 100 (fist 1, diamond sword 7, times the attack cooldown); b, c = push
-- direction in Minecraft x, z (* 1000). Like in Hello Neighbor itself, he is knocked down by a THROWN ITEM: an
-- invisible real one is thrown at his chest (its native hit code stuns him). Only if that is impossible is he
-- pushed with LaunchCharacter.
local throwItemAt   -- (p, dir, speed, from) -> ok; defined with the impact code below

-- His own knockdown: BP_Sosed has SlipAndFall / Fall (the banana-peel slip, fall_run / fall_walk animations) and
-- MixerFall (the Mixer "make him fall" feature). The first of these on his class chain that takes no parameters
-- is called; all are logged with their parameters once. Not again while he is still down (3 s).
K.FALL_NAMES = { "SlipAndFall", "Fall", "MixerFall" }
local function neighbourFall(n)
    if st.fallFn == nil then
        st.fallFn = false
        pcall(function()
            local c = n:GetClass()
            for _ = 1, 6 do
                if c == nil then break end
                local path = c:GetFullName():match("^%S+ (.+)$")
                for _, name in ipairs(K.FALL_NAMES) do
                    local sig = path and signature(path .. ":" .. name)
                    if sig ~= nil then
                        log("neighbour function %s(%s) on %s", name, sig, path)
                        if not st.fallFn and (sig == "" or sig:match("^ReturnValue:%w+$")) then st.fallFn = name end
                    end
                end
                c = c:GetSuperStruct()
            end
        end)
        log("neighbour knockdown: %s", st.fallFn and ("calls his " .. st.fallFn) or "no parameterless fall function; thrown items instead")
    end
    if not st.fallFn then return false end
    local now = os.clock()
    st.fellNow = false
    if now - (st.lastFallAt or -100) < 3.0 then return true end   -- already down
    local ok, e = pcall(function() risky("fall", function() n[st.fallFn](n) end) end)
    if ok then st.lastFallAt, st.fellNow = now, true
    else log("neighbour %s failed: %s", st.fallFn, tostring(e)) st.fallFn = false end
    return ok
end

local function neighbourHit(a, b, c)
    local n = findNeighbour()
    if not n then log("hit the neighbour, but he is gone") return end
    local strength = a / 100.0
    local dx, dy = b / 1000.0, c / 1000.0                        -- Minecraft (x, z) -> Unreal (X, Y)
    if neighbourFall(n) then
        -- (only when he actually goes down: mobs hit him many times a second)
        if st.fellNow then log("hit the neighbour: strength %.2f -> he falls (%s)", strength, st.fallFn) end
        return
    end
    local thrown = false
    pcall(function()
        -- From outside his capsule (radius ~34 uu): an item spawned inside it never registers a hit.
        local loc = n:K2_GetActorLocation()
        thrown = throwItemAt({ X = loc.X, Y = loc.Y, Z = loc.Z + 25.0 }, { X = dx, Y = dy, Z = 0.0 }, 2600.0, 130.0)
    end)
    if thrown then
        log("hit the neighbour: strength %.2f -> item thrown at him", strength)
        return
    end
    local speed = math.min(1100.0, 250.0 + 110.0 * strength)   -- uu/s
    local ok, e = pcall(function()
        n:LaunchCharacter({ X = dx * speed, Y = dy * speed, Z = 180.0 + 20.0 * strength }, true, true)
    end)
    log("hit the neighbour: strength %.2f -> no item to throw, pushed at %.0f uu/s%s", strength, speed, ok and "" or (" (failed: " .. tostring(e) .. ")"))
end

-- ======================================================================================
-- Minecraft impacts on Hello Neighbor's world: a punch/sword on one of its surfaces, an arrow stuck in it, an
-- explosion. Whatever is there gets the engine's generic damage (ApplyDamage: what Blueprints listen to), loose
-- physics props get pushed, destructible meshes (glass) fracture. Every new kind of object hit is logged once with
-- its break/damage-like functions, so a specific reaction can be added for it.
-- ======================================================================================
local EV_SURFACE_HIT, EV_EXPLOSION, EV_SCAN_CELL = 6, 7, 8
local IMPACT_TYPES = { 0, 1, 3, 5 }   -- ObjectTypeQuery1/2/4/6: WorldStatic, WorldDynamic, PhysicsBody, Destructible
st.DESTRUCTIBLE = nil
st.impactLogged = {}

local function mcToHost(mx, my, mz)
    return { X = mx * UNITS_PER_BLOCK, Y = mz * UNITS_PER_BLOCK, Z = my * UNITS_PER_BLOCK - yOff }
end

-- Once per object class: every Blueprint class in its parent chain with ALL its functions (native engine classes
-- are skipped: their functions are generic), and its components. That is what tells us how Hello Neighbor itself
-- breaks a window or knocks something over.
st.ACTOR_COMPONENT = nil
local function logImpactTarget(actor, comp)
    local cls = describe(actor)
    if st.impactLogged[cls] then return end
    st.impactLogged[cls] = true
    local chain = {}
    pcall(function()
        local c = actor:GetClass()
        for _ = 1, 8 do
            if c == nil or not c:IsValid() then break end
            local full = c:GetFullName()
            if full:find("/Script/", 1, true) then break end   -- native: stop
            local fns = {}
            c:ForEachFunction(function(fn)
                if #fns < 60 then fns[#fns + 1] = fn:GetFName():ToString() end
            end)
            chain[#chain + 1] = string.format("%s [%s]", c:GetFName():ToString(), table.concat(fns, ", "))
            c = c:GetSuperStruct()
        end
    end)
    local comps = {}
    pcall(function()
        if not exists(st.ACTOR_COMPONENT) then st.ACTOR_COMPONENT = StaticFindObject("/Script/Engine.ActorComponent") end
        local list = risky("components", function()
            local out = {}
            local ret = actor:K2_GetComponentsByClass(st.ACTOR_COMPONENT, out)
            return arrayOut(ret, out)
        end)
        for _, k in ipairs(list) do
            if #comps < 30 then comps[#comps + 1] = k:GetFName():ToString() .. ":" .. describe(k) end
        end
    end)
    log("impact target: %s (hit component %s)\n    blueprint chain: %s\n    components: %s", cls, describe(comp),
        #chain > 0 and table.concat(chain, " <- ") or "native only", #comps > 0 and table.concat(comps, ", ") or "?")
end

-- A small invisible physics ball thrown into the impact point, so Hello Neighbor's OWN collision logic decides what
-- happens (that is how its windows break when the player throws something). It removes itself after 1.5 s.
st.PEBBLE_MESH = nil
st.pebblesThrown, st.pebbleFailLogged = 0, false
local function throwPebble(from, vel)
    local pc = playerController()
    if not exists(pc) then return false end
    if not exists(st.GAMEPLAY_STATICS) then st.GAMEPLAY_STATICS = StaticFindObject("/Script/Engine.Default__GameplayStatics") end
    if not exists(st.PEBBLE_MESH) then st.PEBBLE_MESH = StaticFindObject("/Engine/BasicShapes/Cube.Cube") end
    local cls = StaticFindObject("/Script/Engine.StaticMeshActor")
    if not (exists(st.GAMEPLAY_STATICS) and exists(st.PEBBLE_MESH) and exists(cls)) then return false end
    local s = 0.12   -- 12 cm ball
    local xf = { Rotation = { X = 0.0, Y = 0.0, Z = 0.0, W = 1.0 }, Translation = from, Scale3D = { X = s, Y = s, Z = s } }
    local ok, e = pcall(function()
        local actor = st.GAMEPLAY_STATICS:BeginDeferredActorSpawnFromClass(pc, cls, xf, 1, nil)
        if not exists(actor) then error("spawn returned nothing") end
        local comp = actor.StaticMeshComponent
        comp.Mobility = 2   -- Movable, before the mesh is set (as for the cubes)
        comp:SetStaticMesh(st.PEBBLE_MESH)
        st.GAMEPLAY_STATICS:FinishSpawningActor(actor, xf)
        actor:SetActorScale3D({ X = s, Y = s, Z = s })
        actor:SetActorHiddenInGame(true)
        actor:SetLifeSpan(1.5)
        comp:SetCollisionProfileName(FName("PhysicsActor"))
        comp:SetNotifyRigidBodyCollision(true)
        comp:SetSimulatePhysics(true)
        pcall(function() comp:SetMassOverrideInKg(FName("None"), 5.0, true) end)
        comp:SetPhysicsLinearVelocity(vel, false, FName("None"))
    end)
    if ok then st.pebblesThrown = st.pebblesThrown + 1
    elseif not st.pebbleFailLogged then st.pebbleFailLogged = true log("physics throw failed: %s", tostring(e)) end
    return ok
end

-- Throw one at point p along dir: from 30 uu in front of it, at a speed growing with the hit's strength.
-- Hello Neighbor's throwables (apple, ball, box, can, book, ...) are Blueprints of the native class Simple, whose
-- own hit code breaks windows and stuns the neighbour. To hit something "for real", an invisible copy of an item
-- that is already in the level (so its class is loaded) is spawned just in front of the target, marked thrown by
-- the player (OnThrow, when its signature is the expected one) and sent flying into it. It removes itself after 3 s.
st.SIMPLE, st.itemClass, st.itemClassName, st.itemFailLogged, st.itemsThrown = nil, nil, nil, false, 0
st.onThrowSig = nil   -- false = OnThrow not usable

local function pickItemClass(pc)
    if st.itemClass then
        local ok, valid = pcall(function() return st.itemClass:IsValid() end)
        if ok and valid then return st.itemClass end
        st.itemClass = nil
    end
    if not exists(st.SIMPLE) then st.SIMPLE = StaticFindObject("/Script/HelloNeighbor.Simple") end
    if not exists(st.SIMPLE) then return nil end
    local items = risky("allactors", function()
        local out = {}
        local ret = st.GAMEPLAY_STATICS:GetAllActorsOfClass(pc, st.SIMPLE, out)
        return arrayOut(ret, out)
    end)
    local best, bestScore, names = nil, -1, {}
    for _, it in ipairs(items) do
        local name = describe(it)
        if #names < 12 then names[#names + 1] = name end
        -- small, sturdy throwables first
        local score = name:find("Golden", 1, true) and 0   -- a collectible, not a throwable
            or (name:find("Ball") or name:find("Can_") or name:find("Apple")) and 3 or (name:find("Box") or name:find("Book")) and 2 or 1
        if score > bestScore then best, bestScore = it, score end
    end
    if best then
        st.itemClass = best:GetClass()
        st.itemClassName = describe(best)
    end
    log("throwable items in the level: %d (%s); throwing copies of %s", #items, table.concat(names, ", "), tostring(st.itemClassName))
    if st.onThrowSig == nil then
        -- OnThrow lives on Simple or one of its native parents; log its parameters, use it only with one object.
        st.onThrowSig = false
        pcall(function()
            local c = st.SIMPLE
            for _ = 1, 5 do
                if c == nil or not c:IsValid() then break end
                local path = c:GetFullName():match("^%S+ (.+)$")
                local sig = path and signature(path .. ":OnThrow")
                if sig then
                    log("item OnThrow(%s) on %s", sig, path)
                    if sig:match("^[%w_]+:ObjectProperty$") then st.onThrowSig = sig end
                    break
                end
                c = c:GetSuperStruct()
            end
        end)
    end
    return st.itemClass
end

-- The item's sound properties (object properties named *Sound* anywhere on its class chain), found once per class.
local function itemSoundProps(cls)
    if st.soundPropsFor == cls then return st.soundProps end
    local props = {}
    pcall(function()
        local c = cls
        for _ = 1, 8 do
            if c == nil then break end
            c:ForEachProperty(function(p)
                local name, kind = p:GetFName():ToString(), p:GetClass():GetFName():ToString()
                if kind == "ObjectProperty" and name:lower():find("sound", 1, true) then props[#props + 1] = name end
            end)
            c = c:GetSuperStruct()
        end
    end)
    st.soundPropsFor, st.soundProps = cls, props
    log("thrown items are muted: %s", #props > 0 and table.concat(props, ", ") or "no sound properties found")
    return props
end

throwItemAt = function(p, dir, speed, back, quiet)
    local pc = playerController()
    if not exists(pc) then return false end
    if not exists(st.GAMEPLAY_STATICS) then st.GAMEPLAY_STATICS = StaticFindObject("/Script/Engine.Default__GameplayStatics") end
    local cls = pickItemClass(pc)
    if not cls then return false end
    back = back or 45.0
    local from = { X = p.X - dir.X * back, Y = p.Y - dir.Y * back, Z = p.Z - dir.Z * back }
    local xf = { Rotation = { X = 0.0, Y = 0.0, Z = 0.0, W = 1.0 }, Translation = from, Scale3D = { X = 1.0, Y = 1.0, Z = 1.0 } }
    -- Each step has its own crash guard (a crash inside the old single "throwitem" step on 2026-10-03 00:32 turned
    -- the whole throw off): a crash in an optional step (mute, OnThrow) only turns that step off.
    local ok, e = pcall(function()
        local actor = risky("item.spawn", function()
            local a = st.GAMEPLAY_STATICS:BeginDeferredActorSpawnFromClass(pc, cls, xf, 1, nil)
            if not exists(a) then error("spawn returned nothing") end
            return a
        end)
        -- Silent: the invisible item must not make its own hit sound (what it hits still sounds as usual).
        if not broken["item.mute"] then
            pcall(risky, "item.mute", function()
                for _, prop in ipairs(itemSoundProps(cls)) do pcall(function() actor[prop] = nil end) end
            end)
        end
        risky("item.finish", function()
            st.GAMEPLAY_STATICS:FinishSpawningActor(actor, xf)
            actor:SetActorHiddenInGame(true)
            -- Gone right after it reaches what was hit (0.03 s away): it kept bouncing around behind the window
            -- or the prop for 3 s, with its own sounds.
            actor:SetLifeSpan(0.25)
        end)
        if st.onThrowSig and not broken["item.onthrow"] then
            pcall(risky, "item.onthrow", function() actor:OnThrow(pc.Pawn) end)
        end
        risky("item.launch", function()
            local root = actor:K2_GetRootComponent()
            -- Silent: no hit events for the item itself, so it never plays its own impact/bounce sound. What it hits
            -- still gets its own (glass breaks, props react: they use their own hit events).
            -- Not near a window: glass breaks from the ITEM's hit event, so there it must keep its events (and sound).
            if quiet and not broken["item.quiet"] then pcall(risky, "item.quiet", function() root:SetNotifyRigidBodyCollision(false) end) end
            root:SetSimulatePhysics(true)
            root:SetPhysicsLinearVelocity({ X = dir.X * speed, Y = dir.Y * speed, Z = dir.Z * speed }, false, FName("None"))
        end)
    end)
    if ok then st.itemsThrown = st.itemsThrown + 1
    elseif not st.itemFailLogged then st.itemFailLogged = true log("item throw failed: %s", tostring(e)) end
    return ok
end

-- Throw at point p along dir: a real item if there is one in the level, else the plain physics block.
local function throwAt(p, dir, strength, quiet)
    local speed = math.min(4000.0, 1600.0 + 300.0 * strength)
    if throwItemAt(p, dir, speed, nil, quiet) then return true end
    return throwPebble({ X = p.X - dir.X * 30.0, Y = p.Y - dir.Y * 30.0, Z = p.Z - dir.Z * 30.0 },
        { X = dir.X * speed, Y = dir.Y * speed, Z = dir.Z * speed })
end

-- p: Unreal point; dir: unit push direction; strength: Minecraft damage; radius: query radius (uu).
local function hitWorld(p, dir, strength, radius, explosion)
    local pc = playerController()
    if not exists(pc) then return end
    local pawn = pc.Pawn
    if not exists(KSL) then KSL = StaticFindObject("/Script/Engine.Default__KismetSystemLibrary") end
    if not exists(st.GAMEPLAY_STATICS) then st.GAMEPLAY_STATICS = StaticFindObject("/Script/Engine.Default__GameplayStatics") end
    if st.DESTRUCTIBLE == nil then st.DESTRUCTIBLE = StaticFindObject("/Script/ApexDestruction.DestructibleComponent") or false end
    local comps = risky("overlapout", function()
        local out = {}
        local ret = KSL:SphereOverlapComponents(pawn, p, radius, IMPACT_TYPES, nil, { pawn }, out)
        return arrayOut(ret, out)
    end)
    local damage = strength * 10.0
    local push = math.min(1500.0, 200.0 + 80.0 * strength)   -- cm/s velocity change for loose props
    local done, actors, props, broke, window = {}, 0, 0, 0, false
    if st.WINDOW_BASE == nil then st.WINDOW_BASE = StaticFindObject("/Script/HelloNeighbor.Window") or false end
    if st.WINDOW3 == nil then st.WINDOW3 = StaticFindObject("/Script/HelloNeighbor.Window3") or false end
    if not exists(st.SIMPLE) then st.SIMPLE = StaticFindObject("/Script/HelloNeighbor.Simple") end
    -- Damage can make an object destroy itself (and its components) on the spot, and touching a destroyed one
    -- crashes the game (a creeper blast did). So: first read everything needed while all are alive, then act, and
    -- skip whatever the EndPlay hook (end of file) reports gone meanwhile.
    local hits = {}
    for _, comp in ipairs(comps) do
        pcall(function()
            local h = { comp = comp }
            local actor = comp:GetOwner()
            if actor ~= nil and actor:IsValid() then h.actor, h.owner = actor, actor:GetAddress() end
            h.destructible = st.DESTRUCTIBLE and comp:IsA(st.DESTRUCTIBLE) or false
            h.physics = comp:IsSimulatingPhysics() == true
            h.at = comp:K2_GetComponentLocation()
            if h.actor and not done[h.owner] then
                done[h.owner] = true
                h.damage = true
                if (st.WINDOW_BASE and actor:IsA(st.WINDOW_BASE)) or (st.WINDOW3 and actor:IsA(st.WINDOW3)) then window = true end
                logImpactTarget(actor, comp)
            end
            hits[#hits + 1] = h
        end)
    end
    local gone = {}
    st.endWatch = gone
    local function alive(h) return not (h.owner and gone[h.owner]) end
    -- Pushes first (they never destroy anything), then the damage, one actor at a time.
    for _, h in ipairs(hits) do
        if h.destructible and alive(h) then pcall(function()
            if explosion then h.comp:ApplyRadiusDamage(damage, p, radius, push, false)
            else h.comp:ApplyDamage(damage, p, dir, push) end
            broke = broke + 1
        end) end
        if h.physics and alive(h) then pcall(function()
            if explosion then h.comp:AddRadialImpulse(p, radius, push, 1, true)
            else h.comp:AddImpulse({ X = dir.X * push, Y = dir.Y * push, Z = dir.Z * push + 100.0 }, FName("None"), true) end
            props = props + 1
        end) end
    end
    for _, h in ipairs(hits) do
        if h.damage and alive(h) then pcall(function()
            actors = actors + 1
            st.GAMEPLAY_STATICS:ApplyDamage(h.actor, damage, pc, pawn, nil)
        end) end
    end
    st.endWatch = nil
    -- A blast throws a physics ball at each nearby object (up to 12), from the centre outwards (positions read above).
    if explosion then
        local speed = math.min(4000.0, 1500.0 + 200.0 * strength)
        for i = 1, math.min(12, #hits) do
            pcall(function()
                local l = hits[i].at
                local dx, dy, dz = l.X - p.X, l.Y - p.Y, l.Z - p.Z
                local len = math.sqrt(dx * dx + dy * dy + dz * dz)
                if len < 1.0 then return end
                dx, dy, dz = dx / len, dy / len, dz / len
                throwPebble({ X = p.X + dx * 20.0, Y = p.Y + dy * 20.0, Z = p.Z + dz * 20.0 }, { X = dx * speed, Y = dy * speed, Z = dz * speed })
            end)
        end
    end
    return #comps, actors, props, broke, window
end

-- a,b,c: point (Minecraft * 1000); d: strength * 100 | kind << 24 (0 melee, 1 arrow).
local function surfaceHit(a, b, c, d)
    local kind, strength = d >> 24, (d & 0xFFFFFF) / 100.0
    local mx, my, mz = a / 1000.0, b / 1000.0, c / 1000.0
    local p = mcToHost(mx, my, mz)
    -- Push direction: from the player's eye to the point (arrows come from the player too).
    local dir = { X = 0.0, Y = 0.0, Z = -1.0 }
    if mc and mc.x then
        local e = mcToHost(mc.x, mc.y + (mc.eye or 1.62), mc.z)
        local dx, dy, dz = p.X - e.X, p.Y - e.Y, p.Z - e.Z
        local l = math.sqrt(dx * dx + dy * dy + dz * dz)
        if l > 1e-3 then dir = { X = dx / l, Y = dy / l, Z = dz / l } end
    end
    local n, actors, props, broke, window = hitWorld(p, dir, strength, kind == 1 and 12.0 or 20.0, false)
    -- Every hit throws the invisible item at the point (as it always did): glass only breaks, and Hello Neighbor's
    -- objects only move, when something thrown hits them. The item is silent (item.quiet) and gone 0.25 s later.
    -- Silent unless a window is near (arrows stop up to ~20 uu before the pane: hence the wider look).
    if not window then
        pcall(function()
            local out = {}
            local pc = playerController()
            local ret = KSL:SphereOverlapComponents(pc.Pawn, p, 70.0, IMPACT_TYPES, nil, { pc.Pawn }, out)
            for _, comp in ipairs(arrayOut(ret, out)) do
                local a = comp:GetOwner()
                if a and ((st.WINDOW_BASE and a:IsA(st.WINDOW_BASE)) or (st.WINDOW3 and a:IsA(st.WINDOW3))) then window = true break end
            end
        end)
    end
    local thrown = throwAt(p, dir, strength, not window)
    log("%s on Hello Neighbor's world at (%.0f, %.0f, %.0f), strength %.1f: %d component(s), %d actor(s) damaged, %d prop(s) pushed, %d destructible(s), thrown object %s (%d items, %d blocks so far)",
        kind == 1 and "arrow" or "hit", p.X, p.Y, p.Z, strength, n or 0, actors or 0, props or 0, broke or 0,
        thrown and (window and "sent (window: with sound)" or "sent (silent)") or "FAILED", st.itemsThrown, st.pebblesThrown)
end

-- Explosions vs Hello Neighbor's own world. Its windows ignore damage and only break when something thrown hits the
-- glass, so a blast throws an invisible real item (throwItemAt) from the centre into every intact pane in reach.
-- Its throwables (native Simple) at rest are woken and blown outwards; items in the player's inventory are skipped.
st.WINDOW, st.invAddrs = nil, {}
function st.allOf(pc, path, key)
    if not exists(st[key]) then st[key] = StaticFindObject(path) end
    if not exists(st[key]) then return {} end
    return risky("allactors", function()
        local out = {}
        local ret = st.GAMEPLAY_STATICS:GetAllActorsOfClass(pc, st[key], out)
        return arrayOut(ret, out)
    end)
end

function st.blastWindows(pc, p, radius)
    local panes, windows = 0, 0
    for _, w in ipairs(st.allOf(pc, "/Script/HelloNeighbor.Window3", "WINDOW")) do
        if panes >= 8 then break end
        pcall(function()
            local hit = false
            for i = 1, 3 do
                local glass = w["GlassMesh" .. i]
                if glass ~= nil and glass:GetAddress() ~= 0 and not w["bCrashed" .. i] then
                    local g = glass:K2_GetComponentLocation()
                    local dx, dy, dz = g.X - p.X, g.Y - p.Y, g.Z - p.Z
                    local dist = math.sqrt(dx * dx + dy * dy + dz * dz)
                    if dist < radius and dist > 1.0 and panes < 8 then
                        local dir = { X = dx / dist, Y = dy / dist, Z = dz / dist }
                        if throwItemAt(g, dir, 2600.0, 60.0) then panes = panes + 1 hit = true end
                    end
                end
            end
            if hit then windows = windows + 1 end
        end)
    end
    return panes, windows
end

function st.blastProps(pc, p, radius, strength)
    local n = 0
    for _, it in ipairs(st.allOf(pc, "/Script/HelloNeighbor.Simple", "SIMPLE")) do
        if n >= 24 then break end
        pcall(function()
            if st.invAddrs[it:GetAddress()] then return end   -- carried by the player
            local l = it:K2_GetActorLocation()
            local dx, dy, dz = l.X - p.X, l.Y - p.Y, l.Z - p.Z
            local dist = math.sqrt(dx * dx + dy * dy + dz * dz)
            if dist >= radius then return end
            local root = it:K2_GetRootComponent()
            if not root:IsSimulatingPhysics() then root:SetSimulatePhysics(true) end
            local s = (1.0 - dist / radius) * (600.0 + 120.0 * strength) + 200.0   -- cm/s, stronger near the centre
            local l2 = math.max(1.0, dist)
            root:AddImpulse({ X = dx / l2 * s, Y = dy / l2 * s, Z = dz / l2 * s + s * 0.5 }, FName("None"), true)
            n = n + 1
        end)
    end
    return n
end

-- a,b,c: centre (Minecraft * 1000); d: radius * 100 (blocks).
local function explosionHit(a, b, c, d)
    local p = mcToHost(a / 1000.0, b / 1000.0, c / 1000.0)
    local radiusBlocks = d / 100.0
    local radius = radiusBlocks * UNITS_PER_BLOCK * 1.5
    local n, actors, props, broke = hitWorld(p, { X = 0.0, Y = 0.0, Z = 1.0 }, radiusBlocks * 2.5, radius, true)
    local pc = playerController()
    local okW, panes, windows = pcall(st.blastWindows, pc, p, radius * 1.2)
    if not okW then log("explosion: windows failed: %s", tostring(panes)) panes, windows = 0, 0 end
    local okP, blown = pcall(st.blastProps, pc, p, radius * 1.3, radiusBlocks * 2.5)
    if not okP then log("explosion: objects failed: %s", tostring(blown)) blown = 0 end
    -- The neighbour is knocked down and thrown when he is close.
    local nb = findNeighbour()
    local thrown = false
    if nb then
        pcall(function()
            local loc = nb:K2_GetActorLocation()
            local dx, dy = loc.X - p.X, loc.Y - p.Y
            local dist = math.sqrt(dx * dx + dy * dy + (loc.Z - p.Z) ^ 2)
            if dist < radius then
                local l = math.max(1.0, math.sqrt(dx * dx + dy * dy))
                local s = 1400.0 * (1.0 - dist / radius) + 300.0
                neighbourFall(nb)
                nb:LaunchCharacter({ X = dx / l * s, Y = dy / l * s, Z = 300.0 + s * 0.4 }, true, true)
                thrown = true
            end
        end)
    end
    log("explosion in Hello Neighbor's world at (%.0f, %.0f, %.0f), radius %.1f blocks: %d component(s), %d actor(s) damaged, %d prop(s) pushed, %d destructible(s), %d glass pane(s) in %d window(s) hit, %d object(s) blown away%s",
        p.X, p.Y, p.Z, radiusBlocks, n or 0, actors or 0, props or 0, broke or 0, panes, windows, blown, thrown and "; the neighbour was knocked down and thrown" or "")
end

-- ======================================================================================
-- Hello Neighbor's own interactions (doors, switches, picking things up) and its inventory.
-- Hello Neighbor binds E = InputPickUp/InputAction, LMB = InputApply, RMB = InputThrow/InputPutDown, 1-4 = slots
-- (Config/DefaultInput.ini); the player remaps them in its settings (read back by ia.keysTick).
-- ======================================================================================
local ia = { focusAt = 0 }
function ia.show(v)
    if v == nil then return "nil" end
    if type(v) ~= "userdata" then return tostring(v) end
    local ok, s = pcall(function() if v:GetAddress() == 0 then return "none" end return v:GetFullName() end)
    return ok and s or tostring(v)
end

-- Hello Neighbor has no "what am I looking at" function; its cursor widget (pawn.m_pCursor, BP_Cursor_C) changes
-- when an interactable is in reach. Log every change of the cursor's simple properties, and of what the player
-- holds (GetHoldingItem, the inventory's current object), to find that signal.
ia.SIMPLE_KINDS = { BoolProperty = true, ByteProperty = true, EnumProperty = true, IntProperty = true, FloatProperty = true, ObjectProperty = true }
function ia.focusTick()
    local now = os.clock()
    if now - ia.focusAt < 0.2 then return end
    ia.focusAt = now
    local pawn = playerController().Pawn
    local cursor = pawn.m_pCursor
    if ia.cursorProps == nil then
        ia.cursorProps, ia.cursorState = {}, {}
        local c = cursor:GetClass()
        for _ = 1, 4 do
            if c == nil or not c:IsValid() or c:GetFName():ToString() == "UserWidget" then break end
            c:ForEachProperty(function(p)
                local kind = p:GetClass():GetFName():ToString()
                if ia.SIMPLE_KINDS[kind] then ia.cursorProps[#ia.cursorProps + 1] = p:GetFName():ToString() end
            end)
            c = c:GetSuperStruct()
        end
        local fns = {}
        pcall(function() cursor:GetClass():ForEachFunction(function(f) fns[#fns + 1] = f:GetFName():ToString() end) end)
        log("cursor %s: watching %d properties (%s); functions: %s", describe(cursor), #ia.cursorProps, table.concat(ia.cursorProps, ", "), table.concat(fns, ", "))
    end
    local changes = {}
    local function watch(name, v)
        local s = ia.show(v)
        if s:find("Function ", 1, true) then return end
        if ia.cursorState[name] ~= s then
            changes[#changes + 1] = name .. "=" .. s
            ia.cursorState[name] = s
        end
    end
    for _, name in ipairs(ia.cursorProps) do
        local ok, v = pcall(function() return cursor[name] end)
        if ok then watch("cursor." .. name, v) end
    end
    pcall(risky, "ia.getters", function()
        watch("holding", pawn:GetHoldingItem())
        watch("drag", pawn:GetDragObject())
        watch("hands", pawn:GetHandsState())
        watch("invCurrent", pawn.m_pInventory:GetCurrentObject())
        watch("active", pawn:IsActive())
        watch("state", pawn:GetState())
        watch("moveMode", pawn.CharacterMovement.MovementMode)
        watch("cutscene", pawn:IsPlayCutScene())
        -- Did Hello Neighbor's input code see a press? Its idle timer drops back to ~0.
        local idle = pawn:GetTimeNotInput()
        if ia.idle and ia.idle > 0.6 and idle < ia.idle - 0.3 then changes[#changes + 1] = string.format("INPUT SEEN (idle %.1f s -> %.2f s)", ia.idle, idle) end
        ia.idle = idle
    end)
    if #changes > 0 then log("interaction state: %s", table.concat(changes, "  ")) end
end

-- The player's CURRENT Hello Neighbor key for each action (the settings menu can rebind them, e.g. E -> R), written
-- to hn_keys.txt for hn_gfx, which presses exactly that key when Minecraft asks for the action. Read from the live
-- PlayerInput (falls back to InputSettings); refreshed every 3 s, the file only rewritten on a change.
ia.ACTIONS = { use = { "InputPickUp", "InputAction" }, apply = { "InputApply" }, throw = { "InputThrow", "InputPutDown" } }
ia.keysAt, ia.keysText = -100, nil
function ia.readMappings(obj)
    local list = {}
    local arr = obj.ActionMappings
    arr:ForEach(function(_, e)
        local m = e:get()
        list[#list + 1] = { m.ActionName:ToString(), m.Key.KeyName:ToString() }
    end)
    return list
end

function ia.keysTick()
    local now = os.clock()
    if now - ia.keysAt < 3.0 then return end
    ia.keysAt = now
    local list, from = {}, "PlayerInput"
    pcall(function() list = ia.readMappings(playerController().PlayerInput) end)
    if #list == 0 then
        from = "InputSettings"
        pcall(function() list = ia.readMappings(StaticFindObject("/Script/Engine.Default__InputSettings")) end)
    end
    if #list == 0 then return end
    local lines, all = {}, {}
    for _, kind in ipairs({ "use", "apply", "throw" }) do
        local key
        for _, action in ipairs(ia.ACTIONS[kind]) do
            for _, m in ipairs(list) do
                if not key and m[1] == action and not m[2]:find("^Gamepad") and not m[2]:find("^MotionController") then key = m[2] end
            end
        end
        if key then lines[#lines + 1] = kind .. " " .. key end
    end
    local text = table.concat(lines, "\n") .. "\n"
    if text == ia.keysText then return end
    ia.keysText = text
    writeFile("hn_keys.txt", text)
    for _, m in ipairs(list) do if m[1]:find("^Input") and not m[2]:find("^Gamepad") then all[#all + 1] = m[1] .. "=" .. m[2] end end
    log("Hello Neighbor keys (from %s): %s  [all: %s]", from, (text:gsub("\n", "; ")), table.concat(all, ", "))
end

-- Hello Neighbor's inventory as Minecraft items (HnItems.java). Every actor in pawn.m_pInventory gets an id; its
-- name goes to Minecraft (kCmdHnItem), which keeps a matching hotbar item. Minecraft says which one is in its hand
-- (kEvHnSelect): that one is taken out here (SelectInventoryObject) and shown; with a Minecraft item in the hand
-- (id 0) the item Hello Neighbor holds is hidden, so the player never holds two things.
ia.CMD_HN_ITEM, ia.EV_HN_SELECT = 11, 9
ia.byAddr, ia.byId, ia.nextId, ia.want, ia.hidden = {}, {}, 1, 0, {}
ia.invAt, ia.resendAt, ia.selectAt = 0, 0, 0

function ia.cleanName(actor)
    local n = describe(actor):gsub("^BP_", ""):gsub("_C$", ""):gsub("_[Aa]partments%d*", ""):gsub("_%d+$", ""):gsub("_", " ")
    n = n:gsub("[^ -~]", "")
    return #n > 0 and n:sub(1, 24) or "Item"
end

function ia.sendItem(id, name)
    local function word(s, i)
        local w = 0
        for k = 0, 3 do w = w | ((s:byte(i + k) or 0) << (8 * k)) end
        return w
    end
    for part = 0, 1 do
        local s = name:sub(part * 12 + 1, part * 12 + 12)
        if part == 0 or #s > 0 then
            queueCmd(ia.CMD_HN_ITEM, id | (part << 16) | (1 << 24), word(s, 1), word(s, 5), word(s, 9))
        end
    end
end

function ia.forget()
    ia.byAddr, ia.byId, ia.hidden, ia.want, ia.pose, ia.posedFor = {}, {}, {}, 0, {}, nil
    interact.held, interact.byClass = nil, {}
    queueCmd(ia.CMD_HN_ITEM, 0, 0, 0, 0)   -- id 0, present 0: Minecraft drops all of them
end

function ia.setHidden(actor, hide)
    local addr = actor:GetAddress()
    if hide == (ia.hidden[addr] ~= nil) then return end
    actor:SetActorHiddenInGame(hide)
    ia.hidden[addr] = hide and actor or nil
end

function ia.invTick()
    local now = os.clock()
    if now - ia.invAt < 0.2 then return end
    ia.invAt = now
    local pawn = playerController().Pawn
    local inv = pawn.m_pInventory
    local list = risky("ia.slots", function() return arrayOut(inv:GetActorsInSlots(), {}) end)
    local seen, order = {}, {}
    for i, a in ipairs(list) do
        local ok, addr = pcall(function() return a:GetAddress() end)
        if ok and addr ~= 0 then
            seen[addr] = true
            local e = ia.byAddr[addr]
            if not e then
                e = { id = ia.nextId, actor = a, name = ia.cleanName(a) }
                ia.nextId = ia.nextId % 65535 + 1
                ia.byAddr[addr], ia.byId[e.id] = e, e
                ia.sendItem(e.id, e.name)
                log("Hello Neighbor inventory: + %s (item %d, list index %d)", e.name, e.id, i)
            end
            e.index = i
            order[#order + 1] = e
        end
    end
    st.invAddrs = ia.byAddr   -- the explosion code must not blow away what the player carries
    for addr, e in pairs(ia.byAddr) do
        if not seen[addr] then
            ia.byAddr[addr], ia.byId[e.id] = nil, nil
            queueCmd(ia.CMD_HN_ITEM, e.id, 0, 0, 0)
            -- Thrown / put down while hidden by us: it must be visible in the world.
            if ia.hidden[addr] then pcall(ia.setHidden, e.actor, false) end
            ia.unpose(addr)   -- its real size again
            log("Hello Neighbor inventory: - %s (item %d)", e.name, e.id)
        end
    end
    if now - ia.resendAt > 3.0 then   -- Minecraft may have restarted: repeat the whole list now and then
        ia.resendAt = now
        for _, e in ipairs(order) do ia.sendItem(e.id, e.name) end
    end

    -- The hand: what Minecraft holds decides.
    local holding = risky("ia.holding", function() return pawn:GetHoldingItem() end)
    local holdAddr = 0
    pcall(function() if holding ~= nil then holdAddr = holding:GetAddress() end end)
    if ia.want == 0 then
        if holdAddr ~= 0 and ia.byAddr[holdAddr] then ia.setHidden(holding, true) end
        return
    end
    local e = ia.byId[ia.want]
    if not e then return end
    local cur = risky("ia.current", function() return inv:GetCurrentObject() end)
    local curAddr = 0
    pcall(function() if cur ~= nil then curAddr = cur:GetAddress() end end)
    local targetAddr = e.actor:GetAddress()
    if curAddr ~= targetAddr and now - ia.selectAt > 0.5 then
        ia.selectAt = now
        ia.selectSlot(pawn, inv, e, targetAddr)
    end
    if ia.hidden[targetAddr] then ia.setHidden(e.actor, false) end
end

-- SelectInventoryObject(slot): whether slots count from 0 or 1, and whether the list has gaps, is not known; try the
-- list index first (with the base that worked last time), then every slot, and keep what worked.
ia.selBase = 0
function ia.selectSlot(pawn, inv, e, targetAddr)
    local tries = { e.index - 1 + ia.selBase }
    for n = 0, 4 do if n ~= tries[1] then tries[#tries + 1] = n end end
    for _, n in ipairs(tries) do
        risky("ia.select", function() pawn:SelectInventoryObject(n) end)
        local ok, now = pcall(function() return inv:GetCurrentObject():GetAddress() end)
        if ok and now == targetAddr then
            ia.selBase = n - (e.index - 1)
            log("Hello Neighbor hand: %s (SelectInventoryObject(%d), list index %d)", e.name, n, e.index)
            return true
        end
    end
    log("Hello Neighbor hand: could not select %s (list index %d); trying again in 5 s", e.name, e.index)
    ia.selectAt = os.clock() + 4.5
    return false
end

-- The item Hello Neighbor holds is placed by us, every frame, where Minecraft holds things: Hello Neighbor holds it in
-- front of its own (hidden) first-person arms, which looked wrong next to Steve's. First person: bottom right of the
-- view, following the camera (pawn.Camera sits at Minecraft's eye). Third person (F5): in Steve's right hand, from
-- Minecraft's feet and yaw.
-- Every item is held the same way, like a Minecraft item: its BOUNDING-BOX CENTRE (not its pivot, which differs per
-- item) goes to Minecraft's own hand point (kEvHeldFirst / kEvHeldThird, world positions, every frame), and it is
-- scaled to one held-item size while it is in the hand (its real size
-- comes back when it leaves the inventory: thrown, put down, used).
ia.HAND_RIGHT, ia.HAND_UP, ia.HAND_FWD = 0.36, 0.62, 0.35   -- third person: item centre, blocks from the feet
ia.FP_FWD, ia.FP_RIGHT, ia.FP_DOWN = 38.0, 20.0, 22.0         -- first person: item centre, Unreal units from the eye
ia.HELD_HALF = 18.0                                           -- largest half-extent of a held item, Unreal units
ia.KML, ia.pose = nil, {}

function ia.bounds(actor, onlyColliding)
    local o, x = {}, {}
    local r1, r2, r3 = actor:GetActorBounds(onlyColliding == true, o, x)
    if o.X == nil and type(r2) ~= "nil" then o, x = r2, r3 end   -- out parameters either fill the tables or come back
    return o, x
end

-- The item's original scale, once, and its shrink for the hand.
function ia.poseState(actor)
    local addr = actor:GetAddress()
    local p = ia.pose[addr]
    if p then return p end
    local s = actor:GetActorScale3D()
    p = { actor = actor, scale = { X = s.X, Y = s.Y, Z = s.Z }, off = { X = 0.0, Y = 0.0, Z = 0.0 } }
    -- Size: the smaller of all parts / colliding parts only (an attached cable or trigger volume can be huge).
    local function half(onlyColliding)
        local _, ext = ia.bounds(actor, onlyColliding)
        local m = math.max(ext.X or 0, ext.Y or 0, ext.Z or 0)
        return m > 0.5 and m or math.huge
    end
    local m = math.min(half(false), half(true))
    local k = m < math.huge and math.max(0.2, math.min(3.0, ia.HELD_HALF / m)) or 1.0
    if math.abs(k - 1.0) > 0.02 then actor:SetActorScale3D({ X = s.X * k, Y = s.Y * k, Z = s.Z * k }) end
    ia.pose[addr] = p
    log("held item %s: half size %.0f uu (all parts %.0f), shown at %.0f%%", describe(actor), m, half(false), k * 100)
    return p
end

-- Out of the inventory: real size again.
function ia.unpose(addr)
    local p = ia.pose[addr]
    if not p then return end
    ia.pose[addr] = nil
    pcall(function() p.actor:SetActorScale3D(p.scale) end)
end

function ia.poseHeld()
    if not followOn or not mc then interact.held = nil return end
    local e = ia.want ~= 0 and ia.byId[ia.want] or nil
    if not e or ia.hidden[e.actor:GetAddress()] then interact.held = nil return end
    local actor = e.actor
    interact.held = actor
    local p = ia.poseState(actor)
    local pos, rot
    if (mc.flags & MC_CAM_DETACHED) == 0 then
        if not exists(ia.KML) then ia.KML = StaticFindObject("/Script/Engine.Default__KismetMathLibrary") end
        local cam = playerController().Pawn.Camera
        local loc, crot = cam:K2_GetComponentLocation(), cam:K2_GetComponentRotation()
        local F, R, U = ia.KML:GetForwardVector(crot), ia.KML:GetRightVector(crot), ia.KML:GetUpVector(crot)
        local fp, B = ia.fpPose, UNITS_PER_BLOCK
        if fp and os.clock() - fp.at < 0.25 then   -- Minecraft's first-person hand this frame (world, bob and swing included)
            -- Relative to the camera we just moved: the big motion is this frame's; only bob/swing can be a frame old.
            pos = { X = loc.X + fp[1] * B, Y = loc.Y + fp[3] * B, Z = loc.Z + fp[2] * B }
            local fr = ia.fpRot
            if fr and os.clock() - fr.at < 0.25 then rot = { Pitch = crot.Pitch + fr.Pitch, Yaw = crot.Yaw + fr.Yaw, Roll = crot.Roll + fr.Roll } end
        else
            local f, r, d = ia.FP_FWD, ia.FP_RIGHT, -ia.FP_DOWN
            pos = { X = loc.X + F.X * f + R.X * r + U.X * d, Y = loc.Y + F.Y * f + R.Y * r + U.Y * d, Z = loc.Z + F.Z * f + R.Z * r + U.Z * d }
        end
        rot = rot or { Pitch = crot.Pitch, Yaw = crot.Yaw, Roll = 0.0 }
    else
        local B, tp = UNITS_PER_BLOCK, ia.tpPose
        if tp and os.clock() - tp.at < 0.25 then   -- Steve's hand this frame (walk swing included)
            -- Relative to the feet this update uses (the camera and pawn follow the same ones).
            pos = { X = (mc.x + tp[1]) * B, Y = (mc.z + tp[3]) * B, Z = (mc.y + tp[2]) * B - yOff }
            -- Turns with the arm: swinging it forward (Minecraft xRot down) lifts the item's front (Unreal pitch up).
            rot = { Pitch = -math.deg(tp.swing or 0), Yaw = tp.yaw + 90.0, Roll = 0.0 }
        else
            local yaw = math.rad(mc.yaw)
            -- Minecraft: forward = (-sin yaw, 0, cos yaw), right = (-cos yaw, 0, -sin yaw); Unreal (x, y, z) = (mx, mz, my).
            local fx, fz, rx, rz = -math.sin(yaw), math.cos(yaw), -math.cos(yaw), -math.sin(yaw)
            pos = { X = (mc.x + rx * ia.HAND_RIGHT + fx * ia.HAND_FWD) * B, Y = (mc.z + rz * ia.HAND_RIGHT + fz * ia.HAND_FWD) * B,
                    Z = (mc.y + ia.HAND_UP) * B - yOff }
            rot = { Pitch = 0.0, Yaw = mc.yaw + 90.0, Roll = 0.0 }
        end
    end
    -- Pivot = where the centre should be minus the centre's offset from the pivot (measured last frame, same pose).
    actor:K2_TeleportTo({ X = pos.X - p.off.X, Y = pos.Y - p.off.Y, Z = pos.Z - p.off.Z }, rot)
    local o = ia.bounds(actor)
    local a = actor:K2_GetActorLocation()
    if o.X then p.off = { X = o.X - a.X, Y = o.Y - a.Y, Z = o.Z - a.Z } end
    if ia.posedFor ~= actor then
        ia.posedFor = actor
        log("%s is held where Minecraft holds items (centre %.0f, %.0f, %.0f uu from its pivot)", e.name, p.off.X, p.off.Y, p.off.Z)
    end
end

-- What under the crosshair can Hello Neighbor's player use? The actors of the components right at the surface point
-- (a little in front of it, inside the object) are checked against Hello Neighbor's interactable native classes,
-- once per class (logged, so a wrong guess shows up in the log).
-- Not FlatActor: it is also the base of windows, furniture (Stand) and the level script (BP_Act1_C).
interact.CLASSES = { "Simple", "Door", "Tumbler", "Electric", "Interactive", "Switch" }
interact.byClass, interact.natives = {}, nil
function interact.isInteractable(actor)
    local cls = actor:GetClass()
    local key = cls:GetAddress()
    local v = interact.byClass[key]
    if v ~= nil then return v end
    if not interact.natives then
        interact.natives = {}
        local found = {}
        for _, n in ipairs(interact.CLASSES) do
            local c = StaticFindObject("/Script/HelloNeighbor." .. n)
            if exists(c) then interact.natives[#interact.natives + 1] = { n, c } found[#found + 1] = n end
        end
        interact.ICOMP = StaticFindObject("/Script/HelloNeighbor.InteractiveComponent")
        log("right-click interact: Hello Neighbor classes found: %s; InteractiveComponent %s", table.concat(found, ", "), exists(interact.ICOMP) and "found" or "not found")
    end
    local why
    for _, e in ipairs(interact.natives) do
        if not why and actor:IsA(e[2]) then why = e[1] end
    end
    if not why and exists(interact.ICOMP) then
        pcall(function()
            local c = actor:GetComponentByClass(interact.ICOMP)
            if c ~= nil and c:GetAddress() ~= 0 then why = "InteractiveComponent" end
        end)
    end
    -- The class chain, for the log.
    local chain = {}
    pcall(function()
        local c = cls
        for _ = 1, 8 do
            if c == nil or not c:IsValid() then break end
            chain[#chain + 1] = c:GetFName():ToString()
            c = c:GetSuperStruct()
        end
    end)
    interact.byClass[key] = why ~= nil
    log("right-click interact: %s -> %s  [%s]", describe(actor), why and ("yes (" .. why .. ")") or "no", table.concat(chain, " < "))
    return why ~= nil
end

function interact.classify(pc, ip, n)
    local p = { X = ip.X - n.X * 6.0, Y = ip.Y - n.Y * 6.0, Z = ip.Z - n.Z * 6.0 }   -- just inside the surface
    local comps = risky("overlapout", function()
        local out = {}
        local ret = KSL:SphereOverlapComponents(pc.Pawn, p, 10.0, IMPACT_TYPES, nil, interact.held and { pc.Pawn, interact.held } or { pc.Pawn }, out)
        return arrayOut(ret, out)
    end)
    local seen = {}
    for _, comp in ipairs(comps) do
        local ok, yes = pcall(function()
            local a = comp:GetOwner()
            if a == nil or a:GetAddress() == 0 or seen[a:GetAddress()] then return false end
            seen[a:GetAddress()] = true
            return interact.isInteractable(a)
        end)
        if ok and yes then return true end
    end
    return false
end

-- Fast movement (elytra, rockets, ender pearls in flight): the area scan around the player (VOX_R cells) cannot keep up,
-- so walls and floors appeared only once the player was already in them (a jump up on landing). Ask for the cells
-- along the way ahead first: every cell on the predicted path for the next st.AHEAD_S seconds, one cell around it and from
-- the feet to above the head. voxPass scans asked-for cells before the area, and skips ones seen in the last 30 s.
st.AHEAD_S, st.AHEAD_MIN_SPEED = 1.5, 8.0              -- seconds ahead; blocks per second
function st.scanAhead()
    if not mc or (mc.flags & MC_DRIVING) == 0 or not mc.x then st.prevMc = nil return end
    local now = os.clock()
    local p = st.prevMc
    st.prevMc = { x = mc.x, y = mc.y, z = mc.z, t = now }
    if not p or now - p.t <= 0 or now - p.t > 0.5 then return end
    local dt = now - p.t
    local vx, vy, vz = (mc.x - p.x) / dt, (mc.y - p.y) / dt, (mc.z - p.z) / dt
    local speed = math.sqrt(vx * vx + vy * vy + vz * vz)
    if speed < st.AHEAD_MIN_SPEED then return end
    -- Only re-ask when we moved on by a cell (or changed direction): the same cells every frame are wasted work.
    local cx, cy, cz = math.floor(mc.x), math.floor(mc.y), math.floor(mc.z)
    local dirKey = string.format("%d,%d,%d", math.floor(vx / speed * 4), math.floor(vy / speed * 4), math.floor(vz / speed * 4))
    local k = cx .. "," .. cy .. "," .. cz .. "," .. dirKey
    if k == st.aheadKey then return end
    st.aheadKey = k
    -- A new path replaces the old one (nearest cells first; voxPass skips cells it already knows).
    local list, seen = {}, {}
    local steps = math.ceil(speed * st.AHEAD_S)
    for i = 1, steps do
        local t = i / speed                                  -- one cell further along per step
        local x, y, z = math.floor(mc.x + vx * t), math.floor(mc.y + vy * t), math.floor(mc.z + vz * t)
        for dx = -1, 1 do for dz = -1, 1 do for dy = -1, 2 do
            local kk = voxAhead.key(x + dx, y + dy, z + dz)
            if not seen[kk] then seen[kk] = true list[#list + 1] = { x + dx, y + dy, z + dz } end
        end end end
    end
    voxAhead.list, voxAhead.head = list, 1
end

-- Doors (yours or the neighbour's): an opened door must stop blocking Minecraft at once, not at the next rescan.
-- Once a second the doors within DOOR_NEAR_UU are listed; every frame their colliding bounds are read, and while one
-- moves, every cell its old and new bounds cover is rescanned right away (forced, before anything else; again ~0.2 s
-- after it stops, for the final position).
st.DOOR_NEAR_UU, st.doors, st.doorsAt = 8 * 84.0, {}, -100
function st.doorTick()
    if not mc or (mc.flags & MC_DRIVING) == 0 then return end
    if not st.endHookOn then return end   -- without the EndPlay hook a held door could be a dead one (see below)
    local pc = playerController()
    if not exists(pc) or not exists(pc.Pawn) then return end
    local now = os.clock()
    if now - st.doorsAt > 1.0 then
        st.doorsAt = now
        local me = pc.Pawn:K2_GetActorLocation()
        local keep = {}
        for _, d in ipairs(st.allOf(pc, "/Script/HelloNeighbor.Door", "DOOR")) do
            local okL, l = pcall(function() return d:K2_GetActorLocation() end)
            if okL and l then
                local dx, dy, dz = l.X - me.X, l.Y - me.Y, l.Z - me.Z
                if dx * dx + dy * dy + dz * dz < st.DOOR_NEAR_UU * st.DOOR_NEAR_UU then
                    local addr = d:GetAddress()
                    keep[addr] = st.doors[addr] or { actor = d }
                end
            end
        end
        st.doors = keep
    end
    local B = UNITS_PER_BLOCK
    for _, rec in pairs(st.doors) do
        local ok, o, e = pcall(ia.bounds, rec.actor, true)
        if ok and o and o.X and e and e.X then
            local x0, x1 = math.floor((o.X - e.X) / B), math.floor((o.X + e.X) / B)
            local z0, z1 = math.floor((o.Y - e.Y) / B), math.floor((o.Y + e.Y) / B)
            local y0, y1 = math.floor((o.Z - e.Z + yOff) / B), math.floor((o.Z + e.Z + yOff) / B)
            local moved = rec.ox and (math.abs(o.X - rec.ox) + math.abs(o.Y - rec.oy) + math.abs(o.Z - rec.oz)
                + math.abs(e.X - rec.ex) + math.abs(e.Y - rec.ey) + math.abs(e.Z - rec.ez) > 0.5)
            if moved then rec.settleAt = now + 0.2 end
            local final = rec.settleAt and not moved and now >= rec.settleAt
            if (moved and now - (rec.askedAt or -1) >= 0.08) or final then
                rec.askedAt = now
                if final then rec.settleAt = nil end
                -- old and new bounds together (a swinging door sweeps through the cells in between)
                local ax0, ax1 = math.min(x0, rec.x0 or x0), math.max(x1, rec.x1 or x1)
                local ay0, ay1 = math.min(y0, rec.y0 or y0), math.max(y1, rec.y1 or y1)
                local az0, az1 = math.min(z0, rec.z0 or z0), math.max(z1, rec.z1 or z1)
                local u = voxAhead.urgent
                if (ax1 - ax0 + 1) * (ay1 - ay0 + 1) * (az1 - az0 + 1) <= 200 then
                    for x = ax0, ax1 do for y = ay0, ay1 do for z = az0, az1 do u[#u + 1] = { x, y, z, true } end end end
                end
                st.doorLogs = (st.doorLogs or 0) + 1
                if st.doorLogs <= 20 then log("door watch: %s moved, its %d cell(s) rescanned at once",
                    describe(rec.actor), (ax1 - ax0 + 1) * (ay1 - ay0 + 1) * (az1 - az0 + 1)) end
            end
            rec.ox, rec.oy, rec.oz, rec.ex, rec.ey, rec.ez = o.X, o.Y, o.Z, e.X, e.Y, e.Z
            rec.x0, rec.x1, rec.y0, rec.y1, rec.z0, rec.z1 = x0, x1, y0, y1, z0, z1
        end
    end
end

-- The player died in Minecraft: back to the level's starting point (spawnPt, see sample). The pawn is put there;
-- follow() then sees it far from where it put it and Minecraft follows, like any move Hello Neighbor makes.
-- (Calling the player's OnNeighborCatchPlayer alone did nothing.)
function st.catchPlayer()
    local pc = playerController()
    if not exists(pc) or not exists(pc.Pawn) then log("Minecraft death: no player pawn") return end
    -- Hello Neighbor's own respawn point for this map if known, else where the level started.
    local s, what = spawnPt.resp[lastMap], "Hello Neighbor's respawn point"
    if not s then
        s, what = spawnPt, "the starting point (no respawn point known yet: get caught once on this map)"
        if s.map == nil or s.map ~= lastMap then log("Minecraft death: no starting point known for %s", tostring(lastMap)) return end
    end
    local rot = { Pitch = 0.0, Yaw = s.Yaw, Roll = 0.0 }
    local ok = pc.Pawn:K2_TeleportTo({ X = s.X, Y = s.Y, Z = s.Z }, rot)
    pcall(function() pc:SetControlRotation(rot) end)
    log("Minecraft death: back to %s (%.0f, %.0f, %.0f)%s", what, s.X, s.Y, s.Z, ok and "" or " - teleport refused")
end

local function readReply()
    while true do
        local line = rf:read("l")
        if not line then error("bridge closed the pipe") end
        local c = line:sub(1, 1)
        if c == "E" then
            eventsSeen = eventsSeen + 1
            local t, a, b, cc, d = line:match("^E (%-?%d+) (%-?%d+) (%-?%d+) (%-?%d+) (%-?%d+)")
            t, a, b, cc, d = tonumber(t), tonumber(a), tonumber(b), tonumber(cc), tonumber(d)
            if t == EV_BLOCK_CHANGE then
                applyBlockChange(a, b, cc, d)
                log("block (%d, %d, %d) -> %s  [%d shown]", a, b, cc, d == 0 and "air" or (BLOCK_NAMES[d] or tostring(d)), blockCount)
            elseif t == EV_RESYNC_DONE then
                log("resync done: Minecraft has %d block(s), %d shown", a, blockCount)
            elseif t == EV_NEIGHBOR_HIT then
                neighbourHit(a, b, cc)
            elseif t == EV_SCAN_CELL then
                if d == 1 then
                    local u = voxAhead.urgent
                    if #u - voxAhead.uhead < 8000 then u[#u + 1] = { a, b, cc } end
                else
                    voxRequest(a, b, cc)
                end
            elseif t == 10 then      -- kEvHeldFirst: Minecraft's first-person held-item pose, from the camera
                if not ia.fpPose then log("held item: first-person hand pose from Minecraft arriving (%.2f, %.2f, %.2f)", a / 1000, b / 1000, cc / 1000) end
                ia.fpPose = { a / 1000, b / 1000, cc / 1000, at = os.clock() }
            elseif t == 11 then      -- kEvHeldThird: in Steve's hand, from the feet + body yaw + arm swing
                if not ia.tpPose then log("held item: third-person hand pose from Minecraft arriving (%.2f, %.2f, %.2f) yaw %.0f", a / 1000, b / 1000, cc / 1000, (d & 0xFFFF) / 10) end
                local low = d & 0xFFFF
                ia.tpPose = { a / 1000, b / 1000, cc / 1000, yaw = low / 10, swing = ((d - low) // 65536) / 1000, at = os.clock() }
            elseif t == 12 then      -- kEvHeldRot: first-person item rotation minus the camera's (Unreal degrees)
                ia.fpRot = { Pitch = a / 100, Yaw = b / 100, Roll = cc / 100, at = os.clock() }
            elseif t == ia.EV_HN_SELECT then
                if a ~= ia.want then log("Minecraft hand: %s", a == 0 and "a Minecraft item (Hello Neighbor's item put away)" or ("Hello Neighbor item " .. a)) end
                ia.want = a
            elseif t == 3 then       -- kEvPlayerDied: Minecraft's player died (cancelled there): Hello Neighbor catches us
                local ok, e = pcall(st.catchPlayer)
                if not ok then log("Hello Neighbor's own respawn failed: %s", tostring(e)) end
            elseif t == EV_SURFACE_HIT or t == EV_EXPLOSION then
                local ok, e = pcall(t == EV_SURFACE_HIT and surfaceHit or explosionHit, a, b, cc, d)
                if not ok then log("impact failed: %s", tostring(e)) end
            end
        elseif c == "M" then
            -- v[1] = "M" (kept as a placeholder: tonumber("M") is nil and must not shift the fields)
            local v, n = {}, 0
            for w in line:gmatch("%S+") do n = n + 1; v[n] = tonumber(w) or 0 end
            if n < 10 then error("short M line: " .. line) end
            local prevTick = mc and mc.tick or 0
            mc = { flags = math.tointeger(v[2]) or 0, x = v[3], y = v[4], z = v[5], yaw = v[6], pitch = v[7], ack = v[8], slot = v[9], tick = v[10],
                   eye = v[11], camDX = v[12], camDY = v[13], camDZ = v[14], camYaw = v[15], camPitch = v[16], camFov = v[17] }
            if mc.tick < prevTick and mc.tick < 2000 then   -- a real restart starts again near 0
                requestResync("Minecraft restarted")   -- its world is new: drop stale cubes
                gridDirty = true                       -- ...and it has none of Hello Neighbor's geometry yet: rescan first
            end
            return
        end
    end
end

-- Level change guard. Loading a level destroys every actor of the old one (cubes, the third-person camera, the
-- player controller and pawn). Touching one of those afterwards is a native crash that pcall cannot catch, so as
-- soon as the controller our references belong to is no longer a live object, drop them all WITHOUT touching
-- them. Only addresses are compared (GetAddress does not read the object); FindAllOf lists live objects only.
-- Belt and braces: also forget on the engine's own signals (map load, a new player controller), because a new
-- controller can land at the old one's address.
st.worldPcAddr = nil
local function forgetWorld(why)
    local n = blockCount
    blocks, blockCount = {}, 0
    camActor, viewOnCam = nil, false
    pcCache = nil
    ignoreList = nil
    followOn, lastSet, waitAck = false, nil, nil
    camWrittenFor = nil
    st.neighbourSizeKey, st.neighbourSent = nil, nil   -- resend his size for the new level
    st.neighbourRef, st.fallFn = nil, nil
    st.itemClass = nil
    st.doors, st.doorsAt = {}, -100   -- the doors died with the level
    interact.capsuleSaved = nil       -- ...and the pawn whose collision responses were saved
    voxAhead.blockCache = {}          -- component addresses get reused by the next level
    st.worldPcAddr = nil
    ia.forget()   -- Hello Neighbor's items died with the level; Minecraft drops their copies
    handoff.on, handoff.state = false, -1
    gridDirty = true
    queueCmd(CMD_RESYNC, 0, 0, 0, 0)   -- the new level gets Minecraft's blocks again
    log("level change (%s): forgot %d cube actor(s), the camera actor and the player; they died with the old level", why, n)
end

local function checkWorld()
    local live, withPawn, first = {}, nil, nil
    for _, c in ipairs(FindAllOf("PlayerController") or {}) do
        local addr = c:GetAddress()
        live[addr] = true
        first = first or addr
        if not withPawn then
            local ok, r = pcall(function() return c:IsValid() and c.Pawn and c.Pawn:IsValid() end)
            if ok and r then withPawn = addr end
        end
    end
    if st.worldPcAddr and not live[st.worldPcAddr] then forgetWorld("player controller gone") end
    if not st.worldPcAddr then st.worldPcAddr = withPawn or first end
end

-- Frame cost of this mod on Hello Neighbor's game thread, per section, logged every 2 s: average and worst frame
-- (os.clock has ~1 ms steps on Windows: averages are good, single values coarse), and how many frames took > 8 ms.
st.VERBOSE = false   -- true: status lines every 2 s (see the end of tick)
st.prof = { frames = 0, spikes = 0, sum = {}, max = {} }
function st.profAdd(name, dt)
    local p = st.prof
    p.sum[name] = (p.sum[name] or 0) + dt
    if dt > (p.max[name] or 0) then p.max[name] = dt end
end
function st.profReport()
    local p = st.prof
    if p.frames == 0 then return end
    local parts = {}
    for _, n in ipairs({ "total", "world", "scan", "game", "trace", "exchange", "follow" }) do
        parts[#parts + 1] = string.format("%s %.2f/%.0f", n, 1000 * (p.sum[n] or 0) / p.frames, 1000 * (p.max[n] or 0))
    end
    log("frame cost ms (avg/max over %d frames): %s; frames over 8 ms: %d; scan %.0f queries/frame, %.1f us/query; Lua memory %.0f MB",
        p.frames, table.concat(parts, ", "), p.spikes, (p.queries or 0) / p.frames, p.queries and p.queries > 0 and 1e6 * (p.sum.scan or 0) / p.queries or 0, collectgarbage("count") / 1024)
    st.prof = { frames = 0, spikes = 0, sum = {}, max = {}, queries = 0 }
end

local function tick()
    local now = os.clock()
    local tStart = now
    local memStart = collectgarbage("count")
    voxAhead.maxQ, voxAhead.ignS = 0, 0
    if not wf then
        if now < nextConnectAt then return end
        nextConnectAt = now + 1.0
        if not connect() then return end
    end

    checkWorld()
    local inGame, world, x, y, z, yaw, pitch, ground = sample()
    local tMark = os.clock()
    st.profAdd("world", tMark - tStart)

    -- Step 3b: scan Hello Neighbor's geometry for Minecraft, then let Minecraft drive.
    if inGame == 1 and driveWanted then
        if gridDirty then gridDirty = false voxReset("new map or grid offset") end
        local cx, cy, cz
        if mc and (mc.flags & MC_DRIVING) ~= 0 then
            cx, cy, cz = math.floor(mc.x), math.floor(mc.y), math.floor(mc.z)
        else
            cx, cy, cz = math.floor(x / UNITS_PER_BLOCK), math.floor(z / UNITS_PER_BLOCK), math.floor(y / UNITS_PER_BLOCK)
        end
        local okD, eD = pcall(st.doorTick)
        if not okD and not st.doorErrLogged then st.doorErrLogged = true log("door watch failed: %s", tostring(eD)) end
        local okA, eA = pcall(st.scanAhead)
        if not okA and not st.aheadErrLogged then st.aheadErrLogged = true log("scan ahead failed: %s", tostring(eA)) end
        local q0 = voxQueries
        local ok, e = pcall(voxAhead.tick, cx, cy, cz)
        if not ok and not voxErrLogged then voxErrLogged = true log("scanner failed: %s", tostring(e)) end
        st.prof.queries = (st.prof.queries or 0) + voxQueries - q0
    end
    do local t2 = os.clock() st.profAdd("scan", t2 - tMark) tMark = t2 end
    -- The neighbour as a Minecraft target (position/size out; hits come back in readReply).
    if inGame == 1 then
        local ok, e = pcall(neighbourTick)
        if not ok and not st.neighbourErrLogged then st.neighbourErrLogged = true log("neighbour tracking failed: %s", tostring(e)) end
        ok, e = pcall(ia.focusTick)
        if not ok and not ia.focusErrLogged then ia.focusErrLogged = true log("focus tracking failed: %s", tostring(e)) end
        ok, e = pcall(ia.keysTick)
        if not ok and not ia.keysErrLogged then ia.keysErrLogged = true log("reading Hello Neighbor's keys failed: %s", tostring(e)) end
        ok, e = pcall(ia.invTick)
        if not ok and not ia.invErrLogged then ia.invErrLogged = true log("inventory sync failed: %s", tostring(e)) end
    end
    pcall(handoff.tick, inGame)
    local wantFlag = inGame == 1 and driveWanted and voxPassDone and not gridDirty and not handoff.on
    if wantFlag ~= driveFlag then
        driveFlag = wantFlag
        if driveFlag then teleportSeq = teleportSeq + 1 end   -- Minecraft starts exactly at our feet
        log("Minecraft movement %s", driveFlag and "requested" or "off")
    end

    -- Hello Neighbor's own pause menu: tell Minecraft (kHostMenuOpen), so hn_gfx gives it every key and click.
    -- A Hello Neighbor cutscene counts as its menu too: the player cannot move anyway, and Space (its skip key)
    -- must reach it instead of making Minecraft jump.
    do local t2 = os.clock() st.profAdd("game", t2 - tMark) tMark = t2 end
    local paused, cutscene = false, false
    if inGame == 1 then
        pcall(function()
            local gs = StaticFindObject("/Script/Engine.Default__GameplayStatics")
            paused = gs:IsGamePaused(playerController()) == true
        end)
        pcall(function() cutscene = playerController().Pawn:IsPlayCutScene() == true end)
        if cutscene ~= ia.lastCutscene then
            ia.lastCutscene = cutscene
            log("Hello Neighbor cutscene %s", cutscene and "playing: all input goes to it (Space skips)" or "over")
        end
        paused = paused or cutscene
    end
    if paused ~= lastPaused then
        lastPaused = paused
        log("Hello Neighbor menu %s", paused and "open (game paused): all input goes to it" or "closed")
    end

    local line
    if inGame then
        line = string.format("S %d %d %d %.4f %.4f %.4f %.3f %.3f %d %.3f\n",
            inGame | (driveFlag and HOST_MC_DRIVES or 0) | (paused and K.HOST_MENU_OPEN or 0), teleportSeq, world, x, y, z, yaw, pitch, ground, now - t0)
    else -- no pawn (loading): keep the heartbeat alive, state not in game
        line = string.format("S 0 %d 0 0 0 0 0 0 1 %.3f\n", teleportSeq, now - t0)
    end
    pcall(checkWorldStatus)
    do
        local ok, e = pcall(publishCamera, inGame or 0)
        if not ok and not camFailed then camFailed = true log("camera publish failed: %s", tostring(e)) end
    end
    if inGame == 1 and now - lastTraceAt >= 0.066 then   -- ~15 traces/s is plenty for a crosshair
        lastTraceAt = now
        local ok, s = pcall(traceSurface)
        if ok and s then table.insert(pendingCmds, s)
        elseif not ok and not traceFailLogged then traceFailLogged = true log("camera trace failed: %s", tostring(s)) end
    end
    do local t2 = os.clock() st.profAdd("trace", t2 - tMark) tMark = t2 end
    if #pendingCmds > 0 then
        line = table.concat(pendingCmds) .. line
        pendingCmds = {}
    end
    wf:write(line)
    readReply()
    do local t2 = os.clock() st.profAdd("exchange", t2 - tMark) tMark = t2 end
    do
        local ok, e = pcall(follow)
        if not ok and not followErrLogged then followErrLogged = true log("follow failed: %s", tostring(e)) end
        ok, e = pcall(ia.poseHeld)
        if not ok and not ia.poseErrLogged then ia.poseErrLogged = true log("placing the held item failed: %s", tostring(e)) end
    end
    do
        local t2 = os.clock()
        st.profAdd("follow", t2 - tMark)
        st.profAdd("total", t2 - tStart)
        st.prof.frames = st.prof.frames + 1
        if t2 - tStart > 0.008 then st.prof.spikes = st.prof.spikes + 1 end
        -- A really slow frame: what was it? (Lua memory falling a lot inside it = the garbage collector ran.)
        if t2 - tStart > 0.030 then
            log("slow frame %.0f ms: longest single query %.0f ms, ignore-list refresh %.0f ms, Lua memory %.0f -> %.0f KB, %d cells in the current generation",
                1000 * (t2 - tStart), 1000 * (voxAhead.maxQ or 0), 1000 * (voxAhead.ignS or 0), memStart, collectgarbage("count"), voxAhead.cells or 0)
        end
    end

    -- Status lines every 2 s (positions, trace and scan counters, frame costs): only with st.VERBOSE, or the log
    -- grows by ~2 MB an hour. Problems (slow frames, refusals, failures) are always logged where they happen.
    if st.VERBOSE and mc and now - lastLog > 2.0 and inGame == 1 then
        lastLog = now
        log("host (%.0f, %.0f, %.0f) yaw %.0f -> MC (%.2f, %.2f, %.2f) ack=%d tick=%d events=%d blocks=%d look=%s",
            x, y, z, yaw, mc.x, mc.y, mc.z, mc.ack, mc.tick, eventsSeen, blockCount,
            surface and string.format("(%.2f, %.2f, %.2f) face %d", surface[1], surface[2], surface[3], surface[4]) or "nothing")
        st.profReport()
        if traceN > 0 then
            log("trace cost: %.2f ms avg over %d traces, %d probes", traceMs / traceN, traceN, probeCalls)
            traceMs, traceN, probeCalls = 0.0, 0, 0
        end
        if driveWanted then
            log("drive: flag=%s mcDriving=%s follows=%d refused=%d scanQueries=%d cellsSent=%d arrowPathCells=%d/%d queued=%d ahead=%d (%d left) projectile=%d (%d left) eye=%s",
                tostring(driveFlag), tostring(mc and (mc.flags & MC_DRIVING) ~= 0), follows, followFails, voxQueries, voxSent, voxPathScanned, voxPathAsked,
                #voxPriority, voxAhead.scanned, #voxAhead.list - voxAhead.head + 1, voxAhead.uscanned, #voxAhead.urgent - voxAhead.uhead + 1,
                tostring(mc and mc.eye))
        end
    end
end

-- Every game frame while Minecraft drives (the pawn must follow smoothly), every TICK_MS otherwise.
-- At most one tick is ever queued on the game thread.
local tickQueued, lastTickAt = false, 0
LoopAsync(5, function()
    if tickQueued then return false end
    local now = os.clock()
    if not followOn and now - lastTickAt < TICK_MS / 1000 then return false end
    tickQueued = true
    ExecuteInGameThread(function()
        lastTickAt = os.clock()
        local ok, err = pcall(tick)
        tickQueued = false
        if not ok then
            log("link error, will retry: %s", tostring(err))
            disconnect()
            nextConnectAt = os.clock() + 1.0
        end
    end)
    return false
end)

-- ======================================================================================
-- Keys (handled on the game thread; commands go out with the next tick)
-- ======================================================================================
local function onKey(fn, name)
    return function()
        ExecuteInGameThread(function()
            local ok, e = pcall(fn)
            if not ok then log("%s failed: %s", name, tostring(e)) end
        end)
    end
end

RegisterKeyBind(Key.F6, onKey(function() requestResync("F6") end, "resync"))

-- F9: what makes the cells in front of the player solid for Minecraft? (A door that opens but still blocks: which
-- object is in the doorway.) Logs, for the 2 cells ahead at feet and head height and the cells beside them: the
-- shape Minecraft has, and every Hello Neighbor component found there (actor, component, object type).
function st.probeFront()
    if not mc or not mc.x then log("F9: no Minecraft position yet") return end
    local pc = playerController()
    if not exists(pc) or not exists(pc.Pawn) or not exists(KSL) then log("F9: no player / scanner yet") return end
    local yaw = math.rad(mc.yaw or 0)
    local fx, fz = -math.sin(yaw), math.cos(yaw)               -- Minecraft forward
    local seen = {}
    log("F9: player at (%.2f, %.2f, %.2f) looking %s", mc.x, mc.y, mc.z, math.abs(fx) > math.abs(fz) and (fx > 0 and "+x" or "-x") or (fz > 0 and "+z" or "-z"))
    for d = 1, 2 do for side = -1, 1 do for h = 0, 1 do
        local x = math.floor(mc.x + fx * d + (fz ~= 0 and side * fz or 0))
        local z = math.floor(mc.z + fz * d - (fx ~= 0 and side * fx or 0))
        local y = math.floor(mc.y) + h
        local k = voxAhead.key(x, y, z)
        if not seen[k] then
            seen[k] = true
            local mask = voxCells.S[k] or 0
            local B = UNITS_PER_BLOCK
            local out = {}
            local okQ = pcall(function()
                KSL:BoxOverlapComponents(pc.Pawn, { X = (x + 0.5) * B, Y = (z + 0.5) * B, Z = (y + 0.5) * B - yOff },
                    { X = B / 2 - 1, Y = B / 2 - 1, Z = B / 2 - 1 }, { 0, 1, 2, 3, 4, 5, 6, 7 }, nil, ignoredActors(pc.Pawn), out)
            end)
            local names = {}
            if okQ then
                for _, c in ipairs(arrayOut(nil, out)) do
                    pcall(function()
                        local owner = c:GetOwner()
                        local okB, b = pcall(voxAhead.blocks, c)
                        local verdict = not okB and "solid?" or b == true and "solid" or b == "hidden" and "invisible (floor only)" or "passes"
                        names[#names + 1] = string.format("%s.%s[type %s, %s]", owner and owner:GetFName():ToString() or "?",
                            c:GetFName():ToString(), tostring(c:GetCollisionObjectType()), verdict)
                    end)
                    if #names >= 6 then break end
                end
            end
            log("F9:   cell (%d, %d, %d) %s, Minecraft shape %s: %s", x, y, z, d == 1 and "1 ahead" or "2 ahead",
                mask == 0 and "empty" or string.format("0x%x", mask), #names > 0 and table.concat(names, ", ") or "nothing there")
        end
    end end end
end
RegisterKeyBind(Key.F9, onKey(function() st.probeFront() end, "probe"))

-- Level changes (see checkWorld): forget the old level's actors the moment the engine starts a new one.
-- The callbacks only clear Lua tables; they never touch an object.
do
    local okMap = pcall(function() RegisterLoadMapPreHook(function() forgetWorld("map load") end) end)
    local okPc = pcall(function()
        NotifyOnNewObject("/Script/Engine.PlayerController", function() if st.worldPcAddr then forgetWorld("new player controller") end end)
    end)
    -- A secret area is unloaded without a map load (level streaming): its doors and the like die while the door
    -- watch and the neighbour cache still hold them for up to a second, and reading a dead actor crashes the game
    -- (it did, leaving a secret level). Every actor's EndPlay passes here first, while it is still alive: drop it.
    local okEnd = pcall(function()
        RegisterHook("/Script/Engine.Actor:ReceiveEndPlay", function(self)
            local ok, addr = pcall(function() return self:get():GetAddress() end)
            if not ok or not addr then return end
            if st.endWatch then st.endWatch[addr] = true end   -- an impact in progress skips it from now on
            if st.doors[addr] then st.doors[addr] = nil end
            if st.neighbourRef then
                local okN, n = pcall(function() return st.neighbourRef:GetAddress() end)
                if not okN or n == addr then st.neighbourRef = nil end
            end
        end)
    end)
    st.endHookOn = okEnd
    log("level change hooks: map load %s, new player controller %s, actor end %s", okMap and "on" or "unavailable",
        okPc and "on" or "unavailable", okEnd and "on" or "unavailable")
end

-- F4: Minecraft movement on/off (off = Hello Neighbor moves the player and Minecraft follows, as before).
RegisterKeyBind(Key.F4, onKey(function()
    driveWanted = not driveWanted
    log("F4: Minecraft movement %s", driveWanted and "ON (once the area is scanned)" or "OFF")
end, "drive toggle"))

-- (F9 used to toggle hn_gfx's step-4 test cube as well, on top of the probe above. Gone; the cube stays off.)
writeFile(CUBE_FILE, "0\n")

log("loaded. F4 Minecraft movement on/off, F6 resync blocks, F9 what blocks the cells ahead")
