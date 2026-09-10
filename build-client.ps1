# build-client.ps1 — 同一個套件包含共用核心、Profiler、client 修復的兩種記憶體變體。
# 只產出 dist-client-modular/output；不安裝、不啟動遊戲、不動 server manifest。
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
$GAME_VERSION = '42.20.4'
$PACKAGE_VERSION = '0.1.0'
$DIST = Join-Path $R 'dist-client-modular'
$OUT = Join-Path $R 'work/out-client-modular'
$GEN = Join-Path $R 'work/gen-client-modular'
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
foreach ($path in @($DIST, $OUT, $GEN)) {
    if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Recurse -Force }
    New-Item -ItemType Directory -Path $path -Force | Out-Null
}

Write-Host '[1/7] 編譯 ASM 工具'
& $JAVAC -encoding UTF-8 -cp $ASM_CP -d $OUT @((Get-ChildItem "$R\patcher\src\*.java").FullName)
Assert-Ok 'javac patcher'

Write-Host '[2/7] 編譯共用核心與採樣器'
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

Write-Host '[3/7] 產生各模組 payload 與逐方法守門'
$recipes = @(
    @{ id='core'; mode='client-core'; name='Minidoracat patch core'; version=$coreVersion; requires=@() },
    @{ id='profiler'; mode='client-profiler'; name='DevProfiler'; version=$coreVersion; requires=@('core') },
    @{ id='client-fixes-standard'; mode='client'; name='Client fixes (standard 4 GiB)'; version="v3.0($sourceRef)"; requires=@('core'); group='client-fixes' },
    @{ id='client-fixes-lowmem'; mode='client-lowmem'; name='Client fixes (lowmem 50 MiB)'; version="v3.0-lowmem($sourceRef)"; requires=@('core'); group='client-fixes' }
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
        $helperEntries = @(Get-ClassEntries $payload)
    }
    $manifestPath = Join-Path $DIST "manifests/$id.txt"
    & $JAVA -cp "$OUT;$ASM_CP" Patcher $JAR $payload $manifestPath $recipe.mode
    Assert-Ok "Patcher $id"
    $patchedLines = @(Get-Content -LiteralPath $manifestPath)
    $lines = @($helperEntries | ForEach-Object { "$($_.path)`t-`t$($_.sha256)`t0hits" }) + $patchedLines
    Write-Utf8 $manifestPath (($lines -join "`n") + "`n")
    $moduleManifests[$id] = $lines
    $files = @($lines | ForEach-Object {
        $fields = $_.Split("`t")
        [ordered]@{ path=$fields[0]; source="payload/$id/$($fields[0])"; sha256=$fields[2] }
    })
    $module = [ordered]@{ id=$id; name=$recipe.name; version=$recipe.version; requires=@($recipe.requires); files=$files }
    if ($recipe.group) { $module.exclusiveGroup = $recipe.group }
    $modules += $module
}

Write-Host '[4/7] 每個模組與核心合併後做 linkage／bytecode／語意驗證'
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
    & $JAVA -Xverify:all -cp "$OUT;$ASM_CP" LoadCheck $view $JAR $checkManifest $recipe.mode
    Assert-Ok "LoadCheck $id"
    & $JAVA -cp "$OUT;$ASM_CP" BytecodeVerify $view $JAR $checkManifest
    Assert-Ok "BytecodeVerify $id"
    & $JAVA -cp "$OUT;$ASM_CP" SmokeCheck $view $JAR $recipe.mode
    Assert-Ok "SmokeCheck $id"
}

Write-Host '[5/7] 執行新核心與既有 client 修復行為測試'
$testSources = @((Get-ChildItem "$R\patcher\tests-client" -Recurse -Filter '*.java').FullName)
$testSources += @((Get-ChildItem "$R\patcher\tests-profiler" -Recurse -Filter '*.java').FullName)
if (Test-Path "$R\patcher\tests-client-common") {
    $testSources += @((Get-ChildItem "$R\patcher\tests-client-common" -Recurse -Filter '*.java').FullName)
}
& $JAVAC -encoding UTF-8 -cp "$OUT;$ASM_CP;$DIST\checks\client-fixes-standard;$JAR" -d $OUT @testSources
Assert-Ok 'javac client behavior tests'
foreach ($id in @('client-fixes-standard', 'client-fixes-lowmem')) {
    $cp = "$OUT;$DIST\checks\$id;$JAR"
    foreach ($test in @('zombie.mdc.TexturePipelineGuardBehaviorTest', 'zombie.core.textures.MinidoracatTextureLeakGuardBehaviorTest', 'zombie.mdc.ChunkStreamObserverBehaviorTest')) {
        & $JAVA --enable-native-access=ALL-UNNAMED -cp $cp $test
        Assert-Ok "$test $id"
    }
}
$profilerTests = @(Get-ChildItem "$R\patcher\tests-profiler" -Recurse -Filter '*Test.java')
if (Test-Path "$R\patcher\tests-client-common") {
    $profilerTests += @(Get-ChildItem "$R\patcher\tests-client-common" -Recurse -Filter '*Test.java')
}
foreach ($test in $profilerTests) {
    $className = 'zombie.mdc.' + $test.BaseName
    & $JAVA --enable-native-access=ALL-UNNAMED -cp "$OUT;$DIST\checks\profiler;$JAR" $className
    Assert-Ok $className
}

Write-Host '[6/7] 產生管理器套件與模組 manifest'
$pkg = Join-Path $DIST 'pkg'
New-Item -ItemType Directory -Path $pkg -Force | Out-Null
Copy-Item -LiteralPath "$DIST\payload" -Destination "$pkg\payload" -Recurse -Force
foreach ($name in @('Manage-Patches.ps1', 'Install-Patches.bat', 'Uninstall-Patches.bat')) {
    Copy-Item -LiteralPath "$R\deploy-client\$name" -Destination $pkg
}
$manifest = [ordered]@{
    schemaVersion=1; gameVersion=$GAME_VERSION; packageVersion=$PACKAGE_VERSION
    jarSha256=$jarSha; built=$built; sourceRef=$sourceRef; modules=$modules
    legacyPackages=@(Get-Content -LiteralPath "$R\deploy-client\legacy-packages.json" -Raw | ConvertFrom-Json)
}
Write-Utf8 "$pkg\manifest.json" (($manifest | ConvertTo-Json -Depth 12) + "`n")
$instructions = @"
Minidoracat Client Patches $PACKAGE_VERSION / PZ $GAME_VERSION

安裝與移除
1. 修改 patch 前，先關閉 Project Zomboid 與本機測試伺服器。
2. 完整解壓縮，保留 manifest.json、payload 與三個管理程式在同一資料夾。
3. 雙擊 Install-Patches.bat，選擇 Profiler、client 修復包，或兩者。
4. lowmem 保留原版 50 MiB 貼圖等待門檻；standard 放寬至 4 GiB，只給有需要的
   高記憶體電腦。Profiler 不依賴修復包，不會自行更改貼圖門檻。
5. 效能分析介面需另在遊戲 MOD 管理器啟用 MinidoracatDevProfilerFor42。
6. 正常啟動遊戲；主選單短暫顯示已安裝模組與掛點活動。
   installed 只代表檔案驗證通過；hook observed 才代表本次 JVM 走到該掛點。
7. 雙擊 Uninstall-Patches.bat，可移除指定模組或全部自家模組。

安全邊界
- 只管理自家 patch，不相容第三方 ZombieBuddy API，也不移除其它作者的工具。
- 不改遊戲 JAR、JVM 啟動 JSON 或其他 Java agent。
- 不明或變造的 loose class 不會被自動覆蓋或刪除。
- 舊 v3.0 包必須整組 SHA 完全吻合才會接管；辨識不了時先用舊版 uninstaller。
- 遊戲更新前先卸載。安裝時的 SHA 閘無法阻止 Steam 日後更新 JAR 卻殘留舊 class。
- JVM 執行中不得卸載 helper。交易中斷時，先重跑管理器復原，再開遊戲。

資料僅保存本機：Zomboid/Lua/MinidoracatDevProfiler/captures/。
本包不含自動 log 上傳、正式服監控或玩家回報收集。

English quick guide
Close the game, extract the entire ZIP, then run Install-Patches.bat.
Profiler and Client fixes are independent modules. The two fixes variants are
mutually exclusive. Enable the DevProfiler Lua MOD separately for its interface.
Run Uninstall-Patches.bat to remove selected modules or all our modules.
Remove loose patches before updating the game; never uninstall while the JVM runs.
This package does not replace third-party ZombieBuddy APIs or upload diagnostics.
"@
Write-Utf8 "$pkg\README-INSTALL.txt" $instructions

Write-Host '[7/7] 打包（不發布、不安裝）'
$outDir = Join-Path $R 'output'
New-Item -ItemType Directory -Path $outDir -Force | Out-Null
$zip = Join-Path $outDir "MinidoracatClientPatches-$GAME_VERSION-$PACKAGE_VERSION.zip"
if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }
Compress-Archive -Path "$pkg\*" -DestinationPath $zip
Write-Host "完成：$zip"
Write-Host "未壓縮套件：$pkg"
