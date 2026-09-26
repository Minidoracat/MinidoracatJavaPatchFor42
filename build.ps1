# build.ps1 — 建置 PZ 伺服器 loose-class patch
# 需求：JDK 25（42.19 unstable jar 已是 class file v69）、lib/asm-*.jar 9.8、
#       work/projectzomboid.jar（從目標伺服器拉取的權威 jar）
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$R = $PSScriptRoot

# 鎖定 JDK 25（scoop temurin25-jdk），不依賴 PATH 順序
$JDK = "$env:USERPROFILE\scoop\apps\temurin25-jdk\current"
if (-not (Test-Path "$JDK\bin\javac.exe")) { throw "找不到 JDK 25（$JDK）——scoop install java/temurin25-jdk" }
Set-Alias -Name javac -Value "$JDK\bin\javac.exe"
Set-Alias -Name java -Value "$JDK\bin\java.exe"

# $ErrorActionPreference 攔不住原生程式的非零退出碼——每步都要顯式驗 $LASTEXITCODE
function Assert-Ok([string]$step) {
    if ($LASTEXITCODE -ne 0) { throw "$step 失敗（exit=$LASTEXITCODE）——建置中止" }
}

if (-not (Test-Path "$R\work\projectzomboid.jar")) {
    throw "缺 work\projectzomboid.jar —— 先從伺服器拉取（scp <your-server>:/home/pzserver/serverfiles/java/projectzomboid.jar work\）"
}

Remove-Item -Recurse -Force "$R\work\out", "$R\work\gen", "$R\work\smoke-dumps", "$R\dist\java" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$R\work\out", "$R\work\gen", "$R\dist\java" | Out-Null

# 版本指紋（生成而非手寫——手寫會忘記更新，說謊的版本號比沒有更糟）
$gitSha = (& git rev-parse --short HEAD 2>$null)
if (-not $gitSha) { $gitSha = 'nogit' }
# dirty 判定只看「會進到產物的東西」：追蹤檔的任何修改，或 patcher\ 下的未追蹤新檔。
# （docs\ 之類的未追蹤草稿不影響建置產物，不該汙染版本指紋）
if ((& git status --porcelain --untracked-files=no 2>$null) -or
    (& git status --porcelain -- patcher 2>$null)) { $gitSha = "$gitSha+dirty" }
$builtAt = (Get-Date -Format 'yyyy-MM-ddTHH:mm')
$jarSha8 = (Get-FileHash -Algorithm SHA256 "$R\work\projectzomboid.jar").Hash.ToLower().Substring(0, 8)
$ASM_CP = "$R\lib\asm-9.8.jar;$R\lib\asm-tree-9.8.jar;$R\lib\asm-analysis-9.8.jar;$R\lib\asm-util-9.8.jar"

Write-Host "[1/10] 編譯 patcher..."
javac -encoding UTF-8 -cp $ASM_CP -d "$R\work\out" (Get-ChildItem "$R\patcher\src\*.java").FullName
Assert-Ok "javac patcher"

Write-Host "[2/10] 生成 PatchInfo（版本指紋）＋編譯 runtime helpers（對遊戲 jar）..."
java -cp "$R\work\out" PatchInfoGen "$R\work\gen" 'server' $gitSha $builtAt $jarSha8
Assert-Ok "PatchInfoGen"
javac -encoding UTF-8 -cp "$R\work\projectzomboid.jar" -d "$R\dist\java" `
    ((Get-ChildItem "$R\patcher\game" -Recurse -Filter *.java).FullName + (Get-ChildItem "$R\work\gen" -Recurse -Filter *.java).FullName)
Assert-Ok "javac runtime helpers"

Write-Host "[3/10] 編譯全部行為測試與 benchmark..."
javac -encoding UTF-8 -cp "$R\work\out;$ASM_CP;$R\dist\java;$R\work\projectzomboid.jar" -d "$R\work\out" `
    (Get-ChildItem "$R\patcher\tests" -Recurse -Filter *.java).FullName
Assert-Ok "javac tests"

Write-Host "[4/10] 執行 bytecode 手術..."
java -cp "$R\work\out;$ASM_CP" Patcher "$R\work\projectzomboid.jar" "$R\dist\java" "$R\dist\manifest.txt"
Assert-Ok "Patcher"

# Runtime helpers 置於 manifest 最前（origSha=- 表無 jar 原版）；部署時先 helper、再 patched caller。
$helperEntries = @(
    'zombie/mdc/LogFilter.class',
    'zombie/mdc/FastIdentityArrayRemoval.class',
    'zombie/mdc/FastIdentityArrayRemoval$State.class',
    'zombie/mdc/VehicleIntersectPrefilter.class',
    'zombie/mdc/GlassAttachmentGuard.class',
    'zombie/mdc/ZombieAuthThrottle.class',
    'zombie/characters/animals/behavior/AnimalSpottedPrefilter.class',
    'zombie/mdc/VehicleCouldSeeGate.class',
    'zombie/mdc/ChunkRequestPacker.class',
    'zombie/mdc/ContainerCycleGuard.class',
    'zombie/mdc/ContainerCycleGuard$State.class',
    'zombie/mdc/ContainerAddCycleProbe.class',
    'zombie/mdc/ChunkLoadGuard.class',
    'zombie/mdc/ForwardVectorGuard.class',
    'zombie/mdc/ChunkWriteGuard.class',
    'zombie/mdc/ChunkSaveIsolation.class',
    'zombie/mdc/NetTimedActionGuard.class',
    'zombie/mdc/AnimalSortGuard.class',
    'zombie/mdc/VehicleChunkIndexGuard.class',
    'zombie/mdc/AnimalRelevancyGate.class',
    'zombie/mdc/AnimalRequestGate.class',
    'zombie/mdc/AnimalRequestGate$Bucket.class',
    'zombie/mdc/MainLoopWatchdog.class',
    'zombie/mdc/HutchLoadGuard.class',
    'zombie/mdc/AnimalLosGate.class',
    'zombie/mdc/AnimalLosScan.class',
    'zombie/mdc/VehicleRemoveGuard.class',
    'zombie/mdc/ClothingSyncGuard.class',
    'zombie/mdc/ContainerIdProbe.class',
    'zombie/mdc/FaceObjectGuard.class',
    'zombie/mdc/EmitterParamGate.class',
    'zombie/entity/MdcUsingPlayerIndex.class',
    'zombie/core/MdcTimedActionProbe.class',
    'zombie/network/MdcAccountGate.class',
    'zombie/network/MdcAccountGate$Row.class',
    'zombie/mdc/IoPoolIsolation.class',
    'zombie/mdc/IoPoolIsolation$Local.class',
    'zombie/mdc/HutchSyncGate.class',
    'zombie/mdc/RecipientWindow.class',
    'zombie/mdc/GameEntityBroadcastGate.class',
    'zombie/entity/components/crafting/MdcCraftSyncGate.class',
    'zombie/mdc/PopManAddLock.class',
    'zombie/mdc/AnimalUpdateGuard.class',
    'zombie/mdc/BulkItemRegistration.class',
    'zombie/mdc/BulkItemRegistration$1.class',
    'zombie/mdc/FishingDataBroadcast.class',
    'zombie/mdc/AnimalAwayProbe.class',
    'zombie/mdc/BabyBreedGuard.class',
    'zombie/mdc/AnimalSpawnGuard.class',
    'zombie/characters/animals/MdcAnimalCellSave.class',
    'zombie/mdc/AnimalMetaSnapshot.class',
    'zombie/mdc/AnimalDeathLedger.class',
    'zombie/mdc/ProcessItemsGuard.class',
    'zombie/network/packets/sound/MdcWorldSoundProbe.class',
    'zombie/mdc/PatchInfo.class'
)
$manifestLines = foreach ($entry in $helperEntries) {
    $helperSha = (Get-FileHash -Algorithm SHA256 "$R\dist\java\$entry").Hash.ToLower()
    "$entry`t-`t$helperSha`t0hits"
}
$manifestLines += Get-Content "$R\dist\manifest.txt"
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText(
    "$R\dist\manifest.txt",
    [string]::Join("`n", $manifestLines) + "`n",
    $utf8NoBom)
Write-Host "final manifest -> $R\dist\manifest.txt（$($manifestLines.Count) classes）"

# manifest 完整性守門：dist\java 的每個 .class 都必須在 manifest 內（漏登記＝install.sh
# 不會複製、上線 NoClassDefFoundError——$helperEntries 是手寫清單，此檢查堵住人為遺漏）
$distClasses = Get-ChildItem "$R\dist\java" -Recurse -Filter *.class |
    ForEach-Object { $_.FullName.Substring("$R\dist\java\".Length).Replace('\', '/') }
$manifestEntries = $manifestLines | ForEach-Object { ($_ -split "`t")[0] }
$unlisted = $distClasses | Where-Object { $manifestEntries -notcontains $_ }
$missing = $manifestEntries | Where-Object { $distClasses -notcontains $_ }
if ($unlisted -or $missing) {
    if ($unlisted) { Write-Host "[中止] dist\java 有 class 未登記於 manifest（helperEntries 漏加？）：$($unlisted -join ', ')" }
    if ($missing) { Write-Host "[中止] manifest 登記的 class 不存在於 dist\java：$($missing -join ', ')" }
    throw "manifest 完整性守門失敗"
}

Write-Host "[5/10] 連結驗證（-Xverify:all）..."
java -Xverify:all -cp "$R\work\out" LoadCheck "$R\dist\java" "$R\work\projectzomboid.jar" "$R\dist\manifest.txt"
Assert-Ok "LoadCheck"

Write-Host "[6/10] JVMS 資料流驗證（CheckClassAdapter）..."
java -cp "$R\work\out;$ASM_CP" BytecodeVerify "$R\dist\java" "$R\work\projectzomboid.jar" "$R\dist\manifest.txt"
Assert-Ok "BytecodeVerify"

Write-Host "[7/10] 守衛語意驗證（smoke＋負對照＋結構斷言）..."
# dumpDir 導離真實 Zomboid 存檔目錄（W8 blocked-path 行為測試會觸發傾印；ZomboidFileSystem
# 在測試 JVM 能完整初始化，未導向會把垃圾寫進本機 Saves/）
java "-Dmdc.chunkWriteGuard.dumpDir=$R\work\smoke-dumps" -cp "$R\work\out;$ASM_CP" SmokeCheck "$R\dist\java" "$R\work\projectzomboid.jar"
Assert-Ok "SmokeCheck"

# 退役（2026-09-02）：[8/10] 登入／join 量測 wrapper 的行為與例外 precedence 驗證隨刀
# 移除（歸因任務已完成，REJOIN_TOTAL 常態 5–13ms）。詳見 docs/patches.md 2i。

Write-Host "[9/10] entity removal 等價性、碰撞與 fallback 驗證..."
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.FastIdentityArrayRemovalTest
Assert-Ok "FastIdentityArrayRemovalTest"

Write-Host "[9a/10] 容器環守衛（W5）行為驗證（含原版必爆負對照）＋kill switch..."
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.ContainerCycleGuardTest
Assert-Ok "ContainerCycleGuardTest"
# 事故當下的緊急降級路徑，第一次跑它的時機不該是事故現場
java "-Dmdc.cycleGuard.maxDepth=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.ContainerCycleGuardTest
Assert-Ok "ContainerCycleGuardTest（maxDepth=0 kill switch）"
# W5-2 門口 probe：預設 observe＋off kill switch 各獨立 JVM（probe 純函式仍跑，wrapper 模式自驗）。
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.ContainerAddCycleProbeTest
Assert-Ok "ContainerAddCycleProbeTest（observe 預設）"
java "-Dmdc.containerAddCycleProbe=off" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.ContainerAddCycleProbeTest
Assert-Ok "ContainerAddCycleProbeTest（off kill switch）"


Write-Host "[9b/10] chunk 供給併包（W4-1 v2）三模式行為驗證（獨立 JVM；模式是 static final）..."
# observe＝預設出貨（佇列一個位元組都不動、只算 would-merge）；enforce＝真併包（守恆／
# 上限 BATCH／重複放棄／順序／largeArea／預算閘／tick 重置）；off＝純 no-op。測試自驗
# argv 與實際 mode 相符，property 名稱打錯會炸在測試裡，不會把 observe 版跑三遍假綠。
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.ChunkRequestPackerTest observe
Assert-Ok "ChunkRequestPackerTest（observe，預設出貨模式）"
java "-Dmdc.chunkPacker=enforce" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.ChunkRequestPackerTest enforce
Assert-Ok "ChunkRequestPackerTest（enforce）"
java "-Dmdc.chunkPacker=enforce" "-Dmdc.chunkPacker.batch=60" "-Dmdc.chunkPacker.windowBudget=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.ChunkRequestPackerTest enforce-nobudget
Assert-Ok "ChunkRequestPackerTest（enforce 但 windowBudget=0＝不併包）"
java "-Dmdc.chunkPacker=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.ChunkRequestPackerTest off
Assert-Ok "ChunkRequestPackerTest（off kill switch）"

Write-Host "[9c/10] 地圖格載入捕手（W6）行為驗證（含替身必拋負對照）＋kill switch..."
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.ChunkLoadGuardTest
Assert-Ok "ChunkLoadGuardTest"
# 事故當下的緊急降級路徑，第一次跑它的時機不該是事故現場。
# 傳 disabled 讓測試自己斷言旋鈕真的生效——只看 exit code 的話，property 名稱打錯會變成
# 「把 enabled 版再跑一遍、照樣 exit 0」，降級路徑其實從未被測到。
java "-Dmdc.chunkLoadGuard.enabled=false" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.ChunkLoadGuardTest disabled
Assert-Ok "ChunkLoadGuardTest（enabled=false kill switch）"

Write-Host "[9d/10] 存檔管線隔離（W9）kill switch off 路徑行為驗證（獨立 JVM）..."
# enabled 路徑由 SmokeCheck 覆蓋；off 路徑必須真的執行過（緊急降級不該首跑於事故現場）。
# 測試自驗 property 到位——名稱打錯會炸在測試裡，不會默默跑 enabled 版假綠
java "-Dmdc.chunkSaveIsolation=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.ChunkSaveIsolationTest
Assert-Ok "ChunkSaveIsolationTest（chunkSaveIsolation=0 kill switch）"

# 退役（2026-09-02）：[9e/10] 食材重量記憶化三模式測試隨刀移除（observe 實測收益
# 0.06–0.18%，永不啟用 on 已定案）。詳見 docs/patches.md 2w。

Write-Host "[9f/10] LogFilter 抑噪名單行為鎖（equals 紀律／門檻不收名／反作弊放行）..."
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.LogFilterNoiseTest
Assert-Ok "LogFilterNoiseTest"

Write-Host "[9g/10] W10／W10-D 真封包與解析拒絕回歸（四組態，獨立 JVM）..."
# 出貨、Lua 保險絲關閉、Reject 補正關閉、參數守衛關閉；每組皆自驗旗標。
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.NetTimedActionGuardTest
Assert-Ok "NetTimedActionGuardTest（both，出貨組態）"
java "-Dmdc.netTimedActionGuard=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.NetTimedActionGuardTest guard-off
Assert-Ok "NetTimedActionGuardTest（netTimedActionGuard=0 kill switch）"
java "-Dmdc.netTimedActionState=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.NetTimedActionGuardTest state-off
Assert-Ok "NetTimedActionGuardTest（netTimedActionState=0 kill switch）"
java "-Dmdc.netTimedActionArgs=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.NetTimedActionGuardTest args-off
Assert-Ok "NetTimedActionGuardTest（netTimedActionArgs=0 kill switch，W10-D 直通）"

Write-Host "[9h/10] 動物排序活鎖捕手（W11）行為驗證＋kill switch（獨立 JVM）..."
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalSortGuardTest
Assert-Ok "AnimalSortGuardTest（on，出貨組態）"
java "-Dmdc.animalSortGuard=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalSortGuardTest off
Assert-Ok "AnimalSortGuardTest（animalSortGuard=0 kill switch）"

Write-Host "[9i/10] 車輛 DB chunk 索引守衛行為驗證＋kill switch（獨立 JVM）..."
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.VehicleChunkIndexGuardTest
Assert-Ok "VehicleChunkIndexGuardTest（on，出貨組態）"
java "-Dmdc.vehicleChunkIndexGuard=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.VehicleChunkIndexGuardTest off
Assert-Ok "VehicleChunkIndexGuardTest（off，vanilla 路徑）"

Write-Host "[9j/10] 動物同步範圍對齊（W13）三模式行為驗證（獨立 JVM；MODE 是 static final）..."
# 三個模式都必須真的跑過：enforce 是出貨組態、observe 只量測 `suppressed` 判定差集而
# 不改行為、off 是緊急降級。測試自驗 argv 與實際 MODE 相符，property 名稱打錯
# 會炸在測試裡，不會默默把 enforce 版跑三遍假綠。
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalRelevancyGateTest enforce
Assert-Ok "AnimalRelevancyGateTest（enforce，出貨組態）"
java "-Dmdc.animalRelevancy=2" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalRelevancyGateTest observe
Assert-Ok "AnimalRelevancyGateTest（observe，只量測不改行為）"
java "-Dmdc.animalRelevancy=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalRelevancyGateTest off
Assert-Ok "AnimalRelevancyGateTest（animalRelevancy=0 kill switch）"

Write-Host "[9k/10] 動物 requested 冷卻＋範圍閘（W14）行為驗證（獨立 JVM；模式是 static final）..."
# 兩把獨立 kill switch 的組合都必須真的跑過：both-enforce 是出貨組態；observe 只計數
# 不過濾；off 純委派；cooldown-only / range-only 驗證兩刀可獨立降級。測試自驗 argv 與
# 實際模式相符，property 名稱打錯會炸在測試裡，不會默默把 enforce 版跑五遍假綠。
java "-Dmdc.animalRequestCooldownMs=6000" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalRequestGateTest enforce
Assert-Ok "AnimalRequestGateTest（both enforce，出貨組態）"
java "-Dmdc.animalRequestCooldown=2" "-Dmdc.animalRequestRange=2" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalRequestGateTest observe
Assert-Ok "AnimalRequestGateTest（both observe，只計數不過濾）"
java "-Dmdc.animalRequestCooldown=0" "-Dmdc.animalRequestRange=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalRequestGateTest off
Assert-Ok "AnimalRequestGateTest（both off kill switch，純委派）"
java "-Dmdc.animalRequestRange=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalRequestGateTest cooldown-only
Assert-Ok "AnimalRequestGateTest（cooldown-only，range 獨立降級）"
java "-Dmdc.animalRequestCooldown=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalRequestGateTest range-only
Assert-Ok "AnimalRequestGateTest（range-only，cooldown 獨立降級）"

Write-Host "[9l/10] 主迴圈凍結看門狗（W15）行為驗證（獨立 JVM；時序依 POLL_MS=1000）..."
# on＝clamp 下限＋凍結偵測＋快照＋恢復不重入＋再凍結重入；off＝kill switch 純早退
# （零記錄零偵測零快照）；clamp＝門檻上限咬住。測試自驗 argv 與實際模式相符。
java "-Dmdc.mainLoopWatchdogThresholdMs=500" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.MainLoopWatchdogTest on
Assert-Ok "MainLoopWatchdogTest（on：clamp 下限＋凍結偵測＋恢復重入）"
java "-Dmdc.mainLoopWatchdog=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.MainLoopWatchdogTest off
Assert-Ok "MainLoopWatchdogTest（off kill switch：零記錄零偵測）"
java "-Dmdc.mainLoopWatchdogThresholdMs=999999" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.MainLoopWatchdogTest clamp
Assert-Ok "MainLoopWatchdogTest（threshold 上限 clamp）"

# 退役（2026-09-02）：[9m/10] W16 動物卸載接手守衛測試隨刀移除（8 天全零遺失，
# 觀測結論已達）。詳見 docs/patches.md 2ad。

Write-Host "[9n/10] hutch 載入回傳守衛（W17）三模式行為驗證（獨立 JVM）..."
# ZeroRandom 確定性製造「有空槽但 vanilla 101 次全撞同槽」；enforce force-put、
# observe 只記不救、off 純委派三路徑都必須真跑，並驗滿舍20隻/第21隻CRITICAL。
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.HutchLoadGuardTest enforce
Assert-Ok "HutchLoadGuardTest（enforce，預設出貨模式）"
java "-Dmdc.hutchLoadGuard=2" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.HutchLoadGuardTest observe
Assert-Ok "HutchLoadGuardTest（observe，只記不救）"
java "-Dmdc.hutchLoadGuard=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.HutchLoadGuardTest off
Assert-Ok "HutchLoadGuardTest（off，純委派 kill switch）"

Write-Host "[9n2/10] 雞舍自發同步收件人過濾（W26）三模式行為驗證（獨立 JVM；MODE 是 static final）..."
# enforce＝預設出貨（真的過濾掉範圍外連線）；observe＝只量測 wouldSkip／wouldSkipBytes、
# 照樣全量廣播；off＝純委派原版 sync。三個模式都必須真跑過：測試自驗 argv 與實際 MODE
# 相符，property 名稱打錯會炸在測試裡，不會默默把 enforce 版跑三遍假綠。
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.HutchSyncGateTest enforce
Assert-Ok "HutchSyncGateTest（enforce，預設出貨模式）"
java "-Dmdc.hutchSyncGate=2" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.HutchSyncGateTest observe
Assert-Ok "HutchSyncGateTest（observe，只量測不過濾）"
# off 同時關掉 W36 廣播過濾＝共用 teleport 簿記也停用，驗證兩把都關時 TeleportPacket.write 純委派。
java "-Dmdc.hutchSyncGate=0" "-Dmdc.gameEntityRelevancy=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.HutchSyncGateTest off
Assert-Ok "HutchSyncGateTest（hutchSyncGate=0＋gameEntityRelevancy=0，純委派）"

Write-Host "[9n3/10] GameEntity 廣播收件範圍＋CraftLogic 同步變化閘（W36）三模式行為驗證（獨立 JVM）..."
# enforce＝預設出貨（遠方連線略過、位置不可信型別全服、wire 與原版逐位元相同；進度整數百分比／
# in-progress 清單／濕度不變就不送）；observe＝只計數照送；off＝兩把都純委派。測試自驗模式。
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.entity.components.crafting.GameEntityTrafficTest enforce
Assert-Ok "GameEntityTrafficTest（enforce，預設出貨模式）"
java "-Dmdc.gameEntityRelevancy=2" "-Dmdc.craftLogicSyncGate=observe" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.entity.components.crafting.GameEntityTrafficTest observe
Assert-Ok "GameEntityTrafficTest（observe，只計數照送）"
java "-Dmdc.gameEntityRelevancy=0" "-Dmdc.craftLogicSyncGate=off" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.entity.components.crafting.GameEntityTrafficTest off
Assert-Ok "GameEntityTrafficTest（兩把 kill switch 全關，純委派）"

Write-Host "[9o/10] 動物 LOS 節流閘（W18）七組態行為驗證（獨立 JVM）..."
# observe＝預設出貨（自驗預設 N=2、size 採樣兩分支、錯誤契約：簿記 fail-open 恰一次委派＋
# vanilla RuntimeException/Error 原樣外逃）；enforce 反射驅動 frameCounter——逐幀公式 oracle
# ＋同幀一致＋輪轉硬保證（無失明）＋相位分散＋LOD fail-open（frameMod>1 恆 forward）；
# N=4 主測、出貨組態 N=2、clamp 兩端（0→1 等效全跑、999→16）；off＝純直通計數凍結。
# MODE 與 N 都自驗防 property 假綠；文字別名（off/enforce/observe）由 parseMode 支援。
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalLosGateTest observe
Assert-Ok "AnimalLosGateTest（observe，預設出貨模式＋錯誤契約）"
java "-Dmdc.animalLosGate=1" "-Dmdc.animalLosN=4" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalLosGateTest enforce 4
Assert-Ok "AnimalLosGateTest（enforce N=4，幀輪轉主測）"
java "-Dmdc.animalLosGate=enforce" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalLosGateTest enforce 2
Assert-Ok "AnimalLosGateTest（enforce N=2，出貨組態＋文字別名）"
java "-Dmdc.animalLosGate=1" "-Dmdc.animalLosN=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalLosGateTest enforce 1
Assert-Ok "AnimalLosGateTest（enforce clamp 下限 0→1，等效全跑）"
java "-Dmdc.animalLosGate=1" "-Dmdc.animalLosN=999" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalLosGateTest enforce 16
Assert-Ok "AnimalLosGateTest（enforce clamp 上限 999→16）"
java "-Dmdc.animalLosGate=off" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalLosGateTest off
Assert-Ok "AnimalLosGateTest（off 文字別名，純直通 kill switch）"
java "-Dmdc.animalLosGate=bogus" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalLosGateTest observe
Assert-Ok "AnimalLosGateTest（未知值 bogus 落回 observe——parseMode 安全預設方向）"

Write-Host "[9o2/10] 動物 LOS 迴圈殼 Scan 三模式行為驗證（獨立 JVM）..."
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalLosScanTest observe
Assert-Ok "AnimalLosScanTest（observe 預設 timing wrapper）"
java "-Dmdc.animalLosScan=on" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalLosScanTest on
Assert-Ok "AnimalLosScanTest（on fast/delegate/fallback/邊界）"
java "-Dmdc.animalLosScan=off" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalLosScanTest off
Assert-Ok "AnimalLosScanTest（off 直通）"

Write-Host "[9p/10] 車輛永久移除授權守衛（W19 observe）三組態行為驗證（獨立 JVM）..."
# observe＝預設出貨；1 是尚未實作 enforce 的 observe-alias（授權條件待 observe 數據定案）；
# off＝純早退。三個 static-final 組態都真跑並自驗 MODE，property 拼錯不得假綠。
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.VehicleRemoveGuardTest observe
Assert-Ok "VehicleRemoveGuardTest（observe，預設出貨模式）"
java "-Dmdc.vehicleRemoveGuard=1" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.VehicleRemoveGuardTest enforce
Assert-Ok "VehicleRemoveGuardTest（mode=1，本版 observe-alias）"
java "-Dmdc.vehicleRemoveGuard=off" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.VehicleRemoveGuardTest off
Assert-Ok "VehicleRemoveGuardTest（off 文字別名，純早退 kill switch）"

Write-Host "[9q/10] 衣物同步守衛（W20）三組態行為驗證（獨立 JVM）..."
# observe＝預設出貨（(b) 記錄後拋 NPE 保 vanilla 語意、(c) 資訊超集行、(a) square-null 分解）；
# enforce＝(b) null→white 修復（僅 tint 刀有 enforce 語意）；off＝三把 kill switch 全關、
# 純直通/早退。三組態都真跑並自驗模式，property 拼錯不得假綠。
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.ClothingSyncGuardTest observe
Assert-Ok "ClothingSyncGuardTest（observe，預設出貨模式）"
java "-Dmdc.clothingTintGuard=1" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.ClothingSyncGuardTest enforce
Assert-Ok "ClothingSyncGuardTest（tint enforce，null→white 修復）"
java "-Dmdc.clothingTintGuard=off" "-Dmdc.visualsMismatchProbe=0" "-Dmdc.containerIdProbe=off" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.ClothingSyncGuardTest off
Assert-Ok "ClothingSyncGuardTest（三把 kill switch 全關，純直通）"

Write-Host "[9r/10] 面向物件 sprite-grid null 守衛（W22）行為驗證＋kill switch（獨立 JVM）..."
# on＝預設出貨（null→回原 object、非 null 逐位元轉發、委派例外穿透）；off＝純直通（null 照回）。
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.FaceObjectGuardTest
Assert-Ok "FaceObjectGuardTest（on，出貨組態）"
java "-Dmdc.faceObjectGuard=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.FaceObjectGuardTest off
Assert-Ok "FaceObjectGuardTest（faceObjectGuard=0 kill switch）"

Write-Host "[9q2/10] 伺服器角色聲音參數跳過（W34）三態行為驗證（獨立 JVM）..."
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.EmitterParamGateTest observe
Assert-Ok "EmitterParamGateTest（observe，預設出貨）"
java "-Dmdc.emitterParamGate=enforce" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.EmitterParamGateTest enforce
Assert-Ok "EmitterParamGateTest（enforce）"
java "-Dmdc.emitterParamGate=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.EmitterParamGateTest off
Assert-Ok "EmitterParamGateTest（off kill switch）"

Write-Host "[9q3/10] 使用中玩家索引（W35）三態行為驗證（獨立 JVM）..."
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.entity.MdcUsingPlayerIndexTest observe
Assert-Ok "MdcUsingPlayerIndexTest（observe，預設出貨）"
java "-Dmdc.usingPlayerIndex=enforce" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.entity.MdcUsingPlayerIndexTest enforce
Assert-Ok "MdcUsingPlayerIndexTest（enforce）"
java "-Dmdc.usingPlayerIndex=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.entity.MdcUsingPlayerIndexTest off
Assert-Ok "MdcUsingPlayerIndexTest（off kill switch）"

Write-Host "[9r2/10] 動物離線補算觀測（W32）行為驗證＋kill switch（獨立 JVM）..."
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalAwayProbeTest
Assert-Ok "AnimalAwayProbeTest（observe，出貨組態）"
java "-Dmdc.animalAwayProbe=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalAwayProbeTest off
Assert-Ok "AnimalAwayProbeTest（animalAwayProbe=0 kill switch）"

Write-Host "[9r3/10] 分娩品種守衛（W33）行為驗證＋kill switch（獨立 JVM）..."
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.BabyBreedGuardTest
Assert-Ok "BabyBreedGuardTest（on，出貨組態）"
java "-Dmdc.babyBreedGuard=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.BabyBreedGuardTest off
Assert-Ok "BabyBreedGuardTest（babyBreedGuard=0 kill switch）"

Write-Host "[9r4/10] 動物半建構物件守衛＋apop 先序列化再開檔（W37）行為驗證＋kill switch（獨立 JVM）..."
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalSpawnGuardTest
Assert-Ok "AnimalSpawnGuardTest（on，出貨組態）"
java "-Dmdc.animalSpawnGuard=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalSpawnGuardTest off
Assert-Ok "AnimalSpawnGuardTest（animalSpawnGuard=0 kill switch）"
# off 組態重現原版事故（例外外拋＋apop 截成 0 bytes），on 保留舊檔。
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.characters.animals.MdcAnimalCellSaveTest on
Assert-Ok "MdcAnimalCellSaveTest（on，出貨組態）"
java "-Dmdc.animalCellSave=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.characters.animals.MdcAnimalCellSaveTest off
Assert-Ok "MdcAnimalCellSaveTest（animalCellSave=0，原版截斷重現）"

Write-Host "[9r5/10] 畜牧區補算快照（W38）＋動物死亡帳本（W39）行為驗證＋kill switch（獨立 JVM）..."
# W38 off 組態以原版迴圈形狀重現重複補算／漏算；on 每隻恰一次。
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalMetaSnapshotTest on
Assert-Ok "AnimalMetaSnapshotTest（on，出貨組態）"
java "-Dmdc.animalMetaSnapshot=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalMetaSnapshotTest off
Assert-Ok "AnimalMetaSnapshotTest（animalMetaSnapshot=0，原版重現）"
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalDeathLedgerTest on
Assert-Ok "AnimalDeathLedgerTest（on，出貨組態）"
java "-Dmdc.animalDeathLedger=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalDeathLedgerTest off
Assert-Ok "AnimalDeathLedgerTest（animalDeathLedger=0）"

Write-Host "[9r6/10] 物品處理清單 null 容錯＋跨執行緒寫入觀測（W40）行為驗證＋kill switch（獨立 JVM；走 dist 內手術後的真 IsoCell）..."
# off 組態重現原版：null 處 NPE、之後物品不處理、null 留在清單；on 同幀移除並記錄其他執行緒寫入。
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.ProcessItemsGuardTest on
Assert-Ok "ProcessItemsGuardTest（on，出貨組態）"
java "-Dmdc.processItemsGuard=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.ProcessItemsGuardTest off
Assert-Ok "ProcessItemsGuardTest（processItemsGuard=0，原版重現）"

Write-Host "[9s/10] W10-C／W10-E 連線身分與取消隔離回歸（獨立 JVM）..."
# 觀測模式不控制安全取消開關；涵蓋真 wire、同 id 不同連線、合法取消與上下文收尾。
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.core.MdcTimedActionProbeTest observe
Assert-Ok "MdcTimedActionProbeTest（observe，預設出貨模式）"
java "-Dmdc.timedActionProbe=1" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.core.MdcTimedActionProbeTest enforce
Assert-Ok "MdcTimedActionProbeTest（enforce，補送 Reject 路徑）"
java "-Dmdc.timedActionProbe=off" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.core.MdcTimedActionProbeTest off
Assert-Ok "MdcTimedActionProbeTest（觀測 off，取消隔離仍有效）"
java "-Dmdc.actionRemoveScope=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.core.MdcTimedActionProbeTest observe scope-vanilla
Assert-Ok "MdcTimedActionProbeTest（actionRemoveScope=0，明示回原版取消行為）"

Write-Host "[9t/10] 序列化物件池執行緒隔離（W25）行為驗證＋kill switch（獨立 JVM；走 dist 內手術後的真 BitHeader/ByteBlock）..."
# on＝預設出貨（round trip 逐位元、同執行緒 LIFO 回收同實例、4 執行緒零跨執行緒共用、全域池零寫入、
# cap／分槽溢位／null 契約）；off＝三處全部回到 vanilla 共用池。測試自驗旗標，property 拼錯不得假綠。
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.IoPoolIsolationTest
Assert-Ok "IoPoolIsolationTest（on，出貨組態）"
java "-Dmdc.ioPoolIsolation=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.IoPoolIsolationTest off
Assert-Ok "IoPoolIsolationTest（ioPoolIsolation=0 kill switch）"

Write-Host "[9u/10] RequestData ACK 邊界與正常傳送（W27）..."
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.network.RequestDataAckTest
Assert-Ok "RequestDataAckTest（空佇列、未知連線、重複 ACK、完整傳送與例外穿透）"

Write-Host "[9v/10] PopMan 缺格回退補鎖（W28）互斥、例外解鎖與停用對照..."
java -cp "$R\work\out;$ASM_CP;$R\dist\java;$R\work\projectzomboid.jar" PopManAddLockTest "$R\dist\java" "$R\work\projectzomboid.jar" on
Assert-Ok "PopManAddLockTest（on，兩條真 caller 與例外解鎖）"
java "-Dmdc.popmanAddLock=0" -cp "$R\work\out;$ASM_CP;$R\dist\java;$R\work\projectzomboid.jar" PopManAddLockTest "$R\dist\java" "$R\work\projectzomboid.jar" off
Assert-Ok "PopManAddLockTest（off，原 native 委派仍執行）"

Write-Host "[9w/10] 動物同步接收驗證（W29）與原版回退對照..."
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalUpdateGuardTest enforce
Assert-Ok "AnimalUpdateGuardTest（預設 enforce，真接收入口、狀態保護、限頻與紀錄故障）"
java "-Dmdc.animalUpdateGuard=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalUpdateGuardTest off
Assert-Ok "AnimalUpdateGuardTest（off，原版危險行為對照）"
java "-Dmdc.animalUpdateGuard=bogus" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.AnimalUpdateGuardTest unknown
Assert-Ok "AnimalUpdateGuardTest（未知值仍 enforce）"

Write-Host "[9x/10] 大批物品登記（W30）順序、移除撤銷、回呼與原版回退..."
java -Xverify:all -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.BulkItemRegistrationTest
Assert-Ok "BulkItemRegistrationTest（on，真 IsoCell 差分）"
java -Xverify:all "-Dmdc.bulkItemRegistration=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.BulkItemRegistrationTest off
Assert-Ok "BulkItemRegistrationTest（0，原版回退）"
java -Xverify:all "-Dmdc.bulkItemRegistration=off" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.BulkItemRegistrationTest off
Assert-Ok "BulkItemRegistrationTest（off，文字別名）"
java -Xverify:all -XX:+UnlockExperimentalVMOptions -XX:hashCode=2 -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.BulkItemRegistrationTest collisions
Assert-Ok "BulkItemRegistrationTest（Comparable 強制碰撞回呼）"

Write-Host "[9y/10] 魚群廣播（W31）真 writer/send/decoder、錯誤續送與原版回退..."
java -Xverify:all -cp "$R\work\out;$ASM_CP" FishingDataBroadcastTest "$R\dist\java" "$R\work\projectzomboid.jar" on
Assert-Ok "FishingDataBroadcastTest（on，多收件人與錯誤路徑）"
java -Xverify:all "-Dmdc.fishingDataBroadcast=0" -cp "$R\work\out;$ASM_CP" FishingDataBroadcastTest "$R\dist\java" "$R\work\projectzomboid.jar" off
Assert-Ok "FishingDataBroadcastTest（0，原版回退）"
java -Xverify:all "-Dmdc.fishingDataBroadcast=off" -cp "$R\work\out;$ASM_CP" FishingDataBroadcastTest "$R\dist\java" "$R\work\projectzomboid.jar" off
Assert-Ok "FishingDataBroadcastTest（off，文字別名）"

Write-Host "[9z/10] 聲音封包純觀測：慢呼叫、同批累積與例外隔離..."
java -Xverify:all "-Dmdc.mainLoopWatchdog=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.network.packets.sound.MdcWorldSoundProbeTest observe
Assert-Ok "MdcWorldSoundProbeTest（observe，看門狗停用仍保留批次界線）"
java -Xverify:all "-Dmdc.worldSoundProbe=off" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.network.packets.sound.MdcWorldSoundProbeTest off
Assert-Ok "MdcWorldSoundProbeTest（off，原版委派）"
java -Xverify:all -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.network.packets.sound.MdcWorldSoundProbeTest unarmed
Assert-Ok "MdcWorldSoundProbeTest（主迴圈武裝前維持原版）"
java -Xverify:all -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.network.packets.sound.MdcWorldSoundProbeTest logthrow
Assert-Ok "MdcWorldSoundProbeTest（真 logger 故障與原例外隔離）"

Write-Host "[10/10] entity removal 尺度 benchmark（時間只報告，不設機器相依閾值）..."
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.FastIdentityArrayRemovalBenchmark
Assert-Ok "FastIdentityArrayRemovalBenchmark"

Copy-Item "$R\deploy\install.sh", "$R\deploy\uninstall.sh" "$R\dist\" -Force
Write-Host "完成：dist\java（loose classes）＋ dist\manifest.txt ＋ install/uninstall.sh"
