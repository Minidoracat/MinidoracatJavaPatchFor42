-- Run unchanged PSR modules in isolated standard-Lua environments, not the live game.
-- lua scripts/test_psr_scan_behavior.lua <original.lua> <optimized.lua> [benchmark_rounds]
local original, optimized = assert(arg[1]), assert(arg[2])
local rounds = tonumber(arg[3]) or 7
local function upvalue(fn, wanted)
    for i = 1, 100 do
        local name, value = debug.getupvalue(fn, i)
        if not name then break end
        if name == wanted then return value end
    end
    error('missing upstream helper: ' .. wanted)
end
local function loadModule(path)
    local env = setmetatable({
        isClient = function() return false end,
        isServer = function() return true end,
        SandboxVars = {PSR = {consumptionMultiplier = 100}, GeneratorTileRange = 20, GeneratorVerticalPowerRange = 3},
        instanceof = function(obj, class) return obj.class == class end,
        require = function(name)
            assert(name == 'PSR/Utilities' or name == 'PSR/PSRStructures', name)
            return {WorldUtil = {}}
        end,
    }, {__index = _G})
    local module = assert(loadfile(path, 't', env))()
    return {module = module, scan = upvalue(module.getDrainBuilding, 'scanCellDevices'), env = env}
end
local function list(values)
    return {size = function() return #values end, get = function(_, i) return values[i + 1] end}
end
local Object = {}
Object.__index = Object
function Object:getSquare() return self.square end
function Object:hasModData() return self.md ~= nil end
function Object:getModData() self.md = self.md or {}; return self.md end
function Object:getContainerByType(kind) return self.containers[kind] end
function Object:getSprite() return self.sprite end
function Object:isActivated() return self.on end
function Object:Activated() return self.on end
function Object:isMicrowave() return self.microwave == true end
function Object:getDeviceData()
    if self.failDevice then error('unreadable device') end
    return self.device
end
function Object:getPipedFuelAmount()
    if self.failFuel then error('unreadable fuel') end
    if self.initializeEmptyFuel and self.fuel == nil then
        self.fuel = 0
        self:getModData().FUEL_AMOUNT = 0
        self.fuelInitializations = self.fuelInitializations + 1
    end
    return self.fuel or 0
end
local function object(class, sprite, opts)
    local o = setmetatable({class = class or 'IsoObject', on = true, containers = {}, fuelInitializations = 0}, Object)
    o.sprite = {getName = function() return sprite or 'floors_interior_tilesandwood_01_0' end}
    for k,v in pairs(opts or {}) do o[k] = v end
    return o
end
local function square(x, y, z, objects, powered, switch)
    local sq = {x=x, y=y, z=z, powered=powered ~= false, objects=list(objects)}
    sq.props = {get = function(_, name) if name == 'CustomName' and switch then return 'Switch' end end}
    function sq:getX() return self.x end
    function sq:getY() return self.y end
    function sq:getZ() return self.z end
    function sq:getObjects() return self.objects end
    function sq:haveElectricity() return self.powered end
    function sq:getProperties() return self.props end
    function sq:getBuilding() return self.building end
    for _,o in ipairs(objects) do o.square = sq end
    return sq
end
local function equal(a,b,path)
    path = path or 'result'
    assert(type(a) == type(b), path .. ': type differs')
    if type(a) == 'table' then
        for k,v in pairs(a) do equal(v,b[k],path .. '.' .. tostring(k)) end
        for k in pairs(b) do assert(a[k] ~= nil, path .. ': extra key ' .. tostring(k)) end
    elseif type(a) == 'number' then
        assert(math.abs(a-b) < 1e-10, path .. ': number differs')
    else assert(a==b,path .. ': value differs') end
end
local cases = 0
local function compare(name, run)
    local a,b = loadModule(original),loadModule(optimized)
    equal(run(a),run(b),name)
    cases = cases + 1
    print('PASS '..name)
end
do
    local function spriteReads(path)
        local m=loadModule(path)
        local obj=object()
        local reads=0
        obj.getSprite=function(self) reads=reads+1; return self.sprite end
        local entries={}
        local drain=m.scan(square(1,1,0,{obj}),true,0,entries,{})
        assert(drain==0 and #entries==0)
        return reads
    end
    assert(spriteReads(original)==2, 'baseline no longer reproduces duplicate classification')
    assert(spriteReads(optimized)==1, 'full scan must not classify a non-device twice')
    cases=cases+1
    print('PASS full non-device scan removes the duplicate sprite read')
end
compare('full device types, active/powered states, multiplier and list order',function(m)
    m.env.SandboxVars.PSR.consumptionMultiplier = 175
    local entries,seen,total = {},{},0
    local types = {
        {'IsoLightSwitch',nil,{},'light'},
        {'IsoTelevision',nil,{device={getIsTurnedOn=function() return true end}},'tv'},
        {'IsoRadio',nil,{device={getIsTurnedOn=function() return false end}},'radio'},
        {'IsoStove',nil,{on=false},'stove'},
        {'IsoStove',nil,{microwave=true},'microwave'},
        {'IsoClothingWasher',nil,{},'washer'},
        {'IsoClothingDryer',nil,{},'dryer'},
        {'IsoClothingDryer','waterpipes_01_24',{},'waterpump'},
        {'IsoObject',nil,{containers={fridge={},freezer={}}},'fridgeFreezer'},
        {'IsoObject',nil,{containers={fridge_off={}}},'fridge'},
        {'IsoObject',nil,{containers={freezer={}}},'freezer'},
        {'IsoObject',nil,{md={PFR_isColdUnit=true,PFR_on=true}},'coldunit'},
        {'IsoObject','pws_tileset_01_0',{},'weatherstation'},
        {'IsoObject','custom_lamp',{},'roomlight'},
        {'IsoObject',nil,{fuel=100},'fuelpump'},
        {'IsoObject',nil,{device={getIsTurnedOn=function() return true end}},'appliance'},
    }
    for i,t in ipairs(types) do
        local sq = square(i*3,0,0,{object(t[1],t[2],t[3])},i%4~=0)
        total=m.scan(sq,true,total,entries,seen)
        assert(entries[#entries].dtype==t[4], 'wrong type '..t[4])
    end
    local expected = 0
    for _,d in ipairs(entries) do if d.active and d.powered then expected=expected+d.rate end end
    assert(math.abs(total-expected)<1e-12 and #entries==#types)
    return {total=total,entries=entries,seen=seen}
end)
compare('light-only excludes switches, room lights and other devices',function(m)
    local entries,seen,total={},{},0
    total=m.scan(square(1,0,0,{object('IsoLightSwitch')}),false,total,entries,seen)
    total=m.scan(square(2,0,0,{object('IsoLightSwitch')},true,true),false,total,entries,seen)
    total=m.scan(square(3,0,0,{object(nil,'custom_lamp'),object('IsoTelevision'),object()}),false,total,entries,seen)
    assert(#entries==1 and entries[1].dtype=='light' and total==0.002)
    return {total=total,entries=entries,seen=seen}
end)
compare('overlapping scans and adjacent fuel pump halves stay deduplicated',function(m)
    local entries,seen,total={},{},0
    local sq=square(10,0,0,{object(nil,nil,{fuel=100})})
    total=m.scan(sq,true,total,entries,seen)
    total=m.scan(sq,true,total,entries,seen)
    total=m.scan(square(11,0,0,{object(nil,nil,{fuel=100})}),true,total,entries,seen)
    total=m.scan(square(12,0,0,{object(nil,nil,{fuel=100})}),true,total,entries,seen)
    total=m.scan(square(13,0,0,{object(nil,nil,{fuel=100})}),true,total,entries,seen)
    assert(#entries==2 and total==0.06)
    return {total=total,entries=entries,seen=seen}
end)
compare('classification stays live across calls; no object-result cache',function(m)
    local o=object();local sq=square(1,2,0,{o});local first={}
    assert(m.scan(sq,true,0,first,{})==0 and #first==0)
    o.md={PFR_isColdUnit=true,PFR_on=true};local nextEntries={}
    local on=m.scan(sq,true,0,nextEntries,{})
    o.md.PFR_on=false;local offEntries={};local off=m.scan(sq,true,0,offEntries,{})
    assert(on==0.15 and off==0 and #offEntries==1 and not offEntries[1].active)
    return {on=on,off=off,nextEntries=nextEntries,offEntries=offEntries}
end)
compare('empty fuel initialization, caught getter errors and empty squares',function(m)
    local fuel=object(nil,nil,{initializeEmptyFuel=true});local bad=object(nil,nil,{failFuel=true,failDevice=true})
    local entries,seen={},{}
    assert(m.scan(square(1,1,0,{fuel,bad,object()}),true,0,entries,seen)==0 and #entries==0)
    assert(fuel.fuelInitializations==1 and fuel.md.FUEL_AMOUNT==0)
    assert(m.scan(nil,true,3,{}, {})==3)
    assert(m.scan(square(2,2,0,{}),true,3,{}, {})==3)
    return {entries=entries,seen=seen,fuel=fuel.fuel,md=fuel.md,initializations=fuel.fuelInitializations}
end)
compare('classification read errors still propagate',function(m)
    local obj=object()
    obj.getSprite=function() error('sentinel classification failure') end
    local sq=square(1,1,0,{obj})
    local ok,err=pcall(m.scan,sq,true,0,{}, {})
    assert(not ok and tostring(err):find('sentinel classification failure',1,true))
    return {failed=not ok}
end)

local function buildingWorld(m, nonDevicePercent)
    local size=32
    local def={getX=function() return 0 end,getY=function() return 0 end,
        getW=function() return size end,getH=function() return size end,
        getMinLevel=function() return 0 end,getMaxLevel=function() return 0 end}
    local building={getDef=function() return def end}
    local world={};local devices=0
    for x=0,size-1 do
        world[x]={}
        for y=0,size-1 do
            local objs={}
            for n=1,3 do
                local index=(x*size+y)*3+n
                local isDevice=index%100>=nonDevicePercent
                objs[n]=object(isDevice and 'IsoLightSwitch' or 'IsoObject')
                if isDevice then devices=devices+1 end
            end
            local sq=square(x,y,0,objs);sq.building=building;world[x][y]=sq
        end
    end
    m.env.getSquare=function(x,y,z) return z==0 and world[x] and world[x][y] or nil end
    local pb=setmetatable({x=16,y=16,z=0,fuelToSolarRate=800,PSR_manualStructures={}}, {__index=m.module})
    return function()
        local drain,entries=pb:getDrainBuilding(world[16][16],building)
        assert(#entries==devices and math.abs(drain-devices*0.002*800)<1e-7,'building work mismatch')
        return drain,entries
    end
end
compare('whole getDrainBuilding preserves geometry, drain and list',function(m)
    local run=buildingWorld(m,95);local drain,entries=run();return {drain=drain,entries=entries}
end)
local function median(values)
    table.sort(values);return values[math.floor((#values+1)/2)]
end
for _,pct in ipairs({0,50,95,100}) do
    local a,b=loadModule(original),loadModule(optimized)
    local old,new=buildingWorld(a,pct),buildingWorld(b,pct)
    for _=1,3 do old();new() end
    local ot,nt={},{}
    local function timed(fn)
        collectgarbage('collect')
        local start=os.clock()
        for _=1,50 do fn() end
        return (os.clock()-start)*1000/50
    end
    for i=1,rounds do
        if i%2==1 then ot[i]=timed(old);nt[i]=timed(new) else nt[i]=timed(new);ot[i]=timed(old) end
    end
    local om,nm=median(ot),median(nt)
    print(('BENCH nonDevices=%d%% objects=3072 old=%.3fms new=%.3fms speedup=%.3fx rounds=%d'):format(pct,om,nm,om/nm,rounds))
end
print(('PASS %d behavior comparisons; benchmark uses standard Lua fixtures, not server FPS'):format(cases))
