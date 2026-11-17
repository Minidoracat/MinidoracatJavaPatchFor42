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
$PACKAGE_VERSION = '0.3.6'
$DIST = Join-Path $R 'dist-client-modular'
$OUT = Join-Path $R 'work/out-client-modular'
$GEN = Join-Path $R 'work/gen-client-modular'
$AGENT_CLASSES = Join-Path $R 'work/agent-classes-client'
$AGENT_CHECK = Join-Path $R 'work/agentcheck-client'
$AGENT_JAR_NAME = 'MinidoracatClientPatches.jar'
$LOADER_NAME = 'mdc-agent-loader.dll'
$GCC = "$env:USERPROFILE\scoop\apps\mingw\current\bin\gcc.exe"
if (-not (Test-Path -LiteralPath $GCC)) { throw '找不到 MinGW-w64 gcc（agent 載入器是 C 寫的 DLL）' }
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
    @{ id='client-fixes-standard'; mode='client'; name='客戶端修復・標準版（記憶體 32GB 以上）'; nameEn='Client fixes - standard (32 GB RAM or more)'; version="v3.9($sourceRef)"; requires=@('core'); group='client-fixes' },
    @{ id='client-fixes-lowmem'; mode='client-lowmem'; name='客戶端修復・省記憶體版（記憶體 32GB 以下）'; nameEn='Client fixes - low memory (less than 32 GB RAM)'; version="v3.9-lowmem($sourceRef)"; requires=@('core'); group='client-fixes' },
    # 相容版：只裝 PZ_Optimization 這類 MOD 不替換的 class（PatchConfig.clientCompat），helper 只帶這幾刀用到的
    @{ id='client-fixes-compat'; mode='client-compat'; name='客戶端修復・相容版（只含車輛修補）'; nameEn='Client fixes - compatible (vehicle fixes only)'; version="v3.9-compat($sourceRef)"; requires=@('core'); group='client-fixes';
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
    # 相容版只有車輛的修補；貼圖、串流、W55、W57、W68、W84、W100 的 class 不在它的 view 裡，只在完整版測
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
        # W100／W101：on 與 off 各跑一次（off＝兩個 kill switch 實跑，沒有模型名稱的武器照原版丟 NPE＝原版負對照）
        foreach ($run in @(@('on', @()), @('off', @('-Dmdc.itemModelNullGuard=off', '-Dmdc.heldModelNullGuard=off')))) {
            $jvmArgs = @($run[1])
            & $JAVA --enable-native-access=ALL-UNNAMED @jvmArgs -cp $cp zombie.mdc.ItemModelNullGuardBehaviorTest $run[0]
            Assert-Ok "ItemModelNullGuardBehaviorTest $($run[0]) $id"
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
# helper 定義失敗（故障注入）：不啟用、要換的 class 以原版載入。client 包定義 helper 的期間沒有 class 被換上（實測還原 0 個）；
# 換上後再還原的路徑由 build.ps1 [5b] 的 restored 驗（伺服器包定義 helper 時會先換上父型別）
Invoke-AgentLoadCheck 'core,profiler,client-fixes-standard' @('core', 'profiler', 'client-fixes-standard') 'disarmed' -JvmArgs @('-Dmdc.agent.testFailDefine=1')
# 遊戲更新的情境：classpath 上的 projectzomboid.jar 不是建置基準（拿 bytes 不同的測試 jar 改名頂替）
$mismatchDir = Join-Path $R 'work/agent-mismatch-client'
New-Item -ItemType Directory -Path $mismatchDir -Force | Out-Null
Copy-Item -LiteralPath $TJ -Destination "$mismatchDir\projectzomboid.jar" -Force
Invoke-AgentLoadCheck 'core,client-fixes-standard' @('core', 'client-fixes-standard') 'disarmed' -GameJar "$mismatchDir\projectzomboid.jar"
# 參數錯：缺依賴（只選變體不選 core）、同一組選兩個變體——一律不啟用
Invoke-AgentLoadCheck 'client-fixes-standard' @('core', 'client-fixes-standard') 'disarmed'
Invoke-AgentLoadCheck 'core,client-fixes-standard,client-fixes-lowmem' @('core', 'client-fixes-standard') 'disarmed'

# agent 載入器。遊戲啟動器（ProjectZomboid64.exe）自己載入 jvm.dll，不經 java.exe：JVM 為 -javaagent 載入 instrument.dll 時
# 找不到同資料夾的 java.dll／jli.dll，JVM 起不來。上面的 AgentLoadCheck 都經 java.exe，測不到這件事；這裡用 launcher-probe
# 照啟動器的方式建 JVM（PATH 只留 Windows 自己的資料夾、工作目錄＝遊戲資料夾、參數用安裝器寫的相對路徑）。
# 載入器不連 C runtime：它自己不能再有「找不到相依 DLL」的問題。
$loaderDll = Join-Path $DIST $LOADER_NAME
& $GCC -shared -O2 -s -Wall -Wextra -Werror -nostdlib '-Wl,-e,DllMainCRTStartup' '-Wl,--no-insert-timestamp' -o $loaderDll "$R\patcher\agent-loader\mdc-agent-loader.c" -lkernel32
Assert-Ok 'gcc mdc-agent-loader.dll'
$probe = Join-Path $OUT 'launcher-probe.exe'
& $GCC -O2 -s -Wall -Werror -o $probe "$R\patcher\tests-agent\launcher-probe.c"
Assert-Ok 'gcc launcher-probe.exe'
$probeGame = Join-Path $R 'work/launcher-probe-game'
if (Test-Path -LiteralPath $probeGame) { Remove-Item -LiteralPath $probeGame -Recurse -Force }
New-Item -ItemType Directory -Path "$probeGame\.mdc-patches" -Force | Out-Null
Copy-Item -LiteralPath $agentJar, $loaderDll -Destination "$probeGame\.mdc-patches"
function Invoke-LauncherProbe([string]$AgentOption) {
    $savedPath = $env:PATH
    $env:PATH = "$env:SystemRoot\System32;$env:SystemRoot"
    Push-Location -LiteralPath $probeGame
    try { $text = (& $probe $JDK "-Djava.class.path=$AGENT_CHECK;$JAR" $AgentOption 2>&1 | Out-String) }
    finally { Pop-Location; $env:PATH = $savedPath }
    return [PSCustomObject]@{ Code = $LASTEXITCODE; Text = $text }
}
$probeModules = 'modules=core,client-fixes-standard'
# 原版前提：-javaagent 在啟動器的環境下 JVM 起不來。這條變紅＝JRE 或啟動器改了，先重估還需不需要載入器
$probed = Invoke-LauncherProbe "-javaagent:.mdc-patches/$AGENT_JAR_NAME=$probeModules"
if ($probed.Code -eq 0 -or $probed.Text -notmatch 'Could not find agent library instrument') {
    throw "原版前提不成立：啟動器方式下 -javaagent 沒有照預期失敗（exit=$($probed.Code)）：$($probed.Text)"
}
$probed = Invoke-LauncherProbe "-agentpath:.mdc-patches/$LOADER_NAME=.mdc-patches/$AGENT_JAR_NAME=$probeModules"
if ($probed.Code -ne 0 -or $probed.Text -notmatch '\[mdc-agent\] ARMED') {
    throw "agent 載入器在啟動器方式下沒有把修補帶起來（exit=$($probed.Code)）：$($probed.Text)"
}
Write-Host "launcher-probe OK：啟動器方式下 -javaagent 起不來（原版前提），經 $LOADER_NAME 則 ARMED"
# 找不到 agent jar：instrument.dll 自己回報錯誤、JVM 不啟動（和 -javaagent 指到不存在的 jar 相同），載入器不吞
$probed = Invoke-LauncherProbe "-agentpath:.mdc-patches/$LOADER_NAME=.mdc-patches/no-such.jar=$probeModules"
if ($probed.Code -eq 0 -or $probed.Text -notmatch 'JAR manifest missing') {
    throw "agent jar 不存在時應由 instrument.dll 報錯（exit=$($probed.Code)）：$($probed.Text)"
}
# 啟動參數少了「=<agent jar>」：JVM 交來的 options 是 NULL，載入器要自己擋下（instrument.dll 會直接讀它而當掉）
$probed = Invoke-LauncherProbe "-agentpath:.mdc-patches/$LOADER_NAME"
if ($probed.Code -ne 0 -or $probed.Text -notmatch 'DISARMED: agent loader: the launch option has no agent jar') {
    throw "啟動參數沒有 agent jar 時載入器應印 DISARMED 並照原版啟動（exit=$($probed.Code)）：$($probed.Text)"
}

Write-Host '[7/8] 產生管理器套件與 manifest'
$pkg = Join-Path $DIST 'pkg'
New-Item -ItemType Directory -Path $pkg -Force | Out-Null
Copy-Item -LiteralPath $agentJar, $loaderDll -Destination $pkg
foreach ($name in @('Manage-Patches.ps1', 'Install-Patches.bat', 'Uninstall-Patches.bat')) {
    Copy-Item -LiteralPath "$R\deploy-client\$name" -Destination $pkg
}
$manifest = [ordered]@{
    schemaVersion=2; gameVersion=$GAME_VERSION; packageVersion=$PACKAGE_VERSION
    jarSha256=$jarSha; built=$built; sourceRef=$sourceRef
    agent=[ordered]@{ source=$AGENT_JAR_NAME; sha256=(Get-FileHash -LiteralPath $agentJar -Algorithm SHA256).Hash.ToLowerInvariant() }
    loader=[ordered]@{ source=$LOADER_NAME; sha256=(Get-FileHash -LiteralPath $loaderDll -Algorithm SHA256).Hash.ToLowerInvariant() }
    modules=$modules
    legacyPackages=@(Get-Content -LiteralPath "$R\deploy-client\legacy-packages.json" -Raw | ConvertFrom-Json)
}
Write-Utf8 "$pkg\manifest.json" (($manifest | ConvertTo-Json -Depth 12) + "`n")
$repoUrl = 'https://github.com/Minidoracat/MinidoracatJavaPatchFor42'
$zipName = "MinidoracatClientPatches-$GAME_VERSION-$PACKAGE_VERSION.zip"
# 修補清單與模組說明 Windows、macOS 兩份 README 共用
$fixListEn = @"
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
"@
$modulesEn = @"
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
"@
$fixListZh = @"
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
"@
$modulesZh = @"
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
"@
$instructionsEn = @"
Minidoracat Client Patches $PACKAGE_VERSION / Project Zomboid $GAME_VERSION
(繁體中文說明：README-INSTALL.zh-TW.txt)

WHAT THIS IS
Unofficial client-side fixes that only change the game on your own PC. Since 42.22 the
game jar is signed, so the fixes are applied by a small Java agent while the game loads:
the installer copies $AGENT_JAR_NAME and a small loader ($LOADER_NAME) into the
.mdc-patches folder inside the game folder and adds one launch option (-agentpath:...)
to ProjectZomboid64.json.
projectzomboid.jar itself is not modified.
$fixListEn

INSTALL WITHOUT THIS ZIP (one line in PowerShell)
Close the game, open PowerShell (press the Windows key, type PowerShell, press Enter),
paste this line and press Enter. It downloads the latest package, checks its SHA-256 and
opens the same menu as Install-Patches.bat; continue from step 4 below. To remove
everything or see the status later, paste the same line again.
    irm https://github.com/Minidoracat/MinidoracatJavaPatchFor42/releases/latest/download/install-windows.ps1 | iex

INSTALL WITH THIS ZIP
1. Close the game.
2. Extract the whole zip into any folder, such as your Desktop or Downloads; it does
   not need to be in the game folder. Keep all the files together.
3. Double-click Install-Patches.bat. It finds the game folder through Steam by itself,
   even on another drive, and asks you to paste the path only if it cannot.
4. Type 1 and press Enter (install or update patches).
5. Type 1 and press Enter (client fixes).
6. When asked for the version, just press Enter: the recommended one is picked
   for your PC (its memory, and whether another mod already replaces game classes).
7. If it asks about the game's memory limit, press Enter to raise it (see MEMORY below).
8. When asked to confirm, type Y and press Enter.
9. When it says it is done, press Enter, then type 0 and Enter to exit.
10. Start the game from Steam as usual. ProjectZomboid64.bat does not read
    ProjectZomboid64.json, so starting the game with it skips the patches.
The menus follow your Windows display language or regional format (Chinese or English).
Press L in the main menu to switch, or start it with: Install-Patches.bat -Lang en

MEMORY
The game starts with a 3 GB Java memory limit (-Xmx3072m in ProjectZomboid64.json). On a
server with many mods that may not be enough: the game gets laggy, and in bad cases it
closes (OutOfMemoryError in the console). When the limit is lower than recommended for
your PC, the installer asks whether to raise it: 8 GB on a PC with 32 GB of RAM or more,
6 GB with 16 GB or more, 4 GB with 12 GB or more (the same values as the macOS version;
only 8 GB was tested in the real game, on macOS). It is the game's own setting: removing the
patches keeps it, and a Steam update or "Verify integrity of game files" puts 3 GB back
(installing again asks again). If you put -Xmx in the game's Steam launch options
yourself, that value wins over ProjectZomboid64.json.

GAME UPDATES
You no longer have to remove the patches before a game update. Every time the game
starts, the patches check projectzomboid.jar and switch themselves off when it is not
the exact version they were built for, so after an update the game simply runs without
them. A Steam update may also put back the original ProjectZomboid64.json, which drops
the launch option. When a package for the new game version is out, install it the same
way; the old one does not need to be removed first.
Do not delete the .mdc-patches folder by hand: while ProjectZomboid64.json still has the
launch option, the game does not start without that file (Java stops with "Error opening
zip file"). If that happened, remove everything (below), or let Steam verify the game
files, which puts back the original ProjectZomboid64.json.
To remove everything at any time: close the game, double-click Uninstall-Patches.bat in
this folder, type A and Enter, then Y and Enter. Deleted the folder? Paste the PowerShell
line from the top of this file and choose 3 (any newer package can remove an older install).

WINDOWS SMART APP CONTROL
The patches load through a small DLL (mdc-agent-loader.dll) that is not code-signed. On a
PC where Windows 11 Smart App Control is enforcing ("On"), Windows blocks that DLL and the
game does not start at all. The installer checks this and refuses to install there (code
11); nothing is changed. If Smart App Control became enforcing after you installed (it can
switch from evaluation by itself) and the game stopped starting, remove everything (see
GAME UPDATES). We do not ask you to change that Windows setting.

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
- It only changes its own files (the .mdc-patches folder), its own launch option in
  ProjectZomboid64.json and, when you agree, the -Xmx memory limit (removing the patches
  keeps it); your other options and other mods' lines stay as they are.
  Unknown or modified files are never overwritten or deleted, and an interrupted install
  can be rolled back.
- Check your download: compare the SHA-256 of $zipName with SHA256SUMS.txt on the
  release page (PowerShell: Get-FileHash .\$zipName).
- The PowerShell one-line command runs install-windows.ps1 from this project's GitHub
  release page, so using it means trusting this repo and GitHub; its SHA-256 check only
  catches a broken download or mismatched versions. To read it first: irm <that URL>
- Source code and technical details: $repoUrl

=====================================================================
Advanced

Modules
$modulesEn

Boundaries
- Manages only our own patches; it is not compatible with third-party ZombieBuddy APIs and
  never removes other authors' tools.
- $LOADER_NAME is a small helper (a few KB) that the game needs to load a Java agent:
  the game's launcher cannot use -javaagent directly, because the Java runtime then fails
  to load its own agent support. Its source code is in the repository
  (patcher/agent-loader/mdc-agent-loader.c).
- Changes ProjectZomboid64.json only by adding or removing its own -agentpath option (it
  goes first in vmArgs, so this package starts before other agents) and, when you agree,
  changing the -Xmx value. It does not change the game jar. Other Java agents (-javaagent,
  -agentlib, -agentpath) stay, but changing the same class as another agent is not
  supported: a class that was changed before this
  package sees it is skipped ([mdc-agent] NOT APPLIED in the console), and changes another
  agent makes after this package cannot be detected. If a class to patch is already loaded
  when this package starts, the whole package stays off ([mdc-agent] DISARMED).
- The console shows [mdc-agent] ARMED when the patches are active and [mdc-agent]
  DISARMED (with the reason) when they switched themselves off.
- Old TexPipeline v3.0 packages (42.20.3/42.20.4) are recognized only when every SHA
  matches, even after a game update: installing replaces them, removing deletes them.
  Anything else must be removed with its own uninstaller first.
- Command line: Install-Patches.bat -Action install -MemoryMb N (N at least 3072) sets
  the memory limit to N MB without asking; it stops with code 7 and changes nothing when
  ProjectZomboid64.json has no single -Xmx it can change.
- Never remove patches while the game is running. If an install was interrupted, run the
  installer again before starting the game.
- Nothing is uploaded. Profiler captures stay in Zomboid/Lua/MinidoracatDevProfiler/captures/.
"@
$instructionsZh = @"
Minidoracat Client Patches $PACKAGE_VERSION / PZ $GAME_VERSION
(English: README-INSTALL.txt)

【這是什麼】
只改你自己電腦上的遊戲，修三個問題、減輕兩個效能問題。42.22 起遊戲 jar 有簽章，修補改由一個小小的
Java agent 在遊戲載入時套上：安裝器把 $AGENT_JAR_NAME 和一個小小的載入器（$LOADER_NAME）放進遊戲資料夾裡的
.mdc-patches 資料夾，並在 ProjectZomboid64.json 加一條啟動參數（-agentpath:...）。projectzomboid.jar 本身不會被改。
$fixListZh

【不下載 zip：在 PowerShell 貼一行】
關閉遊戲，打開 PowerShell（按 Windows 鍵，輸入 PowerShell，按 Enter），貼上這一行按 Enter。
它會下載最新的安裝包、比對 SHA-256，再開和 Install-Patches.bat 一樣的選單；接著照下面第 4 步做。
之後要全部移除或看狀態，再貼一次同一行就好。
    irm https://github.com/Minidoracat/MinidoracatJavaPatchFor42/releases/latest/download/install-windows.ps1 | iex

【用這個 zip 安裝】
1. 關閉遊戲。
2. 把整個壓縮檔解壓縮到任何資料夾都可以（例如桌面或下載），不用放進遊戲目錄；
   檔案要放在一起，不要只拉出其中一個。
3. 雙擊 Install-Patches.bat。程式會自己透過 Steam 找到遊戲目錄，遊戲裝在別的硬碟也找得到；
   找不到時才會請你貼上路徑。
4. 輸入 1 按 Enter（安裝或更新修補）。
5. 輸入 1 按 Enter（客戶端修復）。
6. 選版本時直接按 Enter，程式會依你的電腦自動選好。
7. 問到遊戲記憶體上限時，直接按 Enter 就會調到建議值（見下面【記憶體】）。
8. 問「確定要安裝嗎？」時，輸入 Y 按 Enter。
9. 看到安裝完成後按 Enter，再輸入 0 按 Enter 關閉。
10. 照平常從 Steam 開遊戲。ProjectZomboid64.bat 不讀 ProjectZomboid64.json，用它開遊戲不會載入修補。
選單語言跟著 Windows 的顯示語言或地區格式；主選單按 L 可切換中文／英文。

【記憶體】
遊戲預設最多只用 3 GB Java 記憶體（ProjectZomboid64.json 的 -Xmx3072m），MOD 多的伺服器可能不夠用：
不夠時遊戲會變卡，嚴重時閃退（console 出現 OutOfMemoryError）。目前上限低於這台電腦的建議值時，安裝器會問要不要調高：
記憶體 32 GB 以上建議 8 GB、16 GB 以上建議 6 GB、12 GB 以上建議 4 GB（和 macOS 版相同；只有 8 GB 實測過，而且是在 macOS 版）。
這是遊戲本身的設定：移除修補時不會改回；Steam 更新或「驗證遊戲檔案的完整性」會回到 3 GB，到時再安裝一次會再問。
你自己在 Steam 的遊戲啟動選項寫了 -Xmx 的話，以啟動選項為準。

【遊戲更新】
遊戲更新前不用再先移除修補。每次開遊戲時修補都會比對 projectzomboid.jar，不是建置時那一版就自己停用，
遊戲照原版跑；Steam 更新也可能把 ProjectZomboid64.json 換回原版，啟動參數就跟著不見。
等對應新版的修補包發布後，照上面的步驟安裝即可，不用先移除舊的。
不要自己刪掉 .mdc-patches 資料夾：ProjectZomboid64.json 還有啟動參數時，少了那個檔案遊戲會開不起來
（Java 顯示「Error opening zip file」）。已經發生的話，照下面全部移除，
或用 Steam 驗證遊戲檔案的完整性（會換回原版的 ProjectZomboid64.json）。
想全部移除時：關閉遊戲，雙擊這個資料夾裡的 Uninstall-Patches.bat，輸入 A 按 Enter，再輸入 Y 按 Enter 確認。
資料夾刪掉了，就貼這份說明開頭的 PowerShell 那一行，選 3（比較新的安裝包也能移除舊版）。

【Windows 智慧型應用程式控制】
修補是靠一個沒有程式碼簽章的小 DLL（mdc-agent-loader.dll）載入的。Windows 11 的「智慧型應用程式控制」
在強制模式（「開啟」）的電腦上，Windows 會擋下這個 DLL，遊戲完全開不起來。安裝器會先檢查，
在這種電腦上不安裝（代碼 11），也不變更任何檔案。安裝之後它才變成強制模式（評估模式會自己轉）、
遊戲開不起來時，照【遊戲更新】最後一段全部移除。我們不會要你去改那個 Windows 設定。

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
- 只動自己的檔案（.mdc-patches 資料夾）、ProjectZomboid64.json 裡自己那一條啟動參數，以及你同意時的 -Xmx 記憶體上限
  （移除修補時不改回）；你的其他設定與別的 MOD 加的參數照原樣保留。
  不明或變造的檔案不會被自動覆蓋或刪除，安裝中斷可以復原。
- 下載後可比對 $zipName 的 SHA-256 與發布頁的 SHA256SUMS.txt
  （PowerShell：Get-FileHash .\$zipName）。
- PowerShell 一行指令執行的是本專案 GitHub 發布頁上的 install-windows.ps1，用它就是信任這個 repo 和 GitHub；
  它比對 SHA-256 只能擋下載損壞或版本不同步。想先看內容：irm <那個網址>
- 原始碼與技術說明：$repoUrl

=====================================================================
以下是給開發者與進階使用者的說明，一般玩家不用看。

模組說明
$modulesZh

安全邊界
- 只管理自家 patch，不相容第三方 ZombieBuddy API，也不移除其它作者的工具。
- $LOADER_NAME 是讓遊戲載入 Java agent 用的小工具（幾 KB）：遊戲的啟動器不能直接用 -javaagent
  （Java 執行環境會載入不了自己的 agent 支援）。原始碼在 repo 的 patcher/agent-loader/mdc-agent-loader.c。
- 只在 ProjectZomboid64.json 加或拿掉自己那一條 -agentpath（排在 vmArgs 最前面，比其他 agent 先啟動），以及你同意時改 -Xmx 的值；不改遊戲 JAR。
  其他 Java agent（-javaagent、-agentlib、-agentpath）照樣保留，但不支援和它們修改同一個 class：在本包之前就被改過的 class
  不換（console 印 [mdc-agent] NOT APPLIED）；別的 agent 在本包之後才改的，本包偵測不到。本包啟動時要修補的 class 已經被載入，
  就整包不啟用（印 [mdc-agent] DISARMED）。
- console 印 [mdc-agent] ARMED＝修補已啟用；[mdc-agent] DISARMED（附原因）＝修補自己停用、照原版跑。
- 舊 v3.0 包必須整組 SHA 完全吻合才會接管或移除（不受目前遊戲版本影響）；辨識不了時先用舊版 uninstaller。
- JVM 執行中不得移除修補。交易中斷時，先重跑管理器復原，再開遊戲。
- 命令列：Install-Patches.bat -Action install -MemoryMb N（N 至少 3072）不詢問、直接把記憶體上限設成 N MB；
  ProjectZomboid64.json 沒有剛好一條可以改的 -Xmx 時以代碼 7 停止，不改任何檔案。

資料僅保存本機：Zomboid/Lua/MinidoracatDevProfiler/captures/。
本包不含自動 log 上傳、正式服監控或玩家回報收集。
"@
Write-Utf8 "$pkg\README-INSTALL.txt" $instructionsEn
Write-Utf8 "$pkg\README-INSTALL.zh-TW.txt" $instructionsZh

# ---------- macOS 套件：同一顆 agent jar，安裝器改用 Java（mdc-mac-installer.jar）＋ Install-Patches.command。
# Mac 的啟動器（JavaAppLauncher）經 JLI_Launch 建 JVM，-javaagent 直接可用，不需要 $LOADER_NAME（實機 42.22.2 驗過）。
$BASH = 'C:\Program Files\Git\bin\bash.exe'
if (-not (Test-Path -LiteralPath $BASH)) { throw "找不到 Git Bash（$BASH）：macOS 啟動腳本要用 bash 驗證" }
$macClasses = Join-Path $R 'work/mac-installer-classes'
$macTestClasses = Join-Path $R 'work/mac-installer-test-classes'
foreach ($d in @($macClasses, $macTestClasses)) {
    if (Test-Path -LiteralPath $d) { Remove-Item -LiteralPath $d -Recurse -Force }
    New-Item -ItemType Directory -Path $d | Out-Null
}
# 遊戲自帶 JRE 是 25；--release 17 只是讓安裝器不依賴新 API
& $JAVAC -encoding UTF-8 --release 17 '-Xlint:all,-serial' -Werror -d $macClasses @((Get-ChildItem "$R\patcher\installer-mac" -Filter '*.java').FullName)
Assert-Ok 'javac installer-mac'
$macInstallerJar = Join-Path $DIST 'mdc-mac-installer.jar'
if (Test-Path -LiteralPath $macInstallerJar) { Remove-Item -LiteralPath $macInstallerJar -Force }
& (Join-Path $JDK 'bin/jar.exe') --create --file $macInstallerJar --date=2000-01-01T00:00:00Z --main-class MacInstaller -C $macClasses .
Assert-Ok 'jar mdc-mac-installer.jar'
& $JAVAC -encoding UTF-8 --release 17 -cp $macClasses -d $macTestClasses @((Get-ChildItem "$R\patcher\tests-installer-mac" -Filter '*.java').FullName)
Assert-Ok 'javac tests-installer-mac'
& $JAVA -cp "$macClasses;$macTestClasses" MacInstallerTest (Join-Path $R 'work/mac-installer-test')
Assert-Ok 'MacInstallerTest'

$macCommand = "$R\deploy-client\mac\Install-Patches.command"
if ([System.IO.File]::ReadAllText($macCommand).Contains("`r")) { throw 'Install-Patches.command 有 CR：macOS 的 bash 會把它當成指令的一部分（檢查 .gitattributes 的 eol=lf）' }
& $BASH -n $macCommand
Assert-Ok 'bash -n Install-Patches.command'

$pkgMac = Join-Path $DIST 'pkg-mac'
if (Test-Path -LiteralPath $pkgMac) { Remove-Item -LiteralPath $pkgMac -Recurse -Force }
New-Item -ItemType Directory -Path $pkgMac | Out-Null
Copy-Item -LiteralPath $agentJar, $macInstallerJar, $macCommand -Destination $pkgMac
$macManifest = [ordered]@{}
foreach ($k in $manifest.Keys) { if ($k -notin @('loader', 'legacyPackages')) { $macManifest[$k] = $manifest[$k] } }
Write-Utf8 "$pkgMac\manifest.json" (($macManifest | ConvertTo-Json -Depth 12) + "`n")
if ((Get-FileHash -LiteralPath "$pkgMac\$AGENT_JAR_NAME" -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath "$pkg\$AGENT_JAR_NAME" -Algorithm SHA256).Hash) {
    throw 'Windows 與 macOS 套件的 agent jar 不同'
}
$zipNameMac = "MinidoracatClientPatches-$GAME_VERSION-$PACKAGE_VERSION-macOS.zip"
# 一行指令的網址每一版都相同（Release 資產 install-mac.sh 一定要跟著每個 client 版上傳）
$macOneLineUrl = 'https://github.com/Minidoracat/MinidoracatJavaPatchFor42/releases/latest/download/install-mac.sh'
# 本檔在 Windows checkout 是 CRLF，here-string 的換行跟著是 CRLF（Write-Utf8 寫出時才轉 LF）
$modulesMacEn = $modulesEn -replace '\(add them to vmArgs in(\r?\n)  ProjectZomboid64\.json\)', '(add them to JVMOptions in$1  Project Zomboid.app/Contents/Info.plist)'
$modulesMacZh = $modulesZh.Replace('（加在 ProjectZomboid64.json 的 vmArgs）', '（加在 Project Zomboid.app/Contents/Info.plist 的 JVMOptions）')
if ($modulesMacEn -eq $modulesEn -or $modulesMacZh -eq $modulesZh) { throw 'macOS README：JVM 參數位置的替換沒有命中（模組說明的文字改了？）' }
$instructionsMacEn = @"
Minidoracat Client Patches $PACKAGE_VERSION for macOS / Project Zomboid $GAME_VERSION
(繁體中文說明：README-INSTALL.zh-TW.txt)

WHAT THIS IS
Unofficial client-side fixes that only change the game on your own Mac. Since 42.22 the
game jar is signed, so the fixes are applied by a small Java agent while the game loads:
the installer copies $AGENT_JAR_NAME into Project Zomboid.app/Contents/Java/.mdc-patches
and adds one launch option (-javaagent:...) to Project Zomboid.app/Contents/Info.plist.
projectzomboid.jar itself is not modified.
$fixListEn
Tested on an Apple silicon Mac, natively and as the Intel (x86_64) build under Rosetta 2.
Intel Macs use the same steps but have not been tested on real Intel hardware yet.

INSTALL (easiest: one line in Terminal)
1. Quit the game.
2. Open Terminal (Applications > Utilities > Terminal).
3. Paste this line and press Return:
   /bin/bash -c "`$(curl -fsSL $macOneLineUrl)"
   It downloads the newest package, checks its SHA-256, finds the game through Steam and
   opens the installer's menu. The menu follows your Mac's language (Chinese or
   English); press L to switch.
4. Type 1 and press Return (install or update patches), then 1 again (client fixes).
5. When asked for the version, just press Return: the recommended one is picked.
6. If it asks about the game's memory limit, press Return to raise it (see MEMORY below).
7. When asked to confirm, type Y and press Return; when it is done, press Return, then
   type 0 and Return to exit.
8. Start the game from Steam as usual (both "Play Project Zomboid" and "Compatibility
   Mode" load the patches).
If you only see a line starting with "curl:", the download did not work: check your
connection and paste the line again. To remove the patches or see the status later,
paste the same line again.

INSTALL WITH THIS ZIP (without pasting a command)
1. Quit the game.
2. Double-click the zip to extract it. Keep all the files together.
3. Open Terminal (Applications > Utilities > Terminal).
4. Type bash and a space, drag Install-Patches.command from the extracted folder into the
   Terminal window, then press Return. (Double-clicking Install-Patches.command does not
   work: macOS blocks scripts that are not notarized.)
   The installer finds the game through Steam by itself and asks you to drag
   Project Zomboid.app into the window only if it cannot.
5. Type 1 and press Return (install or update patches).
6. Type 1 and press Return (client fixes).
7. When asked for the version, just press Return: the recommended one is picked for your
   Mac (its memory, and whether another mod already replaces game classes).
8. If it asks about the game's memory limit, press Return to raise it (see MEMORY below).
9. When asked to confirm, type Y and press Return.
10. When it says it is done, press Return, then type 0 and Return to exit.
11. Start the game from Steam as usual.
If macOS asks whether Terminal may access the Downloads folder or change other apps,
allow it: the installer has to read the extracted files and write into
Project Zomboid.app. The menus follow your Mac's language (Chinese or English);
press L in the main menu to switch.

MEMORY
The game starts with a 3 GB Java memory limit (-Xmx3072m in Info.plist). On a server with
many mods that is not enough: the game closes a few minutes after joining
(OutOfMemoryError in Zomboid/console.txt). When the limit is lower than recommended for
your Mac, the installer asks whether to raise it: 8 GB on a Mac with 32 GB of RAM or more,
6 GB with 16 GB or more, 4 GB with 12 GB or more (only 8 GB was tested in the real game).
It is the game's own setting: removing the patches keeps it, and a Steam update or
"Verify integrity of game files" puts 3 GB back (installing again asks again).

GAME UPDATES
You do not have to remove the patches before a game update. Every time the game starts,
the patches check projectzomboid.jar and switch themselves off when it is not the exact
version they were built for, so after an update the game simply runs without them. A
Steam update or "Verify integrity of game files" may also put back the original
Info.plist, which drops the launch option (the installer's status then says "installed
but not active"; installing again puts it back). When a package for the new game version
is out, install it the same way; the old one does not need to be removed first.
Do not delete the .mdc-patches folder by hand: while Info.plist still has the launch
option, the game does not start without that file. If that happened, run the installer
and choose 3 (remove everything), or let Steam verify the game files.
To remove everything at any time: quit the game, paste the one-line command again (or run
Install-Patches.command the same way as above), type 3 and Return, then Y and Return.

OTHER MODS THAT REPLACE GAME CLASSES
Some mods put .class files into the game. Since 42.22 such files make the game throw a
SecurityException on their own, with or without this package; the installer warns when
it finds them. When one of them replaces a class this package patches, the installer
stops with code 7 and changes nothing. Do not delete just the files it lists: that mod's
other files still need them. To keep that mod, install the compatible version (vehicle
fixes only); the installer recommends it when another mod is in the way.

SAFETY
- The installer only works on the exact game version it was built for: it checks the
  SHA-256 of your projectzomboid.jar and of the patch file before writing them, and the
  patch file checks projectzomboid.jar again at every game start.
- It only changes its own files (the .mdc-patches folder), its own launch option in
  Info.plist and, when you agree, the -Xmx memory limit; the rest of Info.plist stays
  byte for byte as it was, and removing everything gives back the original file (with
  the memory limit you chose). Unknown or modified files are never overwritten or
  deleted. If an install or removal is interrupted, run it again.
- The one-line command runs install-mac.sh from this project's GitHub release page, so
  using it means trusting this repository and GitHub. Its SHA-256 check catches broken or
  mismatched downloads, not a compromised repository. To read the script first, run
  curl -fsSL $macOneLineUrl | less, or use this zip instead.
- Check your download: compare the SHA-256 of $zipNameMac with SHA256SUMS.txt on the
  release page (Terminal: shasum -a 256 $zipNameMac).
- Source code and technical details: $repoUrl

=====================================================================
Advanced

Modules
$modulesMacEn

Boundaries
- Manages only our own patches; it never removes other authors' tools.
- The installer (mdc-mac-installer.jar) runs with the Java that comes with the game.
  Command line: bash Install-Patches.command [menu|status|install|quick|uninstall]
  [--modules id,...] [--all] [--non-interactive] [--memory-mb N] [--lang zh|en]. The
  one-line command opens the menu; words after it go to the installer the same way
  (/bin/bash -c "`$(curl ...)" status). Set PZ_APP to the path of Project Zomboid.app when the
  game is not in a Steam library.
- Changes Info.plist only by adding or removing its own -javaagent option and, when you agree, the -Xmx value (the option goes first
  in JVMOptions, so this package starts before other agents). Other Java agents
  (-javaagent, -agentlib, -agentpath) stay, but changing the same class as another agent is
  not supported: a class that was changed before this package sees it is skipped
  ([mdc-agent] NOT APPLIED in the console), and changes another agent makes after this
  package cannot be detected.
- The console (Zomboid/console.txt) shows [mdc-agent] ARMED when the patches are active
  and [mdc-agent] DISARMED (with the reason) when they switched themselves off.
- Never remove patches while the game is running; the installer refuses (code 4).
- Nothing is uploaded. Profiler captures stay in Zomboid/Lua/MinidoracatDevProfiler/captures/.
"@
$instructionsMacZh = @"
Minidoracat Client Patches $PACKAGE_VERSION macOS 版 / PZ $GAME_VERSION
(English: README-INSTALL.txt)

【這是什麼】
只改你自己 Mac 上的遊戲，修三個問題、減輕兩個效能問題。42.22 起遊戲 jar 有簽章，修補改由一個小小的
Java agent 在遊戲載入時套上：安裝器把 $AGENT_JAR_NAME 放進 Project Zomboid.app/Contents/Java/.mdc-patches，
並在 Project Zomboid.app/Contents/Info.plist 加一條啟動參數（-javaagent:...）。projectzomboid.jar 本身不會被改。
$fixListZh
在 Apple 晶片的 Mac 上測試過（原生，以及用 Rosetta 2 跑 Intel（x86_64）版）；Intel Mac 步驟相同，但還沒有在 Intel 實機上測。

【怎麼安裝（最簡單：在終端機貼一行）】
1. 結束遊戲。
2. 打開「終端機」（應用程式 → 工具程式 → 終端機）。
3. 貼上這一行，按 Return：
   /bin/bash -c "`$(curl -fsSL $macOneLineUrl)"
   它會下載最新的安裝包、比對 SHA-256、透過 Steam 找到遊戲，再開安裝器的選單。
   選單跟著 Mac 的語言；按 L 可切換中文／英文。
4. 輸入 1 按 Return（安裝或更新修補），再輸入 1 按 Return（客戶端修復）。
5. 選版本時直接按 Return，程式會依你的 Mac 自動選好。
6. 問到遊戲記憶體上限時，直接按 Return 就會調到建議值（見下面【記憶體】）。
7. 問「確定要安裝嗎？」時，輸入 Y 按 Return；看到安裝完成後按 Return，再輸入 0 按 Return 離開。
8. 照平常從 Steam 開遊戲（「執行 Project Zomboid」和「Compatibility Mode」都會載入修補）。
只看到一行 curl: 開頭的英文，就是沒下載成功：檢查網路後再貼一次。之後要移除或看狀態，再貼一次同一行就好。

【用這個 zip 安裝（不想貼指令時）】
1. 結束遊戲。
2. 雙擊 zip 解壓縮。檔案要放在一起，不要只拉出其中一個。
3. 打開「終端機」（應用程式 → 工具程式 → 終端機）。
4. 輸入 bash 和一個空格，把解壓出來的 Install-Patches.command 拖進終端機視窗，按 Return。
   （直接雙擊 Install-Patches.command 不行：macOS 會擋下沒有經過公證的腳本。）
   程式會自己透過 Steam 找到遊戲；找不到時才會請你把 Project Zomboid.app 拖進視窗。
5. 輸入 1 按 Return（安裝或更新修補）。
6. 輸入 1 按 Return（客戶端修復）。
7. 選版本時直接按 Return，程式會依你的 Mac 自動選好。
8. 問到遊戲記憶體上限時，直接按 Return 就會調到建議值（見下面【記憶體】）。
9. 問「確定要安裝嗎？」時，輸入 Y 按 Return。
10. 看到安裝完成後按 Return，再輸入 0 按 Return 離開。
11. 照平常從 Steam 開遊戲。
macOS 問「終端機」能不能取用「下載項目」或修改其他 App 時請允許：安裝器要讀解壓出來的檔案、寫進 Project Zomboid.app。
選單語言跟著 Mac 的語言；主選單按 L 可切換中文／英文。

【記憶體】
遊戲預設最多只用 3 GB Java 記憶體（Info.plist 的 -Xmx3072m），MOD 多的伺服器不夠用，連線幾分鐘後會閃退
（Zomboid/console.txt 出現 OutOfMemoryError）。目前上限低於這台 Mac 的建議值時，安裝器會問要不要調高：
記憶體 32 GB 以上建議 8 GB、16 GB 以上建議 6 GB、12 GB 以上建議 4 GB（只有 8 GB 在遊戲裡實測過）。
這是遊戲本身的設定：移除修補時不會改回；Steam 更新或「驗證遊戲檔案的完整性」會回到 3 GB，到時再安裝一次會再問。

【遊戲更新】
遊戲更新前不用先移除修補。每次開遊戲時修補都會比對 projectzomboid.jar，不是建置時那一版就自己停用，遊戲照原版跑。
Steam 更新或「驗證遊戲檔案的完整性」也可能把 Info.plist 換回原版，啟動參數就跟著不見
（這時安裝器的狀態會顯示「已安裝但沒有啟用」，再安裝一次就會補回去）。
等對應新版的修補包發布後，照上面的步驟安裝即可，不用先移除舊的。
不要自己刪掉 .mdc-patches 資料夾：Info.plist 還有啟動參數時，少了那個檔案遊戲會開不起來。
已經發生的話，執行安裝器選 3（全部移除），或用 Steam 驗證遊戲檔案的完整性。
想全部移除時：結束遊戲，再貼一次同一行指令（或照上面用 Install-Patches.command），
輸入 3 按 Return，再輸入 Y 按 Return 確認。

【和其他替換遊戲 class 的 MOD 一起用】
有些 MOD 會把 .class 檔放進遊戲裡。42.22 起這類檔案本身就會讓遊戲丟 SecurityException（有沒有裝本包都一樣），
安裝器看到時會提醒。它替換的 class 正好是本包要修補的時，安裝器會以 code 7 停止，不改任何檔案。
不要只刪除它列出的檔案，那個 MOD 的其他檔案還需要它們。要保留那個 MOD，就裝相容版（只含車輛的修補）；
有別的 MOD 擋著時，安裝器會建議你選它。

【安全驗證】
- 安裝器只接受對應版本的遊戲：寫入前會比對 projectzomboid.jar 與修補檔的 SHA-256；修補檔每次開遊戲也會再比對一次 jar。
- 只動自己的檔案（.mdc-patches 資料夾）、Info.plist 裡自己那一條啟動參數，以及你同意時的 -Xmx 記憶體上限；
  Info.plist 其他內容逐位元組保持原樣，全部移除後就是原本的檔案（記憶體上限維持你選的值）。
  不明或變造的檔案不會被自動覆蓋或刪除。安裝或移除中斷時，再執行一次即可。
- 一行指令執行的是本專案 GitHub 發布頁上的 install-mac.sh，用它就是信任這個 repo 和 GitHub。
  它比對 SHA-256 只能擋下載損壞或版本不同步，擋不了 repo 被盜用。想先看內容：curl -fsSL $macOneLineUrl | less，或改用這個 zip。
- 下載後可比對 $zipNameMac 的 SHA-256 與發布頁的 SHA256SUMS.txt
  （終端機：shasum -a 256 $zipNameMac）。
- 原始碼與技術說明：$repoUrl

=====================================================================
以下是給開發者與進階使用者的說明，一般玩家不用看。

模組說明
$modulesMacZh

安全邊界
- 只管理自家 patch，不移除其它作者的工具。
- 安裝器（mdc-mac-installer.jar）用遊戲自帶的 Java 執行。命令列：bash Install-Patches.command [menu|status|install|quick|uninstall]
  [--modules id,...] [--all] [--non-interactive] [--memory-mb N] [--lang zh|en]。一行指令會開選單，
  後面加的參數也會原樣交給安裝器（/bin/bash -c "`$(curl ...)" status）。遊戲不在 Steam 資料庫裡時，用 PZ_APP 指定 Project Zomboid.app 的路徑。
- 只在 Info.plist 加或拿掉自己那一條 -javaagent（排在 JVMOptions 最前面，比其他 agent 先啟動），以及你同意時改 -Xmx 的值；不改遊戲 JAR。
  其他 Java agent（-javaagent、-agentlib、-agentpath）照樣保留，但不支援和它們修改同一個 class：在本包之前就被改過的 class
  不換（console 印 [mdc-agent] NOT APPLIED）；別的 agent 在本包之後才改的，本包偵測不到。
- console（Zomboid/console.txt）印 [mdc-agent] ARMED＝修補已啟用；[mdc-agent] DISARMED（附原因）＝修補自己停用、照原版跑。
- 遊戲執行中不得移除修補，安裝器會拒絕（代碼 4）。

資料僅保存本機：Zomboid/Lua/MinidoracatDevProfiler/captures/。
本包不含自動 log 上傳、正式服監控或玩家回報收集。
"@
Write-Utf8 "$pkgMac\README-INSTALL.txt" $instructionsMacEn
Write-Utf8 "$pkgMac\README-INSTALL.zh-TW.txt" $instructionsMacZh

# 端到端：Mac 版面的遊戲（42.22.2 原版 Info.plist＋建置基準 jar）上安裝 → 照啟動器的方式（工作目錄 Contents/Java、
# 用 plist 裡的相對路徑參數）載入 agent 必須 ARMED → 遊戲裡的 MdcPatchRuntime 認得安裝紀錄 → 全部移除後 plist 逐位元組回原樣
$macApp = Join-Path $R 'work/mac-layout-game/Project Zomboid.app'
if (Test-Path -LiteralPath (Split-Path $macApp)) { Remove-Item -LiteralPath (Split-Path $macApp) -Recurse -Force }
$macRoot = Join-Path $macApp 'Contents/Java'
New-Item -ItemType Directory -Path $macRoot, (Join-Path $macApp 'Contents/MacOS') -Force | Out-Null
Copy-Item -LiteralPath $JAR -Destination "$macRoot\projectzomboid.jar"
[System.IO.File]::WriteAllBytes((Join-Path $macApp 'Contents/MacOS/JavaAppLauncher'), [byte[]]@(0))
$vanillaPlist = "$R\patcher\tests-installer-mac\Info.plist.42.22.2"
Copy-Item -LiteralPath $vanillaPlist -Destination (Join-Path $macApp 'Contents/Info.plist')
$e2eModules = @('core', 'profiler', 'client-fixes-standard')
& $JAVA -jar $macInstallerJar install --non-interactive --lang en --app $macApp --package-dir $pkgMac --modules 'profiler,client-fixes-standard'
Assert-Ok 'mac installer install (e2e)'
$plistText = [System.IO.File]::ReadAllText((Join-Path $macApp 'Contents/Info.plist'))
$agentOptions = @([regex]::Matches($plistText, '<string>(-javaagent:[^<]*)</string>') | ForEach-Object { $_.Groups[1].Value })
if ($agentOptions.Count -ne 1 -or $agentOptions[0] -ne "-javaagent:.mdc-patches/$AGENT_JAR_NAME=modules=$($e2eModules -join ',')") {
    throw "macOS 安裝器寫的啟動參數不對：$($agentOptions -join ' | ')"
}
$merged = Join-Path $DIST 'agentcheck-mac-layout.txt'
$mergedLines = @()
foreach ($m in $e2eModules) { $mergedLines += $moduleManifests[$m] }
Write-Utf8 $merged (($mergedLines -join "`n") + "`n")
Push-Location -LiteralPath $macRoot
try { & $JAVA -Xverify:all $agentOptions[0] -cp "$AGENT_CHECK;projectzomboid.jar" AgentLoadCheck $merged armed }
finally { Pop-Location }
Assert-Ok 'AgentLoadCheck armed (mac layout, relative -javaagent)'
& $JAVA -cp "$OUT;$DIST\checks\profiler;$TJ" zombie.mdc.MdcStateAcceptCheck $macRoot "$macRoot\.mdc-patches\$AGENT_JAR_NAME" ($e2eModules -join ',')
Assert-Ok 'MdcStateAcceptCheck (mac layout)'
& $JAVA -jar $macInstallerJar uninstall --non-interactive --all --lang en --app $macApp --package-dir $pkgMac
Assert-Ok 'mac installer uninstall (e2e)'
if ((Get-FileHash -LiteralPath (Join-Path $macApp 'Contents/Info.plist')).Hash -ne (Get-FileHash -LiteralPath $vanillaPlist).Hash -or (Test-Path -LiteralPath "$macRoot\.mdc-patches")) {
    throw 'macOS 全部移除後 Info.plist 沒有回到原檔，或 .mdc-patches 還在'
}
Write-Host 'macOS 端到端 OK：安裝 → 相對路徑 -javaagent ARMED → runtime 認得安裝紀錄 → 移除後 Info.plist 逐位元組相同'

Write-Host '[8/8] 打包（不發布、不安裝）'
$outDir = Join-Path $R 'output'
New-Item -ItemType Directory -Path $outDir -Force | Out-Null
# Windows 的一行指令（install-windows.ps1）同理：zip 先壓到 $DIST，bootstrap 測過才一起複製到 output
$zipDist = Join-Path $DIST $zipName
if (Test-Path -LiteralPath $zipDist) { Remove-Item -LiteralPath $zipDist -Force }
Compress-Archive -Path "$pkg\*" -DestinationPath $zipDist
$zipSha = (Get-FileHash -LiteralPath $zipDist -Algorithm SHA256).Hash.ToLowerInvariant()
$bootWin = [System.IO.File]::ReadAllText("$R\deploy-client\install-online.ps1").Replace("`r`n", "`n").Replace('@PACKAGE_VERSION@', $PACKAGE_VERSION).Replace('@ZIP_NAME@', $zipName).Replace('@ZIP_SHA256@', $zipSha).Replace('@GAME_VERSION@', $GAME_VERSION)
# Windows PowerShell 5.1 的 irm 把下載內容當 Latin-1：非 ASCII 一律轉 \uXXXX，執行時由模板的 L 還原
$bootWin = [regex]::Replace($bootWin, '[^\x00-\x7F]', { param($m) '\u{0:x4}' -f [int][char]$m.Value })
if ($bootWin -match '@[A-Z0-9_]+@' -or $zipSha -notmatch '^[0-9a-f]{64}$') { throw 'install-windows.ps1：佔位符沒填完，或 zip 的 SHA 格式不對' }
if (@($bootWin -split "`n" | Where-Object { $_ -match '\\u[0-9a-f]{4}' -and $_ -notmatch "\(L '" }).Count) { throw 'install-windows.ps1：中文只能放在 L 的第一個參數' }
$bootWinDist = Join-Path $DIST 'install-windows.ps1'
Write-Utf8 $bootWinDist $bootWin
if (@([System.IO.File]::ReadAllBytes($bootWinDist) | Where-Object { $_ -ge 0x80 -or $_ -eq 13 }).Count) { throw 'install-windows.ps1 必須是純 ASCII、LF' }
foreach ($ps in 'powershell.exe', 'pwsh.exe') {
    # 兩個都必跑：玩家多半用內建的 5.1，也有人用 7；從 pwsh 叫 5.1 時清掉 PSModulePath（已知坑：Get-FileHash 找不到）
    $oldModulePath = $env:PSModulePath
    if ($ps -eq 'powershell.exe') { $env:PSModulePath = $null }
    try { & $ps -NoProfile -ExecutionPolicy Bypass -File "$R\patcher\tests-client-installer\bootstrap-test.ps1" -Boot $bootWinDist -Zip $zipDist -Jar $JAR -Work (Join-Path $R 'work/win-bootstrap-test') }
    finally { $env:PSModulePath = $oldModulePath }
    Assert-Ok "bootstrap-test.ps1 ($ps)"
}
$zip = Join-Path $outDir $zipName
Copy-Item -LiteralPath $zipDist, $bootWinDist -Destination $outDir -Force
# 一行指令（install-mac.sh）和它要下載的 zip 出自同一次建置：先放在 $DIST、測過才複製到 output
$zipMacDist = Join-Path $DIST $zipNameMac
if (Test-Path -LiteralPath $zipMacDist) { Remove-Item -LiteralPath $zipMacDist -Force }
& $JAVA -cp $OUT UnixZip $pkgMac $zipMacDist
Assert-Ok 'UnixZip macOS'
$zipMacSha = (Get-FileHash -LiteralPath $zipMacDist -Algorithm SHA256).Hash.ToLowerInvariant()
$bootstrap = [System.IO.File]::ReadAllText("$R\deploy-client\mac\install-online.sh").Replace('@PACKAGE_VERSION@', $PACKAGE_VERSION).Replace('@ZIP_NAME@', $zipNameMac).Replace('@ZIP_SHA256@', $zipMacSha).Replace('@GAME_VERSION@', $GAME_VERSION)
if ($bootstrap -match '@[A-Z0-9_]+@' -or $bootstrap.Contains("`r") -or $zipMacSha -notmatch '^[0-9a-f]{64}$') { throw 'install-mac.sh：佔位符沒填完、有 CR，或 zip 的 SHA 格式不對' }
$bootstrapDist = Join-Path $DIST 'install-mac.sh'
Write-Utf8 $bootstrapDist $bootstrap
& $BASH -n $bootstrapDist
Assert-Ok 'bash -n install-mac.sh'
# 啟動腳本與一行指令：假的 HOME、Steam 資料庫與 .app（路徑含空白、括號、中文），假 java／curl 記下收到的參數
& $BASH "$R\patcher\tests-installer-mac\command-test.sh" $pkgMac (Join-Path $R 'work/mac-command-test') $bootstrapDist.Replace('\', '/') $zipMacDist.Replace('\', '/')
Assert-Ok 'command-test.sh'
$zipMac = Join-Path $outDir $zipNameMac
Copy-Item -LiteralPath $zipMacDist, $bootstrapDist -Destination $outDir -Force
Write-Host "完成：$zip"
Write-Host "完成：$zipMac"
Write-Host "完成：$(Join-Path $outDir 'install-mac.sh')（zip SHA256 $zipMacSha）"
Write-Host "完成：$(Join-Path $outDir 'install-windows.ps1')（zip SHA256 $zipSha）"
Write-Host "未壓縮套件：$pkg、$pkgMac"
