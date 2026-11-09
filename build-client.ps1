# build-client.ps1 — 同一個套件包含共用核心、Profiler、client 修復的三個變體，打成一個 Java agent jar（MdcAgent 多模組）。
# 只產出 dist-client-modular／output；不安裝、不啟動遊戲、不動 server manifest。
param()
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$R = $PSScriptRoot
$JDK = "$env:USERPROFILE\scoop\apps\temurin25-jdk\current"
$JAVA = Join-Path $JDK 'bin/java.exe'
$JAVAC = Join-Path $JDK 'bin/javac.exe'
if (-not (Test-Path -LiteralPath $JAVAC)) { throw '找不到 JDK 25' }
$JAR = Join-Path $R 'work/projectzomboid.jar'
if (-not (Test-Path -LiteralPath $JAR)) { throw '缺 work/projectzomboid.jar' }
$GAME_VERSION = '42.22.2'
$PACKAGE_VERSION = '0.3.0'
$DIST = Join-Path $R 'dist-client-modular'
$OUT = Join-Path $R 'work/out-client-modular'
$GEN = Join-Path $R 'work/gen-client-modular'
$AGENT_CLASSES = Join-Path $R 'work/agent-classes-client'
$AGENT_CHECK = Join-Path $R 'work/agentcheck-client'
$AGENT_JAR_NAME = 'MinidoracatClientPatches.jar'
$ASM_CP = "$R\lib\asm-9.8.jar;$R\lib\asm-tree-9.8.jar;$R\lib\asm-analysis-9.8.jar;$R\lib\asm-util-9.8.jar"
$UTF8 = [System.Text.UTF8Encoding]::new($false)

function Assert-Ok([string]$Step) {
    if ($LASTEXITCODE -ne 0) { throw "$Step 失敗 (exit=$LASTEXITCODE)" }
}
function Write-Utf8([string]$Path, [string]$Content) {
    [System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($Path)) | Out-Null
    [System.IO.File]::WriteAllText($Path, $Content.Replace("`r`n", "`n"), $UTF8)
}
function Get-ClassEntries([string]$Root) {
    @(Get-ChildItem -LiteralPath $Root -Recurse -Filter '*.class' -File | Sort-Object FullName | ForEach-Object {
        $relative = $_.FullName.Substring($Root.Length + 1).Replace('\', '/')
        [PSCustomObject]@{ path = $relative; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
    })
}

$jarSha = (Get-FileHash -LiteralPath $JAR -Algorithm SHA256).Hash.ToLowerInvariant()
$sourceRef = (& git -C $R rev-parse --short HEAD)
if (-not $sourceRef) { $sourceRef = 'nogit' }
if ((& git -C $R status --porcelain --untracked-files=no) -or (& git -C $R status --porcelain -- patcher deploy-client)) {
    $sourceRef = "$sourceRef+dirty"
}
$built = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
$coreVersion = "$PACKAGE_VERSION($sourceRef)"
foreach ($path in @($DIST, $OUT, $GEN, $AGENT_CLASSES, $AGENT_CHECK)) {
    if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Recurse -Force }
    New-Item -ItemType Directory -Path $path -Force | Out-Null
}

Write-Host '[1/8] 編譯 ASM 工具與 agent'
& $JAVAC -encoding UTF-8 -cp $ASM_CP -d $OUT @((Get-ChildItem "$R\patcher\src\*.java").FullName)
Assert-Ok 'javac patcher'
# agent 只用 JDK；打包時才和各模組 payload 合成 agent jar
& $JAVAC -encoding UTF-8 -d $AGENT_CLASSES @((Get-ChildItem "$R\patcher\agent" -Recurse -Filter '*.java').FullName)
Assert-Ok 'javac agent'
# 42.22 起官方 jar 簽章：手術後的 class 以 loose 檔放在簽章 jar 前面會丟 SecurityException。
# LoadCheck／BytecodeVerify／SmokeCheck／行為測試用拿掉簽章檔的副本（class bytes 相同，build.ps1 同一份）；
# Patcher、PatchInfo 指紋與 AgentLoadCheck 讀原 jar。
$TJ = Join-Path $R 'work/projectzomboid-unsigned.jar'
$tjStamp = "$TJ.src-sha256"
if (-not ((Test-Path -LiteralPath $TJ) -and (Test-Path -LiteralPath $tjStamp) -and ((Get-Content -Raw -LiteralPath $tjStamp).Trim() -eq $jarSha))) {
    & $JAVA -cp $OUT UnsignedTestJar $JAR $TJ
    Assert-Ok 'UnsignedTestJar'
    [System.IO.File]::WriteAllText($tjStamp, $jarSha)
}

Write-Host '[2/8] 編譯共用核心與採樣器'
$buildInfo = @"
package zombie.mdc;
public final class MdcPatchBuildInfo {
    public static final String VERSION = "$coreVersion";
    public static final String BUILT = "$built";
    public static final String JAR_SHA256 = "$jarSha";
    private MdcPatchBuildInfo() {}
}
"@
Write-Utf8 "$GEN\zombie\mdc\MdcPatchBuildInfo.java" $buildInfo
$coreHelpers = Join-Path $DIST 'helpers/core'
New-Item -ItemType Directory -Path $coreHelpers -Force | Out-Null
$coreSources = @((Get-ChildItem "$R\patcher\game-client-common" -Recurse -Filter '*.java').FullName)
$coreSources += @((Get-ChildItem "$R\patcher\game-profiler" -Recurse -Filter '*.java').FullName)
$coreSources += "$GEN\zombie\mdc\MdcPatchBuildInfo.java"
& $JAVAC -encoding UTF-8 -cp $JAR -d $coreHelpers @coreSources
Assert-Ok 'javac common/profiler helpers'

Write-Host '[3/8] 產生各模組 payload 與逐方法守門'
$recipes = @(
    @{ id='core'; mode='client-core'; name='核心元件（自動安裝）'; nameEn='Core components (installed automatically)'; version=$coreVersion; requires=@() },
    @{ id='profiler'; mode='client-profiler'; name='DevProfiler 效能分析工具（模組開發者用）'; nameEn='DevProfiler performance tool (for mod developers)'; version=$coreVersion; requires=@('core') },
    @{ id='client-fixes-standard'; mode='client'; name='客戶端修復・標準版（記憶體 32GB 以上）'; nameEn='Client fixes - standard (32 GB RAM or more)'; version="v3.8($sourceRef)"; requires=@('core'); group='client-fixes' },
    @{ id='client-fixes-lowmem'; mode='client-lowmem'; name='客戶端修復・省記憶體版（記憶體 32GB 以下）'; nameEn='Client fixes - low memory (less than 32 GB RAM)'; version="v3.8-lowmem($sourceRef)"; requires=@('core'); group='client-fixes' },
    # 相容版：只裝 PZ_Optimization 這類 MOD 不替換的 class（PatchConfig.clientCompat），helper 只帶這幾刀用到的
    @{ id='client-fixes-compat'; mode='client-compat'; name='客戶端修復・相容版（只含車輛修補）'; nameEn='Client fixes - compatible (vehicle fixes only)'; version="v3.8-compat($sourceRef)"; requires=@('core'); group='client-fixes';
       helpers=@('zombie/mdc/PatchInfo.class', 'zombie/mdc/VehicleAnimGate.class', 'zombie/mdc/VehicleAnimGate$PoseState.class',
                 'zombie/mdc/VehicleClampGuard.class', 'zombie/mdc/VehicleOrphanGuard.class', 'zombie/vehicles/MdcVehicleSinkGuard.class') }
)
$modules = @()
$moduleManifests = @{}
foreach ($recipe in $recipes) {
    $id = $recipe.id
    $payload = Join-Path $DIST "payload/$id"
    New-Item -ItemType Directory -Path $payload -Force | Out-Null
    $helperEntries = @()
    if ($id -eq 'core') {
        Copy-Item -Path "$coreHelpers\*" -Destination $payload -Recurse -Force
        $helperEntries = @(Get-ClassEntries $payload)
    } elseif ($id.StartsWith('client-fixes-')) {
        $variantGen = Join-Path $GEN $id
        & $JAVA -cp $OUT PatchInfoGen $variantGen client $recipe.version $built $jarSha.Substring(0, 8)
        Assert-Ok "PatchInfoGen $id"
        $fixSources = @((Get-ChildItem "$R\patcher\game-client" -Recurse -Filter '*.java').FullName)
        $fixSources += "$variantGen\zombie\mdc\PatchInfo.java"
        & $JAVAC -encoding UTF-8 -cp "$JAR;$coreHelpers" -d $payload @fixSources
        Assert-Ok "javac $id"
        if ($recipe.helpers) {
            foreach ($entry in @(Get-ClassEntries $payload)) {
                if ($recipe.helpers -notcontains $entry.path) { Remove-Item -LiteralPath (Join-Path $payload $entry.path) -Force }
            }
            Get-ChildItem -LiteralPath $payload -Recurse -Directory | Sort-Object { $_.FullName.Length } -Descending |
                Where-Object { -not (Get-ChildItem -LiteralPath $_.FullName -Force) } | Remove-Item -Force
        }
        $helperEntries = @(Get-ClassEntries $payload)
    }
    $manifestPath = Join-Path $DIST "manifests/$id.txt"
    & $JAVA -cp "$OUT;$ASM_CP" Patcher $JAR $payload $manifestPath $recipe.mode
    Assert-Ok "Patcher $id"
    $patchedLines = @(Get-Content -LiteralPath $manifestPath)
    $lines = @($helperEntries | ForEach-Object { "$($_.path)`t-`t$($_.sha256)`t0hits" }) + $patchedLines
    Write-Utf8 $manifestPath (($lines -join "`n") + "`n")
    $moduleManifests[$id] = $lines
    # 安裝器只需要模組資訊與「這個模組換掉哪些原版 class」（偵測遊戲資料夾裡會蓋掉它們的外來 loose class）
    $patchedClasses = @($patchedLines | Where-Object { $_ } | ForEach-Object { ($_.Split("`t")[0]) -replace '\.class$', '' })
    $module = [ordered]@{ id=$id; name=$recipe.name; nameEn=$recipe.nameEn; version=$recipe.version; requires=@($recipe.requires); patchedClasses=$patchedClasses }
    if ($recipe.group) { $module.exclusiveGroup = $recipe.group }
    $modules += $module
}

Write-Host '[4/8] 每個模組與核心合併後做 linkage／bytecode／語意驗證（測試 jar）'
foreach ($recipe in $recipes) {
    $id = $recipe.id
    $view = Join-Path $DIST "checks/$id"
    New-Item -ItemType Directory -Path $view -Force | Out-Null
    Copy-Item -Path "$DIST\payload\core\*" -Destination $view -Recurse -Force
    $checkLines = @($moduleManifests['core'])
    if ($id -ne 'core') {
        Copy-Item -Path "$DIST\payload\$id\*" -Destination $view -Recurse -Force
        $checkLines += $moduleManifests[$id]
    }
    $checkManifest = Join-Path $DIST "checks/$id-manifest.txt"
    Write-Utf8 $checkManifest (($checkLines -join "`n") + "`n")
    & $JAVA -Xverify:all -cp "$OUT;$ASM_CP" LoadCheck $view $TJ $checkManifest $recipe.mode
    Assert-Ok "LoadCheck $id"
    & $JAVA -cp "$OUT;$ASM_CP" BytecodeVerify $view $TJ $checkManifest
    Assert-Ok "BytecodeVerify $id"
    & $JAVA -cp "$OUT;$ASM_CP" SmokeCheck $view $TJ $recipe.mode
    Assert-Ok "SmokeCheck $id"
}

Write-Host '[5/8] 執行新核心與既有 client 修復行為測試（測試 jar）'
$testSources = @((Get-ChildItem "$R\patcher\tests-client" -Recurse -Filter '*.java').FullName)
$testSources += @((Get-ChildItem "$R\patcher\tests-profiler" -Recurse -Filter '*.java').FullName)
if (Test-Path "$R\patcher\tests-client-common") {
    $testSources += @((Get-ChildItem "$R\patcher\tests-client-common" -Recurse -Filter '*.java').FullName)
}
& $JAVAC -encoding UTF-8 -cp "$OUT;$ASM_CP;$DIST\checks\client-fixes-standard;$JAR" -d $OUT @testSources
Assert-Ok 'javac client behavior tests'
foreach ($id in @('client-fixes-standard', 'client-fixes-lowmem', 'client-fixes-compat')) {
    $cp = "$OUT;$DIST\checks\$id;$TJ"
    # 相容版只有車輛的修補；貼圖、串流、W55、W57、W68、W84 的 class 不在它的 view 裡，只在完整版測
    $full = $id -ne 'client-fixes-compat'
    $simpleTests = if ($full) { @('zombie.mdc.TexturePipelineGuardBehaviorTest', 'zombie.core.textures.MinidoracatTextureLeakGuardBehaviorTest', 'zombie.mdc.ChunkStreamObserverBehaviorTest') } else { @() }
    foreach ($test in $simpleTests) {
        & $JAVA --enable-native-access=ALL-UNNAMED -cp $cp $test
        Assert-Ok "$test $id"
    }
    # W54：出貨 on、verify、off（kill switch 實跑；off＝原版負對照）
    foreach ($run in @(@('on', @()), @('verify', @('-Dmdc.vehAnimSkip=verify')), @('off', @('-Dmdc.vehAnimSkip=off')))) {
        $jvmArgs = @($run[1])
        & $JAVA --enable-native-access=ALL-UNNAMED @jvmArgs -cp $cp zombie.mdc.VehicleAnimGateBehaviorTest $run[0]
        Assert-Ok "VehicleAnimGateBehaviorTest $($run[0]) $id"
    }
    if ($full) {
        # W55：on 與 off 各跑一次，60 幀真動畫的姿勢摘要必須逐位相同
        $digests = @{}
        foreach ($run in @(@('on', @()), @('off', @('-Dmdc.boneReparentFast=off')))) {
            $jvmArgs = @($run[1])
            $runOutput = & $JAVA --enable-native-access=ALL-UNNAMED @jvmArgs -cp $cp zombie.mdc.BoneReparentFastPathBehaviorTest $run[0]
            $code = $LASTEXITCODE
            $runOutput | ForEach-Object { Write-Host $_ }
            if ($code -ne 0) { throw "BoneReparentFastPathBehaviorTest $($run[0]) $id 失敗 (exit=$code)" }
            $digests[$run[0]] = @($runOutput | Where-Object { $_ -like 'pose-digest=*' })
        }
        if ($digests['on'].Count -ne 1 -or $digests['on'][0] -ne $digests['off'][0]) {
            throw "W55 姿勢摘要 on／off 不一致 $id：$($digests['on']) vs $($digests['off'])"
        }
        Write-Host "  W55 on／off 姿勢摘要相同（$id）"
        # W57：on 與 off 各跑一次（off＝kill switch 實跑，結果必須與原版相同）
        foreach ($run in @(@('on', @()), @('off', @('-Dmdc.staleRoomHeal=off')))) {
            $jvmArgs = @($run[1])
            & $JAVA --enable-native-access=ALL-UNNAMED @jvmArgs -cp $cp zombie.mdc.StaleRoomGuardBehaviorTest $run[0]
            Assert-Ok "StaleRoomGuardBehaviorTest $($run[0]) $id"
        }
    }
    # W61：on 與 off 各跑一次（off＝kill switch 實跑，車照原版被夾到過期 chunk 的角落＝原版負對照）
    foreach ($run in @(@('on', @()), @('off', @('-Dmdc.vehicleClampGuard=off')))) {
        $jvmArgs = @($run[1])
        & $JAVA --enable-native-access=ALL-UNNAMED @jvmArgs -cp $cp zombie.mdc.VehicleClampGuardBehaviorTest $run[0]
        Assert-Ok "VehicleClampGuardBehaviorTest $($run[0]) $id"
    }
    # W62／W63：on、只關 W62、只關 W63 各跑一次（關掉的那一把必須與原版相同，另一把照常）
    foreach ($run in @(@('on', @()), @('updateOff', @('-Dmdc.vehicleOrphanUpdate=off')), @('recallOff', @('-Dmdc.driverRecallGuard=off')))) {
        $jvmArgs = @($run[1])
        & $JAVA --enable-native-access=ALL-UNNAMED @jvmArgs -cp $cp zombie.mdc.VehicleOrphanGuardBehaviorTest $run[0]
        Assert-Ok "VehicleOrphanGuardBehaviorTest $($run[0]) $id"
    }
    # W65：on 與 off 各跑一次（off＝kill switch 實跑，車照原版留在地板下、翻覆的也不扶正＝原版負對照）
    foreach ($run in @(@('on', @()), @('off', @('-Dmdc.vehicleSinkGuard=off')))) {
        $jvmArgs = @($run[1])
        & $JAVA --enable-native-access=ALL-UNNAMED @jvmArgs -cp $cp zombie.vehicles.MdcVehicleSinkGuardBehaviorTest $run[0]
        Assert-Ok "MdcVehicleSinkGuardBehaviorTest $($run[0]) $id"
    }
    if ($full) {
        # W68：on 與 off 各跑一次（off＝kill switch 實跑，重建與查房間照原版不互斥＝原版負對照）
        foreach ($run in @(@('on', @()), @('off', @('-Dmdc.metaGridLock=off')))) {
            $jvmArgs = @($run[1])
            & $JAVA --enable-native-access=ALL-UNNAMED @jvmArgs -cp $cp zombie.mdc.MetaGridLockBehaviorTest $run[0]
            Assert-Ok "MetaGridLockBehaviorTest $($run[0]) $id"
        }
        # W84：on、verify、off 各跑一次（手術後的 FBORenderCell 與 jar 原版在同一組替身下逐筆比對；off＝kill switch 實跑），
        # 三次的 blood-digest 必須相同（原版跑在包過與沒包過的清單上結果一樣）
        $bloodDigests = @{}
        foreach ($run in @(@('on', @()), @('verify', @('-Dmdc.bloodSplatIndex=verify')), @('off', @('-Dmdc.bloodSplatIndex=off')))) {
            $jvmArgs = @($run[1])
            $runOutput = & $JAVA --enable-native-access=ALL-UNNAMED @jvmArgs -cp "$cp;$ASM_CP" zombie.mdc.BloodSplatIndexBehaviorTest $run[0]
            $code = $LASTEXITCODE
            $runOutput | ForEach-Object { Write-Host $_ }
            if ($code -ne 0) { throw "BloodSplatIndexBehaviorTest $($run[0]) $id 失敗 (exit=$code)" }
            $bloodDigests[$run[0]] = @($runOutput | Where-Object { $_ -like 'blood-digest=*' })
        }
        if ($bloodDigests['on'].Count -ne 1 -or $bloodDigests['on'][0] -ne $bloodDigests['verify'][0] -or $bloodDigests['on'][0] -ne $bloodDigests['off'][0]) {
            throw "W84 blood-digest on／verify／off 不一致 $id：$($bloodDigests['on']) / $($bloodDigests['verify']) / $($bloodDigests['off'])"
        }
        Write-Host "  W84 on／verify／off blood-digest 相同（$id）"
    }
}
$profilerTests = @(Get-ChildItem "$R\patcher\tests-profiler" -Recurse -Filter '*Test.java')
if (Test-Path "$R\patcher\tests-client-common") {
    $profilerTests += @(Get-ChildItem "$R\patcher\tests-client-common" -Recurse -Filter '*Test.java')
}
foreach ($test in $profilerTests) {
    $className = 'zombie.mdc.' + $test.BaseName
    # MdcPatchRuntimeTest 要建置基準 jar 的 SHA（classpath 上是測試 jar）
    & $JAVA --enable-native-access=ALL-UNNAMED "-Dmdc.baseJar=$JAR" -cp "$OUT;$DIST\checks\profiler;$TJ" $className
    Assert-Ok $className
}

Write-Host "[6/8] 打包 agent jar（$AGENT_JAR_NAME，五個模組）並在官方簽章 jar 下驗證載入路徑"
$spec = Join-Path $DIST 'agent-modules.tsv'
$specLines = @($recipes | ForEach-Object {
    $group = if ($_.group) { $_.group } else { '-' }
    $requires = if (@($_.requires).Count -gt 0) { @($_.requires) -join ',' } else { '-' }
    "$($_.id)`t$group`t$requires`t$DIST\payload\$($_.id)`t$DIST\manifests\$($_.id).txt"
})
Write-Utf8 $spec (($specLines -join "`n") + "`n")
$agentJar = Join-Path $DIST $AGENT_JAR_NAME
& $JAVA -cp "$OUT;$ASM_CP" AgentJarBuilder --modules $JAR $spec $AGENT_CLASSES $coreVersion $built $agentJar (Join-Path $DIST 'agent-manifest.txt')
Assert-Ok 'AgentJarBuilder --modules'
# 工具單獨放一個目錄：classpath 上不要有 $OUT 的測試 class 混進遊戲 package
Copy-Item -LiteralPath "$OUT\AgentLoadCheck.class" -Destination $AGENT_CHECK -Force
function Invoke-AgentLoadCheck([string]$Modules, [string[]]$ManifestIds, [string]$Expect, [string]$GameJar = $JAR, [string[]]$JvmArgs = @()) {
    $merged = Join-Path $DIST "agentcheck-$($Modules -replace ',', '+')-$Expect.txt"
    $mergedLines = @()
    foreach ($m in $ManifestIds) { $mergedLines += $moduleManifests[$m] }
    Write-Utf8 $merged (($mergedLines -join "`n") + "`n")
    & $JAVA -Xverify:all "-javaagent:$agentJar=modules=$Modules" @JvmArgs -cp "$AGENT_CHECK;$GameJar" AgentLoadCheck $merged $Expect
    Assert-Ok "AgentLoadCheck $Expect modules=$Modules $($JvmArgs -join ' ')"
}
foreach ($variant in @('client-fixes-standard', 'client-fixes-lowmem', 'client-fixes-compat')) {
    Invoke-AgentLoadCheck "core,$variant" @('core', $variant) 'armed'
}
Invoke-AgentLoadCheck 'core,profiler,client-fixes-standard' @('core', 'profiler', 'client-fixes-standard') 'armed'
Invoke-AgentLoadCheck 'core,client-fixes-standard' @('core', 'client-fixes-standard') 'off' -JvmArgs @('-Dmdc.agent=off')
# 遊戲更新的情境：classpath 上的 projectzomboid.jar 不是建置基準（拿 bytes 不同的測試 jar 改名頂替）
$mismatchDir = Join-Path $R 'work/agent-mismatch-client'
New-Item -ItemType Directory -Path $mismatchDir -Force | Out-Null
Copy-Item -LiteralPath $TJ -Destination "$mismatchDir\projectzomboid.jar" -Force
Invoke-AgentLoadCheck 'core,client-fixes-standard' @('core', 'client-fixes-standard') 'disarmed' -GameJar "$mismatchDir\projectzomboid.jar"
# 參數錯：缺依賴（只選變體不選 core）、同一組選兩個變體——一律不啟用
Invoke-AgentLoadCheck 'client-fixes-standard' @('core', 'client-fixes-standard') 'disarmed'
Invoke-AgentLoadCheck 'core,client-fixes-standard,client-fixes-lowmem' @('core', 'client-fixes-standard') 'disarmed'

Write-Host '[7/8] 產生管理器套件與 manifest'
$pkg = Join-Path $DIST 'pkg'
New-Item -ItemType Directory -Path $pkg -Force | Out-Null
Copy-Item -LiteralPath $agentJar -Destination $pkg
foreach ($name in @('Manage-Patches.ps1', 'Install-Patches.bat', 'Uninstall-Patches.bat')) {
    Copy-Item -LiteralPath "$R\deploy-client\$name" -Destination $pkg
}
$manifest = [ordered]@{
    schemaVersion=2; gameVersion=$GAME_VERSION; packageVersion=$PACKAGE_VERSION
    jarSha256=$jarSha; built=$built; sourceRef=$sourceRef
    agent=[ordered]@{ source=$AGENT_JAR_NAME; sha256=(Get-FileHash -LiteralPath $agentJar -Algorithm SHA256).Hash.ToLowerInvariant() }
    modules=$modules
    legacyPackages=@(Get-Content -LiteralPath "$R\deploy-client\legacy-packages.json" -Raw | ConvertFrom-Json)
}
Write-Utf8 "$pkg\manifest.json" (($manifest | ConvertTo-Json -Depth 12) + "`n")
$repoUrl = 'https://github.com/Minidoracat/MinidoracatJavaPatchFor42'
$zipName = "MinidoracatClientPatches-$GAME_VERSION-$PACKAGE_VERSION.zip"
$instructionsEn = @"
Minidoracat Client Patches $PACKAGE_VERSION / Project Zomboid $GAME_VERSION
(繁體中文說明：README-INSTALL.zh-TW.txt)

WHAT THIS IS
Unofficial client-side fixes that only change the game on your own PC. Since 42.22 the
game jar is signed, so the fixes are applied by a small Java agent while the game loads:
the installer copies $AGENT_JAR_NAME into the .mdc-patches folder inside the game
folder and adds one launch option (-javaagent:...) to ProjectZomboid64.json.
projectzomboid.jar itself is not modified.
- Players, zombies and vehicles turn invisible (only the shadow and name tag remain).
- Walking or driving into an area with player-built houses: an error in the
  bottom-right, the screen goes black and you are disconnected to the main menu
  (a game bug since 42.21.0, still in 42.22.2; it happens when someone builds or tears
  something down nearby while that part of the map is loading).
- Mod vehicles with many moving parts (KI5, rSemiTruck and similar) cost frame time
  even when parked: their doors, hoods and windows are re-animated every frame.
  Parked vehicles no longer recompute an unchanged pose (the picture stays the same).
- Driving into a black area that has not loaded yet: the vehicle is suddenly thrown
  tens or hundreds of tiles away, into woods or water (a game bug since 42.21.0, still
  in 42.22.2). It is now pulled back into the loaded area right next to it, as the game
  intends.
- After a big fight the floor is covered in blood and the frame rate drops: each time a
  chunk is redrawn, the game scans the whole blood list of each of its 8 neighbours to
  find the few splats near the shared edge. The neighbours' splats are now looked up by
  which edge they are near (the picture stays the same).
If you have never seen these problems, you do not need this package.

INSTALL
1. Close the game.
2. Extract the whole zip into any folder, such as your Desktop or Downloads; it does
   not need to be in the game folder. Keep all the files together.
3. Double-click Install-Patches.bat. It finds the game folder through Steam by itself,
   even on another drive, and asks you to paste the path only if it cannot.
4. Type 1 and press Enter (install or update patches).
5. Type 1 and press Enter (client fixes).
6. When asked for the version, just press Enter: the recommended one is picked
   for your PC (its memory, and whether another mod already replaces game classes).
7. When asked to confirm, type Y and press Enter.
8. When it says it is done, press Enter, then type 0 and Enter to exit.
9. Start the game from Steam as usual. ProjectZomboid64.bat does not read
   ProjectZomboid64.json, so starting the game with it skips the patches.
The menus follow your Windows display language (Chinese or English). Press L in
the main menu to switch, or start it with: Install-Patches.bat -Lang en

GAME UPDATES
You no longer have to remove the patches before a game update. Every time the game
starts, the patches check projectzomboid.jar and switch themselves off when it is not
the exact version they were built for, so after an update the game simply runs without
them. A Steam update may also put back the original ProjectZomboid64.json, which drops
the launch option. When a package for the new game version is out, install it the same
way; the old one does not need to be removed first.
Do not delete the .mdc-patches folder by hand: while ProjectZomboid64.json still has the
launch option, the game does not start without that file (Java stops with "Error opening
zip file"). If that happened, run Uninstall-Patches.bat and type A, or let Steam verify
the game files, which puts back the original ProjectZomboid64.json.
To remove everything at any time: close the game, double-click Uninstall-Patches.bat in
this folder (deleted it? download the same zip again), type A and Enter, then Y and Enter.

UPGRADING FROM 0.2.x
Versions 0.2.x put class files directly into the game folder. Since 42.22 the game jar
is signed and the game throws a SecurityException as soon as it loads one of those
files. Installing this package removes them in the same step (the installer knows them
from its install record). To only remove them, run this package's Uninstall-Patches.bat
and type A.

OTHER MODS THAT REPLACE GAME CLASSES
Some mods, such as PZ_Optimization, put .class files into the game folder. Since 42.22
such files make the game throw a SecurityException on their own, with or without this
package; the installer warns when it finds them. When one of them replaces a class this
package patches, the installer stops with code 7 and changes nothing.
- Do not delete just the files it lists. That mod's other files still need them, and the
  game closes when you join a server.
- To keep that mod, install the compatible version (vehicle fixes only). The installer
  recommends it when another mod is in the way.
- For the full version, remove that mod with its own uninstaller first
  (PZ_Optimization: Uninstall-PZ-Optimization.cmd in the game folder), then install.
- With PZ_Optimization's "Warm-start cache (Java AOT)" option on, the game loads classes
  from PZ_Optimization's own jar first and the installer refuses and tells you why. The
  option takes effect at the next start: after turning it off, close the game, start it
  once more to the main menu and close it, then install.

SAFETY
- The installer only works on the exact game version it was built for: it checks the
  SHA-256 of your projectzomboid.jar and of the patch file before writing them, and the
  patch file checks projectzomboid.jar again at every game start.
- It only changes its own files (the .mdc-patches folder) and its own launch option in
  ProjectZomboid64.json; your other options and other mods' lines stay as they are.
  Unknown or modified files are never overwritten or deleted, and an interrupted install
  can be rolled back.
- Check your download: compare the SHA-256 of $zipName with SHA256SUMS.txt on the
  release page (PowerShell: Get-FileHash .\$zipName).
- Source code and technical details: $repoUrl

=====================================================================
Advanced

Modules
- Client fixes: the standard variant raises the texture wait limit to 4 GiB (use it with
  32 GB RAM or more); the low-memory variant keeps the vanilla 50 MiB limit. Choose one
  of these two or the compatible version below.
  Both include the texture leak fixes, a chunk-streaming log for black-edge reports
  (logging only), the player-built room disconnect fix (a chunk whose rooms were rebuilt
  while it was loading is re-bound to the new rooms, the same way the game updates chunks
  that are already loaded) and two vehicle animation speed-ups that keep the picture
  identical: an unchanged vehicle part pose is not recomputed (checked against a full
  recomputation now and then; any difference switches it off for the session), and a
  per-bone check that allocates in vanilla is answered directly when the model has no
  re-parented bones. Both also include the vehicle teleport fix: when a vehicle reaches a
  square that has not loaded, the game pulls it back into the chunk it is listed in, but
  that chunk may already have been unloaded and reused somewhere else; the vehicle is now
  pulled into the nearest adjacent loaded chunk instead, or, if no neighbour is loaded,
  left where it is (the game has already stopped it) until that area loads. Two related
  fixes for a vehicle left on an unloaded chunk with you aboard: the vehicle keeps
  updating (vanilla stops sending its position and the engine goes silent), and the
  server's old position no longer pulls the driver back when this client is driving it.
  Both also include the room table race fix: the map-loading thread and a rebuild of
  nearby player-built buildings no longer change the same room tables at the same time
  (vanilla can disconnect you with an error such as "isoRoom is null"). Both also include
  a floor blood speed-up that keeps the picture identical: drawing a chunk only looks at
  the splats of its neighbours that lie near the shared edge instead of scanning their
  whole lists (checked against a full scan now and then; any difference switches it off
  for the session).
  JVM flags -Dmdc.staleRoomHeal=off, -Dmdc.vehAnimSkip=off,
  -Dmdc.boneReparentFast=off, -Dmdc.vehicleClampGuard=off, -Dmdc.vehicleOrphanUpdate=off,
  -Dmdc.driverRecallGuard=off, -Dmdc.metaGridLock=off and -Dmdc.bloodSplatIndex=off turn
  them off; -Dmdc.agent=off turns the whole package off (add them to vmArgs in
  ProjectZomboid64.json).
- Client fixes, compatible version: only the fixes in classes that mods such as
  PZ_Optimization leave alone: the vehicle fixes (unchanged pose skip, teleport, vehicle
  left on an unloaded chunk, vehicle under the floor). The texture, room-disconnect,
  room-table, floor blood and chunk-streaming fixes are not in it. Choose it only when
  you use such a mod.
- DevProfiler: performance tool for mod developers; its interface is the separate mod
  MinidoracatDevProfilerFor42. "installed" means the files verified; "hook observed" means
  this game session actually reached the hook.
- The player-built room XL tree fix of earlier packages is gone: 42.22.2 fixed it in the
  game itself.

Boundaries
- Manages only our own patches; it is not compatible with third-party ZombieBuddy APIs and
  never removes other authors' tools.
- Changes ProjectZomboid64.json only by adding or removing its own -javaagent option. It
  does not change the game jar. Other Java agents (-javaagent, -agentlib, -agentpath)
  stay; if one of them changes a class this package patches first, this package leaves
  that class alone and prints [mdc-agent] NOT APPLIED in the console.
- The console shows [mdc-agent] ARMED when the patches are active and [mdc-agent]
  DISARMED (with the reason) when they switched themselves off.
- Old TexPipeline v3.0 packages (42.20.3/42.20.4) are recognized only when every SHA
  matches, even after a game update: installing replaces them, removing deletes them.
  Anything else must be removed with its own uninstaller first.
- Never remove patches while the game is running. If an install was interrupted, run the
  installer again before starting the game.
- Nothing is uploaded. Profiler captures stay in Zomboid/Lua/MinidoracatDevProfiler/captures/.
"@
$instructionsZh = @"
Minidoracat Client Patches $PACKAGE_VERSION / PZ $GAME_VERSION
(English: README-INSTALL.txt)

【這是什麼】
只改你自己電腦上的遊戲，修三個問題、減輕兩個效能問題。42.22 起遊戲 jar 有簽章，修補改由一個小小的
Java agent 在遊戲載入時套上：安裝器把 $AGENT_JAR_NAME 放進遊戲資料夾裡的 .mdc-patches 資料夾，
並在 ProjectZomboid64.json 加一條啟動參數（-javaagent:...）。projectzomboid.jar 本身不會被改。
- 隊友、殭屍、車輛看不到，只剩影子和名牌。
- 走進或開車進入有自建房的區域時，右下角跳錯、畫面一黑，被斷線送回主選單
  （42.21.0 起的官方 bug，42.22.2 仍在：那一帶地圖載入途中剛好有人在附近蓋或拆東西時發生）。
- KI5、rSemiTruck 這類門、引擎蓋、車窗會動的模組車，停著也每幀重算動畫，車多的地方會卡；
  停著沒變的車不再重算（畫面不變）。
- 開車開進還沒載入的黑色區域時，車突然被丟到幾十到幾百格外（樹林、水裡）（42.21.0 起的官方 bug，42.22.2 仍在）；
  改成照遊戲原意拉回旁邊已載入的地方。
- 大戰之後地上滿是血跡、畫面變卡：每重畫一個 chunk，遊戲就把 8 個鄰居的血跡清單整份掃一遍，
  只為了找出靠近交界的那幾筆；改成直接查靠那一邊的血跡（畫面不變）。
沒遇過這些問題的人可以不用裝。

【怎麼安裝】
1. 關閉遊戲。
2. 把整個壓縮檔解壓縮到任何資料夾都可以（例如桌面或下載），不用放進遊戲目錄；
   檔案要放在一起，不要只拉出其中一個。
3. 雙擊 Install-Patches.bat。程式會自己透過 Steam 找到遊戲目錄，遊戲裝在別的硬碟也找得到；
   找不到時才會請你貼上路徑。
4. 輸入 1 按 Enter（安裝或更新修補）。
5. 輸入 1 按 Enter（客戶端修復）。
6. 選版本時直接按 Enter，程式會依你的電腦自動選好。
7. 問「確定要安裝嗎？」時，輸入 Y 按 Enter。
8. 看到安裝完成後按 Enter，再輸入 0 按 Enter 關閉。
9. 照平常從 Steam 開遊戲。ProjectZomboid64.bat 不讀 ProjectZomboid64.json，用它開遊戲不會載入修補。
選單語言跟著 Windows 顯示語言；主選單按 L 可切換中文／英文。

【遊戲更新】
遊戲更新前不用再先移除修補。每次開遊戲時修補都會比對 projectzomboid.jar，不是建置時那一版就自己停用，
遊戲照原版跑；Steam 更新也可能把 ProjectZomboid64.json 換回原版，啟動參數就跟著不見。
等對應新版的修補包發布後，照上面的步驟安裝即可，不用先移除舊的。
不要自己刪掉 .mdc-patches 資料夾：ProjectZomboid64.json 還有啟動參數時，少了那個檔案遊戲會開不起來
（Java 顯示「Error opening zip file」）。已經發生的話，執行 Uninstall-Patches.bat 輸入 A，
或用 Steam 驗證遊戲檔案的完整性（會換回原版的 ProjectZomboid64.json）。
想全部移除時：關閉遊戲，雙擊這個資料夾裡的 Uninstall-Patches.bat（資料夾刪掉了就重新下載同一個 zip），
輸入 A 按 Enter，再輸入 Y 按 Enter 確認。

【從 0.2.x 升級】
0.2.x 把 class 檔直接放在遊戲資料夾。42.22 起遊戲 jar 有簽章，遊戲一載入這些檔案就丟 SecurityException。
安裝這一版時會一起把它們刪掉（安裝器認得自己的安裝紀錄）；只想移除的話，執行這包的 Uninstall-Patches.bat 輸入 A。

【和其他替換遊戲 class 的 MOD 一起用】
有些 MOD（例如 PZ_Optimization）會把 .class 檔放進遊戲資料夾。42.22 起這類檔案本身就會讓遊戲丟 SecurityException
（有沒有裝本包都一樣），安裝器看到時會提醒。它替換的 class 正好是本包要修補的時，安裝器會以 code 7 停止，不改任何檔案。
- 不要只刪除它列出的檔案。那個 MOD 的其他檔案還需要它們，刪掉後遊戲會在進服時直接關閉。
- 要保留那個 MOD，就裝相容版（只含車輛的修補）；有別的 MOD 擋著時，安裝器會建議你選它。
- 要完整版，先用那個 MOD 自己的卸載器把它移除（PZ_Optimization：遊戲資料夾裡的
  Uninstall-PZ-Optimization.cmd），再安裝本包。
- PZ_Optimization 的「Warm-start cache (Java AOT)」選項開著時，遊戲會先從它自己的 jar 載入 class，
  安裝器會說明原因並拒絕。選項下次啟動才生效：關掉後先關閉遊戲，再開一次遊戲到主選單後關閉，然後再安裝。

【以前裝過舊版】
- 裝過有 Uninstall-Patches.bat 的版本：直接照上面安裝即可，會自動換成新版。
- 裝過只有 uninstall.bat 的 TexPipeline v3.0（42.20.3／42.20.4）：一樣直接安裝，會自動換成新版；
  只想移除就執行這包的 Uninstall-Patches.bat 輸入 A。遊戲已經更新也適用。
- 更早的版本安裝器認不出來，會拒絕並提示：先執行舊包裡的 uninstall.bat，再裝這包。

【安全驗證】
- 安裝器只接受對應版本的遊戲：寫入前會比對 projectzomboid.jar 與修補檔的 SHA-256；修補檔每次開遊戲也會再比對一次 jar。
- 只動自己的檔案（.mdc-patches 資料夾）與 ProjectZomboid64.json 裡自己那一條啟動參數；你的其他設定與別的 MOD 加的參數照原樣保留。
  不明或變造的檔案不會被自動覆蓋或刪除，安裝中斷可以復原。
- 下載後可比對 $zipName 的 SHA-256 與發布頁的 SHA256SUMS.txt
  （PowerShell：Get-FileHash .\$zipName）。
- 原始碼與技術說明：$repoUrl

=====================================================================
以下是給開發者與進階使用者的說明，一般玩家不用看。

模組說明
- 客戶端修復：標準版把貼圖等待門檻放寬到 4 GiB（建議 32GB 以上 RAM）；
  省記憶體版保留原版 50 MiB 門檻。這兩版和下面的相容版三者擇一。另附黑邊時的串流紀錄（只記錄不改行為）。
  兩版都含自建房斷線修補：chunk 載入途中自建房被重建時，照遊戲更新已載入 chunk 的方式
  把格子重新綁到新房間，不再斷線。
  兩版也都含兩項車輛動畫加速，畫面與原版相同：零件姿勢沒變就不重算（不定期抽查一次完整重算，
  不一致就本次遊戲停用），以及模型沒有骨頭改掛時，每根骨頭的檢查不再配置物件。
  兩版都含開車瞬移修補：車開到還沒載入的格子時，遊戲會把車拉回它登記的 chunk，
  但那個 chunk 可能已經卸載並被重用到別處；改成拉進相鄰最近的已載入 chunk，相鄰都沒載入就留在原地（遊戲已經讓車停住）等載入。
  兩版也含兩項相關修補（車上有你、車卻被留在已卸載的 chunk 上時）：車照常更新（原版會停止送位置、引擎聲消失），
  以及你自己開的車不會再被伺服器的舊位置把駕駛拉回去。
  兩版也含房間表同時寫入修補：背景載入地圖和重建附近的自建建築不再同時改同一份房間表
  （原版會跳出「isoRoom is null」這類錯誤並斷線）。
  兩版也含地上血跡加速，畫面與原版相同：畫一個 chunk 時，鄰居的血跡只看靠交界那一邊的，不再整份掃過
  （不定期抽查一次整份掃描，不一致就本次遊戲停用）。
  JVM 參數 -Dmdc.staleRoomHeal=off、-Dmdc.vehAnimSkip=off、-Dmdc.boneReparentFast=off、
  -Dmdc.vehicleClampGuard=off、-Dmdc.vehicleOrphanUpdate=off、-Dmdc.driverRecallGuard=off、
  -Dmdc.metaGridLock=off、-Dmdc.bloodSplatIndex=off 可分別關閉；-Dmdc.agent=off 關掉整包
  （加在 ProjectZomboid64.json 的 vmArgs）。
- 客戶端修復・相容版：只含 PZ_Optimization 這類 MOD 不替換的 class 裡的修補：車輛修補
  （靜止姿勢跳過、開車瞬移、車留在已卸載的 chunk、車掉到地板下）；沒有貼圖、自建房斷線、房間表、地上血跡與串流紀錄這幾項。
  只在和這類 MOD 一起用時選它。
- DevProfiler：效能分析工具，介面需另在遊戲 MOD 管理器啟用 MinidoracatDevProfilerFor42。
  installed 只代表檔案驗證通過；hook observed 才代表本次 JVM 走到該掛點。
- 舊版的自建房間 XL 樹修補已移除：42.22.2 官方已修好。

安全邊界
- 只管理自家 patch，不相容第三方 ZombieBuddy API，也不移除其它作者的工具。
- 只在 ProjectZomboid64.json 加或拿掉自己那一條 -javaagent；不改遊戲 JAR。其他 Java agent（-javaagent、-agentlib、-agentpath）
  照樣保留；它們若先改了本包要修補的 class，本包就不換那個 class，console 印 [mdc-agent] NOT APPLIED。
- console 印 [mdc-agent] ARMED＝修補已啟用；[mdc-agent] DISARMED（附原因）＝修補自己停用、照原版跑。
- 舊 v3.0 包必須整組 SHA 完全吻合才會接管或移除（不受目前遊戲版本影響）；辨識不了時先用舊版 uninstaller。
- JVM 執行中不得移除修補。交易中斷時，先重跑管理器復原，再開遊戲。

資料僅保存本機：Zomboid/Lua/MinidoracatDevProfiler/captures/。
本包不含自動 log 上傳、正式服監控或玩家回報收集。
"@
Write-Utf8 "$pkg\README-INSTALL.txt" $instructionsEn
Write-Utf8 "$pkg\README-INSTALL.zh-TW.txt" $instructionsZh

Write-Host '[8/8] 打包（不發布、不安裝）'
$outDir = Join-Path $R 'output'
New-Item -ItemType Directory -Path $outDir -Force | Out-Null
$zip = Join-Path $outDir "MinidoracatClientPatches-$GAME_VERSION-$PACKAGE_VERSION.zip"
if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }
Compress-Archive -Path "$pkg\*" -DestinationPath $zip
Write-Host "完成：$zip"
Write-Host "未壓縮套件：$pkg"
