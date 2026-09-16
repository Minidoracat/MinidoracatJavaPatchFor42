#!/usr/bin/env lua
-- Behaviour harness for the VFE (Vanilla Foods Expanded) server aging manager.
--
--   lua scripts/test_vfe_aging_behavior.lua <path/to/vfx_agingmanager.lua>
--
-- Drives the real module through Lua stand-ins for the PZ engine objects it
-- touches (IsoGridSquare / IsoWorldInventoryObject / ItemContainer /
-- InventoryItem / Events). Every assertion is about an observable effect:
-- which items end up where, which sync calls fire, which mod data markers are
-- written, and how much scan backlog the queue is allowed to hold.
--
-- Cases tagged PATCHED require the temporary server-side patch (bounded queue +
-- load filter); they are expected to fail against the unmodified Workshop file.
-- All other cases encode upstream behaviour that the patch must preserve, and
-- pass on both files.
--
-- Scan accounting: square:getObjects() is NOT a scan counter -- the load filter
-- reads it too (and so does reuseGridSquare). Real scan work is observed only
-- where scanGridSquare/scanInventory reach: object:getContainerByIndex
-- (W.containerOpens / square.opens), container:getItems (container.reads) and
-- worldItem:getItem (W.worldItemReads).
--
-- Limits (read the report at the end too): this is standard Lua 5.4, not
-- Kahlua2. It does not exercise real Java items, save/load, threading, or the
-- client, so client-side visuals and multiplayer sync remain in-game
-- acceptance items -- the harness only proves the server-side calls are made.

local source = assert(arg[1], 'usage: lua scripts/test_vfe_aging_behavior.lua <vfx_agingmanager.lua>')
local CAP = 512 -- contract: queue is judged full at 512 queued squares

----------------------------------------------------------------------
-- engine stand-ins
----------------------------------------------------------------------

local W -- per-case world + recordings

local function jlist(arr) -- java List view over a lua array
    return {
        size = function() return #arr end,
        get = function(_, i) return arr[i + 1] end,
        contains = function(_, v)
            for _, x in ipairs(arr) do if x == v then return true end end
            return false
        end,
    }
end

local Item = {}
Item.__index = Item

local nextItemID = 0
local function newItem(fullType, opts)
    opts = opts or {}
    nextItemID = nextItemID + 1
    return setmetatable({
        fullType = fullType,
        id = opts.id or (1000 + nextItemID),
        age = opts.age or 0,
        modData = opts.modData,
        inventory = opts.inventory, -- set => the item is an inventory container
        name = opts.name or fullType,
        customName = false,
        baseHunger = opts.baseHunger or -20,
        hunger = opts.hunger or -20,
        stats = {},
        nameSets = 0,
        ageUpdates = 0,
    }, Item)
end

function Item:getID() return self.id end
function Item:getFullType() return self.fullType end
function Item:getAge() return self.age end
function Item:setAge(v) self.age = v end
function Item:updateAge() self.ageUpdates = self.ageUpdates + 1 end
function Item:hasModData() return self.modData ~= nil end
function Item:getModData()
    self.modData = self.modData or {}
    return self.modData
end
function Item:getWorldItem() return self.worldItem end
function Item:setWorldItem(w) self.worldItem = w end
function Item:getContainer() return self.container end
function Item:IsInventoryContainer() return self.inventory ~= nil end
function Item:getInventory() return self.inventory end
function Item:getScriptItem()
    local item = self
    return { getHungerChange = function() return item.baseHunger end }
end
function Item:getHungerChange() return self.hunger end
function Item:setUnhappyChange(v) self.stats.unhappy = v end
function Item:setBoredomChange(v) self.stats.boredom = v end
function Item:setStressChange(v) self.stats.stress = v end
function Item:getUnhappyChange() return self.stats.unhappy or 0 end
function Item:getBoredomChange() return self.stats.boredom or 0 end
function Item:getStressChange() return self.stats.stress or 0 end
function Item:setName(n)
    self.name = n
    self.nameSets = self.nameSets + 1
end
function Item:getName() return self.name end
function Item:setCustomName(v) self.customName = v end
function Item:isCustomName() return self.customName end

local Container = {}
Container.__index = Container

local function newContainer(ctype)
    return setmetatable({ type = ctype, items = {}, adds = 0, removes = 0, reads = 0 }, Container)
end

function Container:getType() return self.type end
function Container:getItems()
    -- scanInventory / evictInventory read the item list; the load filter never does
    self.reads = self.reads + 1
    W.containerReads = W.containerReads + 1
    return jlist(self.items)
end
function Container:AddItem(item)
    if self.failAdd then return nil end
    self.adds = self.adds + 1
    self.items[#self.items + 1] = item
    item.container = self
    return item
end
function Container:Remove(item)
    if self.failRemove then return end
    for i, x in ipairs(self.items) do
        if x == item then
            table.remove(self.items, i)
            self.removes = self.removes + 1
            if item.container == self then item.container = nil end
            return
        end
    end
end
function Container:types()
    local out = {}
    for i, x in ipairs(self.items) do out[i] = x.fullType end
    return table.concat(out, ',')
end

local function newObject(containers, owner)
    return {
        -- the load filter is allowed to ask how many containers an object has ...
        getContainerCount = function() return #containers end,
        -- ... but only a real scan (or eviction) opens one, so this is the counter
        getContainerByIndex = function(_, i)
            local container = containers[i + 1]
            if container then
                W.containerOpens = W.containerOpens + 1
                if owner then owner.opens = owner.opens + 1 end
            end
            return container
        end,
    }
end

local nextLoadID = 0
local function newChunk()
    nextLoadID = nextLoadID + 1
    return { loadID = nextLoadID, getLoadID = function(c) return c.loadID end }
end

local Square = {}
Square.__index = Square

local nextSquareX = 0
local function newSquare()
    nextSquareX = nextSquareX + 1
    local sq = setmetatable({
        x = nextSquareX, y = 7, z = 0,
        worldObjects = {},
        opens = 0, -- object containers opened on this square == real scan work
        objectReads = 0,
        chunk = newChunk(),
    }, Square)
    -- A bare floor tile owns no ItemContainer in PZ: ground items are world
    -- objects, and item:getContainer() points at the square's floor container.
    -- sq.floor mirrors that relationship only -- it is deliberately NOT reachable
    -- through getObjects(), so a plain floor really has zero object containers.
    sq.floor = newContainer('floor')
    sq.objects = { newObject({}, sq) }
    W.cell.squares[sq.x .. ':' .. sq.y .. ':' .. sq.z] = sq
    return sq
end

function Square:getX() return self.x end
function Square:getY() return self.y end
function Square:getZ() return self.z end
function Square:getChunk() return self.chunk end
function Square:getWorldObjects() return jlist(self.worldObjects) end
function Square:getObjects()
    -- Read by the load filter AND by scanGridSquare/reuseGridSquare, so it proves
    -- nothing about scanning. Use square.opens / W.containerOpens for that.
    self.objectReads = self.objectReads + 1
    W.objectReads = W.objectReads + 1
    return jlist(self.objects)
end
function Square:AddWorldInventoryItem(item, x, y, z)
    if self.failWorldAdd then
        self.failWorldAdd = self.failWorldAdd - 1
        if self.failWorldAdd <= 0 then self.failWorldAdd = nil end
        return nil
    end
    local wi = { item = item, square = self, offX = x, offY = y, offZ = z, transmits = 0 }
    wi.getItem = function(w)
        W.worldItemReads = W.worldItemReads + 1
        return w.item
    end
    wi.getSquare = function(w) return w.square end
    wi.getOffX = function(w) return w.offX end
    wi.getOffY = function(w) return w.offY end
    wi.getOffZ = function(w) return w.offZ end
    wi.transmitCompleteItemToClients = function(w)
        w.transmits = w.transmits + 1
        W.worldTransmits = W.worldTransmits + 1
    end
    self.worldObjects[#self.worldObjects + 1] = wi
    item.worldItem = wi
    self.floor:AddItem(item) -- PZ keeps ground items in the floor container too
    return item
end
function Square:transmitRemoveItemFromSquare(worldItem)
    W.squareRemovals[#W.squareRemovals + 1] = worldItem
end
function Square:removeWorldObject(worldItem)
    for i, x in ipairs(self.worldObjects) do
        if x == worldItem then
            table.remove(self.worldObjects, i)
            return
        end
    end
end

local function addContainer(sq, ctype)
    local container = newContainer(ctype)
    sq.objects[#sq.objects + 1] = newObject({ container }, sq)
    return container
end

local function eligibleSquare(ctype) -- square the load filter must keep
    local sq = newSquare()
    return sq, addContainer(sq, ctype or 'crate')
end

local function forget(sq) -- the square's chunk streams out of the cell again
    W.cell.squares[sq.x .. ':' .. sq.y .. ':' .. sq.z] = nil
end

local function weakSet()
    return setmetatable({}, { __mode = 'v' })
end

local function liveCount(weak)
    collectgarbage('collect')
    collectgarbage('collect')
    local live = 0
    for _ in pairs(weak) do live = live + 1 end
    return live
end

-- globals the module expects
function isClient() return W.isClient == true end
function isServer() return W.isClient ~= true end
function getTimestampMs() return 0 end
GameTime = { getServerTime = function() return 0 end }
function getCell() return W.cell end
function getOnlinePlayers() return jlist({}) end
function getPlayer() return nil end
function sendClientCommand() end
function log() end
DebugType = { Lua = 'Lua' }

function instanceItem(fullType)
    W.instanced[#W.instanced + 1] = fullType
    if W.failInstance then return nil end
    return newItem(fullType)
end

function sendReplaceItemInContainer(container, old, new)
    W.replacePackets[#W.replacePackets + 1] = { container = container, old = old, new = new }
end

function sendItemStats(item)
    W.statPackets[#W.statPackets + 1] = item
end

local realPrint = print
function print(line)
    W.prints[#W.prints + 1] = tostring(line)
end

----------------------------------------------------------------------
-- harness plumbing
----------------------------------------------------------------------

local function resetWorld(opts)
    opts = opts or {}
    W = {
        isClient = opts.isClient,
        containerOpens = 0, -- real scan work: object:getContainerByIndex
        containerReads = 0, -- container:getItems
        worldItemReads = 0, -- worldItem:getItem
        objectReads = 0, -- square:getObjects (filter + scan + evict; not a counter)
        instanced = {},
        replacePackets = {},
        statPackets = {},
        squareRemovals = {},
        worldTransmits = 0,
        prints = {},
        cell = { squares = {} },
    }
    W.cell.getGridSquare = function(_, x, y, z) return W.cell.squares[x .. ':' .. y .. ':' .. z] end
    Events = setmetatable({}, {
        __index = function(t, key)
            local listeners = {}
            local event = {
                Add = function(fn) listeners[#listeners + 1] = fn end,
                Remove = function(fn)
                    for i, x in ipairs(listeners) do
                        if x == fn then table.remove(listeners, i); return end
                    end
                end,
                listeners = listeners,
            }
            rawset(t, key, event)
            return event
        end,
    })
    VFX = nil
    assert(loadfile(source))()
end

local function fire(name, ...)
    local copy = {}
    for _, fn in ipairs(Events[name].listeners) do copy[#copy + 1] = fn end
    for _, fn in ipairs(copy) do fn(...) end
end

local function drain() -- cancelled entries still consume ticks without producing scans
    local ticks = 0
    while #Events.OnTick.listeners > 0 do
        fire('OnTick')
        ticks = ticks + 1
        assert(ticks < 5000, 'scan queue never drained')
    end
    return ticks
end

local function errorLines()
    local out = {}
    for _, line in ipairs(W.prints) do
        if line:find('[VFX Aging Error]', 1, true) then out[#out + 1] = line end
    end
    return out
end

local function assertNoErrors()
    local errs = errorLines()
    assert(#errs == 0, 'unexpected error output: ' .. table.concat(errs, ' | '))
end

local function findPrint(needle)
    local hits = {}
    for _, line in ipairs(W.prints) do
        if line:find(needle, 1, true) then hits[#hits + 1] = line end
    end
    return hits
end

local function close(actual, expected, what)
    assert(actual and math.abs(actual - expected) < 1e-6,
        (what or 'value') .. ': expected ' .. tostring(expected) .. ' got ' .. tostring(actual))
end

local cases, failures = {}, 0
local function case(name, tag, fn)
    cases[#cases + 1] = { name = name, tag = tag, fn = fn }
end

----------------------------------------------------------------------
-- 1. the load filter: which squares are worth queueing at all
----------------------------------------------------------------------

case('plain floor squares are never queued, scanned, or retained', 'PATCHED', function()
    resetWorld()
    local weak = weakSet()
    local function streamPlainFloors(count)
        for i = 1, count do
            local sq = newSquare() -- bare floor: no world items, no object containers
            weak[i] = sq
            fire('LoadGridsquare', sq)
            forget(sq) -- chunk streams out again; only the module could still hold it
        end
    end
    streamPlainFloors(200)

    assert(#Events.OnTick.listeners == 0, 'a filtered-out square must not start a scan session')
    assert(W.containerOpens == 0 and W.containerReads == 0,
        'no container may be opened for a plain floor: opens=' .. W.containerOpens)
    assert(W.worldItemReads == 0, 'no world item may be read for a plain floor')
    assert(drain() == 0, 'there must be no queued work left to drain')

    local retained = liveCount(weak)
    assert(retained == 0, 'filtered squares must not stay referenced by the module, retained=' .. retained)
    assertNoErrors()
    return '200 plain floors: no scan session, no container opens, no retained square references'
end)

case('a ground item alone keeps its square eligible', 'BOTH', function()
    resetWorld()
    local sq = newSquare() -- no object containers at all, only the world item
    local src = newItem('VFX.SourdoughStarter', { age = 1.2, modData = { VFX_AgingReplaceTracked = true } })
    sq:AddWorldInventoryItem(src, 0.5, 0.25, 0)

    fire('LoadGridsquare', sq)
    drain()
    assert(#W.instanced == 1, 'the world-object branch of the filter must admit the square')
    assert(sq.worldObjects[1].item:getFullType() == 'VFX.SourdoughStarterUnfed', 'ground item was not aged')
    assert(sq.opens == 0 and W.containerOpens == 0, 'a floor square has no object container to open')

    -- junk on the ground is not an aging item, but the square is still worked
    local junk = newSquare()
    junk:AddWorldInventoryItem(newItem('Base.Apple'), 0, 0, 0)
    local before = W.worldItemReads
    fire('LoadGridsquare', junk)
    drain()
    assert(W.worldItemReads > before, 'every square holding ground items must still be scanned')
    assert(#W.instanced == 1, 'a non-aging ground item must not be replaced')
    assertNoErrors()
    return 'world items admit a containerless square; non-food ground items are scanned and left alone'
end)

case('empty and non-food containers are kept and scanned', 'BOTH', function()
    resetWorld()
    local empty, emptyCrate = eligibleSquare('crate')
    fire('LoadGridsquare', empty)
    drain()
    assert(empty.opens == 1, 'an empty container must still be opened by the scan, opens=' .. empty.opens)
    assert(emptyCrate.reads == 1, 'the scan must read the empty container once, reads=' .. emptyCrate.reads)

    local junk, shelf = eligibleSquare('shelves')
    shelf:AddItem(newItem('Base.Apple'))
    fire('LoadGridsquare', junk)
    drain()
    assert(junk.opens == 1 and shelf.reads == 1, 'non-food containers are scanned too')
    assert(#W.instanced == 0 and #W.replacePackets == 0, 'nothing to replace in a non-food container')
    assertNoErrors()
    return 'empty container opened and read; non-food container scanned without side effects'
end)

case('food nested inside a bag in a container is still found', 'BOTH', function()
    resetWorld()
    local sq, crate = eligibleSquare('crate')
    local bag = newContainer('bag')
    crate:AddItem(newItem('Base.Bag_ALICEpack', { inventory = bag }))
    local src = newItem('VFX.YeastStarterFermenting', { age = 6, modData = { VFX_AgingReplaceTracked = true } })
    bag:AddItem(src)

    fire('LoadGridsquare', sq)
    drain()
    assert(sq.opens == 1, 'the outer container is reached through the object')
    assert(bag.reads >= 1, 'the nested bag inventory must be walked, reads=' .. bag.reads)
    assert(#W.instanced == 1 and W.instanced[1] == 'VFX.YeastStarter', table.concat(W.instanced, ','))
    assert(#bag.items == 1 and bag.items[1]:getFullType() == 'VFX.YeastStarter',
        'nested container: ' .. bag:types())
    assert(#crate.items == 1 and crate.items[1]:getFullType() == 'Base.Bag_ALICEpack',
        'the bag itself must stay put: ' .. crate:types())
    assert(#W.replacePackets == 1 and W.replacePackets[1].container == bag,
        'the replace packet must name the nested container')
    assertNoErrors()
    return 'nested bag inventory scanned; replacement lands in the bag with one packet'
end)

case('a square recycled into plain floor drops its queued work and chunk', 'PATCHED', function()
    resetWorld()
    local sq = eligibleSquare('crate')
    local weakChunks = weakSet()
    weakChunks[1] = sq.chunk

    fire('LoadGridsquare', sq)
    assert(#Events.OnTick.listeners == 1, 'the eligible load must start a scan session')

    -- same square object, recycled by the streamer into a bare floor tile
    sq.objects = { newObject({}, sq) }
    sq.chunk = newChunk()
    fire('LoadGridsquare', sq)

    assert(#Events.OnTick.listeners == 0,
        'a queue left holding only cancelled entries must be closed out, not kept pending')
    assert(sq.opens == 0 and W.containerOpens == 0, 'nothing may be scanned for the recycled floor')
    local retained = liveCount(weakChunks)
    assert(retained == 0, 'the cancelled entry must release the old chunk, retained=' .. retained)
    assert(drain() == 0, 'no queued work may be left behind')
    assertNoErrors()
    return 'stale pending entry cancelled, chunk released, session finished when the filter rejects'
end)

case('eligible square flood stays bounded and loses no scan', 'PATCHED', function()
    resetWorld()
    local loads, peak = 0, 0
    for _ = 1, 40 do
        for _ = 1, 300 do -- 300 eligible loads produced per tick, 50 consumed
            fire('LoadGridsquare', (eligibleSquare('crate')))
            loads = loads + 1
            local backlog = loads - W.containerOpens
            peak = math.max(peak, backlog)
            assert(backlog <= CAP, 'unscanned backlog grew past the cap: ' .. backlog)
        end
        fire('OnTick')
    end
    drain()
    assert(W.containerOpens == loads,
        ('every eligible load must be scanned once: loads=%d scans=%d'):format(loads, W.containerOpens))
    assert(peak == CAP, 'backlog should saturate at the cap, not vanish: peak=' .. peak)
    assertNoErrors()
    return ('loads=%d scans=%d peakBacklog=%d'):format(loads, W.containerOpens, peak)
end)

----------------------------------------------------------------------
-- 2. fermenting food in a container
----------------------------------------------------------------------

case('container ferment is replaced once, synced once, and not re-replaced', 'BOTH', function()
    resetWorld()
    local sq = newSquare()
    local fridge = addContainer(sq, 'fridge')
    local src = newItem('VFX.YeastStarterFermenting', { age = 6, modData = { VFX_AgingReplaceTracked = true } })
    fridge:AddItem(src)

    fire('LoadGridsquare', sq)
    drain()

    assert(#W.instanced == 1 and W.instanced[1] == 'VFX.YeastStarter',
        'expected exactly one VFX.YeastStarter creation, got ' .. table.concat(W.instanced, ','))
    assert(#fridge.items == 1, 'container should hold exactly the replacement: ' .. fridge:types())
    local new = fridge.items[1]
    assert(new ~= src and new:getFullType() == 'VFX.YeastStarter')
    close(new:getAge(), 1, 'carried-over age') -- 6 - 5 remainder of the chain
    assert(src.container == nil, 'source must leave the container')
    assert(#W.replacePackets == 1, 'exactly one container replace packet')
    local pkt = W.replacePackets[1]
    assert(pkt.container == fridge and pkt.old == src and pkt.new == new, 'replace packet must name old+new item')
    assert(src.modData.VFX_AgingReplaceTracked == false, 'source marker must be cleared')
    assert(new.modData.VFX_AgingReplaceTracked == true, 'replacement must be marked tracked')

    -- the square is streamed again under a new chunk load: no second product
    sq.chunk = newChunk()
    fire('LoadGridsquare', sq)
    drain()
    assert(#W.instanced == 1, 'a repeated LoadGridsquare must not create another item')
    assert(#fridge.items == 1 and fridge.items[1] == new, 'container content must be untouched: ' .. fridge:types())
    assert(#W.replacePackets == 1, 'no extra sync packet for the repeated scan')

    -- hourly manager takes over for the carried remainder
    new:setAge(2)
    fire('EveryHours')
    assert(#W.instanced == 2 and W.instanced[2] == 'VFX.YeastStarterUnfed', 'hourly manager must advance the chain')
    assert(#fridge.items == 1 and fridge.items[1]:getFullType() == 'VFX.YeastStarterUnfed', fridge:types())
    assert(#W.replacePackets == 2 and W.replacePackets[2].old == new)
    assert(new.modData.VFX_AgingReplaceTracked == false)
    assertNoErrors()
    return 'container replace + client packet + chain hand-off to EveryHours'
end)

----------------------------------------------------------------------
-- 3. food on the ground
----------------------------------------------------------------------

case('ground ferment is replaced once and transmitted to clients', 'BOTH', function()
    resetWorld()
    local sq = newSquare()
    local src = newItem('VFX.SourdoughStarter', { age = 1.2, modData = { VFX_AgingReplaceTracked = true } })
    sq:AddWorldInventoryItem(src, 0.5, 0.25, 0)

    fire('LoadGridsquare', sq)
    drain()

    assert(#W.instanced == 1 and W.instanced[1] == 'VFX.SourdoughStarterUnfed', table.concat(W.instanced, ','))
    assert(#sq.worldObjects == 1, 'square must hold exactly one world item')
    local wi = sq.worldObjects[1]
    local new = wi.item
    assert(new ~= src and new:getFullType() == 'VFX.SourdoughStarterUnfed')
    close(new:getAge(), 0.2, 'carried-over age')
    assert(src.worldItem == nil, 'source world item reference must be cleared')
    assert(#W.squareRemovals == 1, 'old world item must be removed on clients too')
    assert(wi.transmits == 1 and W.worldTransmits == 1, 'replacement must be transmitted once')
    assert(#sq.floor.items == 1 and sq.floor.items[1] == new, 'floor container content: ' .. sq.floor:types())
    assert(src.modData.VFX_AgingReplaceTracked == false and new.modData.VFX_AgingReplaceTracked == true)

    sq.chunk = newChunk()
    fire('LoadGridsquare', sq)
    drain()
    assert(#W.instanced == 1, 'repeated ground scan must not create another item')
    assert(#sq.worldObjects == 1 and sq.worldObjects[1].item == new)
    assertNoErrors()
    return 'ground replace + transmitRemove/transmitComplete + floor bookkeeping'
end)

case('failed ground replacement restores the source without leaving a duplicate', 'BOTH', function()
    resetWorld()
    local sq = newSquare()
    local src = newItem('VFX.SourdoughStarter', { age = 1.2, modData = { VFX_AgingReplaceTracked = true } })
    sq:AddWorldInventoryItem(src, 0.1, 0.2, 0)
    sq.failWorldAdd = 1 -- the replacement add fails, the rollback add succeeds

    fire('LoadGridsquare', sq)
    drain()

    assert(#W.instanced == 1, 'one creation attempt')
    assert(#sq.worldObjects == 1, 'square must not keep both the source and the replacement')
    assert(sq.worldObjects[1].item == src, 'source must be back on the square')
    assert(src.worldItem == sq.worldObjects[1], 'restored source must own its world item')
    assert(#sq.floor.items == 1 and sq.floor.items[1] == src, 'floor container: ' .. sq.floor:types())
    assert(src.modData.VFX_AgingReplaceTracked == true, 'restored source must stay tracked')
    assert(#findPrint('Replacement was not added to source square') == 1, 'failure must be reported once')
    assert(#findPrint('Could not restore source item') == 0, 'rollback itself must not fail')

    -- retry from the hourly manager now succeeds and yields a single product
    fire('EveryHours')
    assert(#W.instanced == 2, 'retry creates the replacement once more')
    assert(#sq.worldObjects == 1, 'still exactly one world item after the retry')
    assert(sq.worldObjects[1].item:getFullType() == 'VFX.SourdoughStarterUnfed')
    assert(#sq.floor.items == 1, 'floor container: ' .. sq.floor:types())
    return 'rollback keeps one item on the ground; retry produces exactly one product'
end)

----------------------------------------------------------------------
-- 4. cheese stats + stage markers
----------------------------------------------------------------------

case('cheese stats and stage marker apply once per stage', 'BOTH', function()
    resetWorld()
    local sq = newSquare()
    local cupboard = addContainer(sq, 'counter')
    local cheese = newItem('VFX.WheelCheddarCheeseHomemade', {
        age = 30,
        baseHunger = -30,
        hunger = -30,
        modData = { VFX_AgingStats = { tracked = true, type = 'Cheddar', stageIndex = 0 } },
    })
    cupboard:AddItem(cheese)

    fire('LoadGridsquare', sq)
    drain()

    local cfg = VFX.StatItemTable.Cheddar
    local ratio = math.floor(30 + 0.5) / cfg.ripeAge
    assert(cheese:getName() == 'Cheddar Cheese - Homemade (Medium)', 'stage name: ' .. cheese:getName())
    assert(cheese:isCustomName() == true, 'staged cheese must be custom-named')
    assert(cheese.nameSets == 2, 'catch-up walks stage 1 then 2, got ' .. cheese.nameSets)
    local marker = cheese.modData.VFX_AgingStats
    assert(marker.tracked == true and marker.type == 'Cheddar' and marker.stageIndex == 2, 'stage marker not persisted')
    close(cheese:getUnhappyChange(), cfg.unhappyMax * ratio * 1.0 * 100, 'unhappy')
    close(cheese:getBoredomChange(), cfg.boredMax * ratio * 1.0 * 100, 'boredom')
    close(cheese:getStressChange(), cfg.stressMax * ratio * 1.0, 'stress')
    assert(#W.statPackets == 1 and W.statPackets[1] == cheese, 'stats must be synced once')
    assert(#W.instanced == 0, 'stat items are never item-replaced')

    -- duplicate stream event: no second stage application
    local unhappy = cheese:getUnhappyChange()
    sq.chunk = newChunk()
    fire('LoadGridsquare', sq)
    drain()
    assert(cheese.nameSets == 2, 'repeated scan must not re-apply the stage name')
    assert(cheese.modData.VFX_AgingStats.stageIndex == 2, 'stage index must not advance')
    close(cheese:getUnhappyChange(), unhappy, 'unhappy after repeat')
    assert(#W.statPackets == 2, 'upstream re-syncs stats on every stream catch-up')

    -- daily manager advances exactly one further stage, then stops
    cheese:setAge(90)
    fire('EveryDays')
    assert(cheese:getName() == 'Cheddar Cheese - Homemade (Sharp)', cheese:getName())
    assert(cheese.nameSets == 3 and cheese.modData.VFX_AgingStats.stageIndex == 3)
    close(cheese:getUnhappyChange(), cfg.unhappyMax * (90 / cfg.ripeAge) * 100, 'unhappy at 90 days')
    fire('EveryDays')
    assert(cheese.nameSets == 3, 'same age must not re-apply the stage')

    -- past stale age the item is untracked and left alone afterwards
    cheese:setAge(340)
    fire('EveryDays')
    assert(cheese.modData.VFX_AgingStats.tracked == false, 'stale cheese must be untracked')
    local names, packets = cheese.nameSets, #W.statPackets
    fire('EveryDays')
    assert(cheese.nameSets == names and #W.statPackets == packets, 'untracked cheese must be left alone')
    assertNoErrors()
    return 'stage marker + stat values + sendItemStats, idempotent per stage, untracked at stale age'
end)

----------------------------------------------------------------------
-- 5. cancellation / load identity
----------------------------------------------------------------------

case('stale queue entries are dropped instead of scanned', 'BOTH', function()
    resetWorld()
    local sq = eligibleSquare('crate') -- a container keeps every square past the load filter
    fire('LoadGridsquare', sq)
    fire('LoadGridsquare', sq)
    drain()
    assert(W.containerOpens == 1, 'identical load events must scan once, got ' .. W.containerOpens)

    -- chunk reloaded before the scan: only the current identity is scanned
    local reloaded = eligibleSquare('crate')
    local before = W.containerOpens
    fire('LoadGridsquare', reloaded)
    reloaded.chunk = newChunk()
    fire('LoadGridsquare', reloaded)
    drain()
    assert(W.containerOpens == before + 1, 'stale load identity must not be scanned again')

    -- square recycled before the scan
    local recycled = eligibleSquare('crate')
    before = W.containerOpens
    fire('LoadGridsquare', recycled)
    fire('ReuseGridsquare', recycled)
    drain()
    assert(W.containerOpens == before, 'reused square must not be scanned')

    -- square no longer in the cell
    local unloaded = eligibleSquare('crate')
    before = W.containerOpens
    fire('LoadGridsquare', unloaded)
    forget(unloaded)
    drain()
    assert(W.containerOpens == before, 'unloaded square must not be scanned')
    assertNoErrors()
    return 'dedupe + loadID change + ReuseGridsquare + unloaded square all handled without scanning stale squares'
end)

case('cancelled queue entries stop pinning their chunk', 'PATCHED', function()
    resetWorld()
    local sq = eligibleSquare('crate')
    local weakChunks = weakSet()
    for i = 1, 60 do -- each load cancels the previous entry; no ticks, so the backlog stays
        local chunk = newChunk()
        weakChunks[i] = chunk
        sq.chunk = chunk
        fire('LoadGridsquare', sq)
    end
    local retained = liveCount(weakChunks)
    assert(retained == 1, 'only the square\'s current chunk may survive, retained=' .. retained)
    drain()
    assert(W.containerOpens == 1, 'exactly the live entry is scanned, got ' .. W.containerOpens)
    assertNoErrors()
    return 'cancelled entries release their chunk reference (60 churned, 1 retained)'
end)

----------------------------------------------------------------------
-- 6. overflow path keeps slot semantics
----------------------------------------------------------------------

case('food square overflowing the queue is scanned inline with slot semantics intact', 'PATCHED', function()
    resetWorld()
    for _ = 1, CAP do -- fill the queue with eligible squares, no ticks
        fire('LoadGridsquare', (eligibleSquare('crate')))
    end

    local sq = newSquare()
    local crate = addContainer(sq, 'crate')
    local a = newItem('VFX.YeastStarter', { id = 42, age = 0.5, modData = { VFX_AgingReplaceTracked = true } })
    local b = newItem('VFX.YeastStarter', { id = 42, age = 3, modData = { VFX_AgingReplaceTracked = true } })
    local c = newItem('VFX.YeastStarter', { id = 43, age = 3, modData = { VFX_AgingReplaceTracked = true } })
    crate:AddItem(a)

    local before = sq.opens
    fire('LoadGridsquare', sq)
    assert(sq.opens == before + 1, 'a square arriving on a full queue must be scanned inline, not dropped')

    -- same slot id, different item object; plus a sibling with its own id
    crate:Remove(a)
    crate:AddItem(b)
    crate:AddItem(c)
    sq.chunk = newChunk()
    before = sq.opens
    fire('LoadGridsquare', sq)
    assert(sq.opens == before + 1, 'second overflow load must also be scanned inline')

    fire('EveryHours')
    assert(#W.instanced == 2, 'the re-keyed slot item and its sibling replace once each, got ' .. #W.instanced)
    assert(#crate.items == 2, 'crate: ' .. crate:types())
    for _, item in ipairs(crate.items) do
        assert(item:getFullType() == 'VFX.YeastStarterUnfed', 'crate: ' .. crate:types())
    end
    assert(b.modData.VFX_AgingReplaceTracked == false and c.modData.VFX_AgingReplaceTracked == false)
    assert(a.modData.VFX_AgingReplaceTracked == true, 'the detached item keeps its saved marker')
    assert(#W.replacePackets == 2, 'one replace packet per replaced item')
    assertNoErrors()

    drain()
    assert(W.containerOpens >= CAP, 'the pre-filled queue must still drain: ' .. W.containerOpens)
    return 'inline fallback scan under overflow; same-id re-keying and per-id tracking unchanged'
end)

----------------------------------------------------------------------
-- 7. patch marker / client branch
----------------------------------------------------------------------

case('temporary-patch banner is printed once on the server', 'PATCHED', function()
    resetWorld()
    local banner = findPrint('[MDC-VFE]')
    assert(#banner == 1, 'expected exactly one [MDC-VFE] banner line, got ' .. #banner)
    assert(banner[1]:find('temporary aging filter enabled', 1, true), 'banner must name the mode: ' .. banner[1])
    assert(banner[1]:find('cap=512', 1, true), 'banner must state the cap: ' .. banner[1])
    assert(banner[1]:find('upstream=3.2.16', 1, true), 'banner must pin the upstream version: ' .. banner[1])
    return banner[1]
end)

case('client load stays a no-op and prints nothing', 'BOTH', function()
    resetWorld({ isClient = true })
    assert(#Events.LoadGridsquare.listeners == 0, 'client must not subscribe to LoadGridsquare')
    assert(#Events.EveryHours.listeners == 0 and #Events.EveryDays.listeners == 0)
    assert(type(VFX.AgingOnCreate) == 'function' and type(VFX.CheeseAging) == 'function')
    VFX.AgingOnCreate(newItem('VFX.YeastStarterFermenting'))
    VFX.CheeseAging(newItem('VFX.WheelCheddarCheeseHomemade'))
    assert(#W.instanced == 0 and #W.replacePackets == 0 and #W.statPackets == 0)
    assert(#W.prints == 0, 'client must stay silent: ' .. table.concat(W.prints, ' | '))
    return 'client branch still stubbed out'
end)

----------------------------------------------------------------------
-- run
----------------------------------------------------------------------

realPrint(('VFE aging behaviour harness -- module: %s'):format(source))
realPrint('')
for _, c in ipairs(cases) do
    local ok, result = pcall(c.fn)
    if ok then
        realPrint(('PASS [%s] %s'):format(c.tag, c.name))
        if result then realPrint('       ' .. tostring(result)) end
    else
        failures = failures + 1
        realPrint(('FAIL [%s] %s'):format(c.tag, c.name))
        realPrint('       ' .. tostring(result))
        for _, line in ipairs(errorLines()) do realPrint('       module said: ' .. line) end
    end
end

realPrint('')
realPrint(('%d/%d cases passed'):format(#cases - failures, #cases))
if failures > 0 then
    realPrint('NOT GREEN: the claims below only hold for the cases marked PASS above.')
    realPrint('(PATCHED cases are expected to fail against the unmodified Workshop file.)')
end
realPrint([[
Verified here (server-side, standard Lua fixtures):
  - The load filter drops plain floor squares: 200 streamed bare floors start no
    scan session, open no container, read no world item, and leave no reference
    behind (weak table, so the check does not depend on internal names).
  - A square holding only ground items is still admitted and scanned even though
    it has no object container, and non-food ground items are scanned and left
    alone -- the filter is a floor pre-check, not a food test.
  - Empty containers and containers holding only non-food are kept and really
    scanned (getContainerByIndex + getItems), and food nested in a bag inside a
    container is still found and replaced in that nested container.
  - A square recycled from container tile to bare floor cancels its queued
    entry, releases the old chunk, and tears the scan session down instead of
    leaving pending work.
  - LoadGridsquare backlog for eligible squares stays at/below 512 unscanned
    squares under 6x overproduction, and every eligible load is still scanned
    exactly once (overflowing squares are scanned inline during the event).
  - Cancelled queue entries release their chunk reference.
  - Fermenting food in a container and on the ground is replaced exactly once,
    with the chain remainder carried onto the new item, the source removed, and
    sendReplaceItemInContainer / transmitRemoveItemFromSquare /
    transmitCompleteItemToClients called exactly once.
  - A failed world add rolls the source back with no duplicate item left on the
    square, and a later retry yields exactly one product.
  - Cheese stat values, stage names and VFX_AgingStats markers apply once per
    stage, survive duplicate stream events, and untrack at stale age.
  - Stale queue entries (dedupe, chunk reload, ReuseGridsquare, unloaded
    square) are dropped instead of scanned.
  - Same-id/different-item slot re-keying and per-id tracking behave the same
    on the inline overflow path.

NOT verified here -- still needs in-game acceptance:
  - Kahlua2 differences: this runs on standard Lua 5.4. Table/gc semantics,
    string.format edge cases and error handling are not identical, and real
    Java InventoryItem/IsoGridSquare behaviour (stacking, save/load, id reuse
    across sessions) is only approximated by the fixtures. In particular the
    weak-table/collectgarbage checks describe Lua 5.4 GC, not Kahlua2's.
  - Square shape is a fixture: a bare floor is modelled with zero object
    containers and ground items only as world objects. Real tiles carry walls,
    furniture and vehicle parts, so in-game the filter keeps far more squares
    than this harness implies -- it only proves nothing is dropped that holds
    items, not how many real squares get filtered.
  - No client was involved: the harness only proves the server issues the sync
    calls, not that clients render the replaced item, name or stats.
  - Real timing (OnTick budget via GameTime.getServerTime, scan cost per
    square), real memory numbers, and multiplayer/save persistence.]])

os.exit(failures == 0 and 0 or 1)
