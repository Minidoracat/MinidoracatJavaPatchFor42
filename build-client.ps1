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
$GAME_VERSION = '42.21.0'
$PACKAGE_VERSION = '0.2.3'
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
    @{ id='core'; mode='client-core'; name='核心元件（自動安裝）'; nameEn='Core components (installed automatically)'; version=$coreVersion; requires=@() },
    @{ id='profiler'; mode='client-profiler'; name='DevProfiler 效能分析工具（模組開發者用）'; nameEn='DevProfiler performance tool (for mod developers)'; version=$coreVersion; requires=@('core') },
    @{ id='client-fixes-standard'; mode='client'; name='客戶端修復・標準版（記憶體 32GB 以上）'; nameEn='Client fixes - standard (32 GB RAM or more)'; version="v3.1($sourceRef)"; requires=@('core'); group='client-fixes' },
    @{ id='client-fixes-lowmem'; mode='client-lowmem'; name='客戶端修復・省記憶體版（記憶體 32GB 以下）'; nameEn='Client fixes - low memory (less than 32 GB RAM)'; version="v3.1-lowmem($sourceRef)"; requires=@('core'); group='client-fixes' }
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
    $module = [ordered]@{ id=$id; name=$recipe.name; nameEn=$recipe.nameEn; version=$recipe.version; requires=@($recipe.requires); files=$files }
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
    foreach ($test in @('zombie.mdc.TexturePipelineGuardBehaviorTest', 'zombie.core.textures.MinidoracatTextureLeakGuardBehaviorTest', 'zombie.mdc.ChunkStreamObserverBehaviorTest', 'zombie.mdc.TreeRoomGuardBehaviorTest')) {
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
$repoUrl = 'https://github.com/Minidoracat/MinidoracatJavaPatchFor42'
$zipName = "MinidoracatClientPatches-$GAME_VERSION-$PACKAGE_VERSION.zip"
$instructionsEn = @"
Minidoracat Client Patches $PACKAGE_VERSION / Project Zomboid $GAME_VERSION
(繁體中文說明：README-INSTALL.zh-TW.txt)

WHAT THIS IS
Unofficial client-side fixes that only change the game on your own PC. They are
loose .class files placed next to projectzomboid.jar; the jar itself is not modified.
- Players, zombies and vehicles turn invisible (only the shadow and name tag remain).
- Enclosed player-built rooms next to or on top of a pre-built building: as soon as
  you walk in, furniture, trees and fences disappear and the ERROR counter in the
  bottom-right keeps climbing (a 42.21.0 bug; TIS has fixed it internally).
If you have never seen these problems, you do not need this package.

INSTALL
1. Close the game.
2. Extract the whole zip into a folder (do not drag single files out of it).
3. Double-click Install-Patches.bat.
4. Type 1 and press Enter (install or update patches).
5. Type 1 and press Enter (client fixes).
6. When asked for the version, just press Enter: the recommended one is picked
   from your PC's memory.
7. When asked to confirm, type Y and press Enter.
8. When it says it is done, press Enter, then type 0 and Enter to exit.
The menus follow your Windows display language (Chinese or English). Press L in
the main menu to switch, or start it with: Install-Patches.bat -Lang en

BEFORE A GAME UPDATE
1. Close the game.
2. Double-click Uninstall-Patches.bat, type A and Enter (remove everything), then Y and Enter.
3. Let Steam update the game.
4. Wait for a package that matches the new game version, then install again.
Updating without removing the patches first can stop you from joining servers
or cause errors right after connecting.

SAFETY
- The installer only works on the exact game version it was built for: it checks the
  SHA-256 of your projectzomboid.jar and of every file before writing it.
- It only manages its own files. Unknown or modified loose classes are never
  overwritten or deleted, and an interrupted install can be rolled back.
- Check your download: compare the SHA-256 of $zipName with SHA256SUMS.txt on the
  release page (PowerShell: Get-FileHash .\$zipName).
- Source code and technical details: $repoUrl

=====================================================================
Advanced

Modules
- Client fixes: the standard variant raises the texture wait limit to 4 GiB (use it with
  32 GB RAM or more); the low-memory variant keeps the vanilla 50 MiB limit. Choose one.
  Both include the texture leak fixes, a chunk-streaming log for black-edge reports
  (logging only) and the 42.21.0 player-built room fix (XL trees are simply not faded
  inside such rooms).
- DevProfiler: performance tool for mod developers; its interface is the separate mod
  MinidoracatDevProfilerFor42. "installed" means the files verified; "hook observed" means
  this game session actually reached the hook.

Boundaries
- Manages only our own patches; it is not compatible with third-party ZombieBuddy APIs and
  never removes other authors' tools.
- Does not change the game jar, the JVM launch JSON or any Java agent.
- Old v3.0 packages are taken over only when every SHA matches; otherwise remove them with
  their own uninstaller first.
- Never remove patches while the game is running. If an install was interrupted, run the
  installer again before starting the game.
- Nothing is uploaded. Profiler captures stay in Zomboid/Lua/MinidoracatDevProfiler/captures/.
"@
$instructionsZh = @"
Minidoracat Client Patches $PACKAGE_VERSION / PZ $GAME_VERSION
(English: README-INSTALL.txt)

【這是什麼】
只改你自己電腦上的遊戲，修兩個畫面問題：
- 隊友、殭屍、車輛看不到，只剩影子和名牌。
- 在預製房子旁邊或上面加蓋的封閉房間，一走進去家具、樹、圍籬就消失，
  右下角 ERROR 一直往上跳（42.21.0 官方 bug）。
沒遇過這些問題的人可以不用裝。

【怎麼安裝】
1. 關閉遊戲。
2. 把整個壓縮檔解壓縮到一個資料夾（不要只拉出其中一個檔案）。
3. 雙擊 Install-Patches.bat。
4. 輸入 1 按 Enter（安裝或更新修補）。
5. 輸入 1 按 Enter（修復隱形、自建房間看不到東西）。
6. 選版本時直接按 Enter，程式會依你的電腦自動選好。
7. 問「確定要安裝嗎？」時，輸入 Y 按 Enter。
8. 看到安裝完成後按 Enter，再輸入 0 按 Enter 關閉，就可以開遊戲了。
選單語言跟著 Windows 顯示語言；主選單按 L 可切換中文／英文。

【遊戲要更新時】
1. 關閉遊戲。
2. 雙擊 Uninstall-Patches.bat，輸入 A 按 Enter（全部移除），再輸入 Y 按 Enter 確認。
3. 讓 Steam 更新遊戲。
4. 等新版修補包發布後，再照上面的步驟裝回去。
沒先移除就更新，可能會進不了伺服器，或一連線就出錯。

【以前裝過舊版】
- 裝過有 Uninstall-Patches.bat 的版本：直接照上面安裝即可，會自動換成新版。
- 裝過只有 uninstall.bat 的 TexPipeline 舊版：先執行舊包裡的 uninstall.bat，再裝這包。

【安全驗證】
- 安裝器只接受對應版本的遊戲：會比對 projectzomboid.jar 與每個檔案的 SHA-256，不符就不寫入。
- 下載後可比對 $zipName 的 SHA-256 與發布頁的 SHA256SUMS.txt
  （PowerShell：Get-FileHash .\$zipName）。
- 原始碼與技術說明：$repoUrl

=====================================================================
以下是給開發者與進階使用者的說明，一般玩家不用看。

模組說明
- 客戶端修復：標準版把貼圖等待門檻放寬到 4 GiB（建議 32GB 以上 RAM）；
  省記憶體版保留原版 50 MiB 門檻。兩者擇一。另附黑邊時的串流紀錄（只記錄不改行為）。
  兩版都含 42.21.0 自建房間 XL 樹例外修補：這種房間裡的 XL 樹不做室內淡化，其餘同原版。
- DevProfiler：效能分析工具，介面需另在遊戲 MOD 管理器啟用 MinidoracatDevProfilerFor42。
  installed 只代表檔案驗證通過；hook observed 才代表本次 JVM 走到該掛點。

安全邊界
- 只管理自家 patch，不相容第三方 ZombieBuddy API，也不移除其它作者的工具。
- 不改遊戲 JAR、JVM 啟動 JSON 或其他 Java agent。
- 不明或變造的 loose class 不會被自動覆蓋或刪除。
- 舊 v3.0 包必須整組 SHA 完全吻合才會接管；辨識不了時先用舊版 uninstaller。
- 遊戲更新前先卸載。安裝時的 SHA 閘無法阻止 Steam 日後更新 JAR 卻殘留舊 class。
- JVM 執行中不得卸載 helper。交易中斷時，先重跑管理器復原，再開遊戲。

資料僅保存本機：Zomboid/Lua/MinidoracatDevProfiler/captures/。
本包不含自動 log 上傳、正式服監控或玩家回報收集。
"@
Write-Utf8 "$pkg\README-INSTALL.txt" $instructionsEn
Write-Utf8 "$pkg\README-INSTALL.zh-TW.txt" $instructionsZh

Write-Host '[7/7] 打包（不發布、不安裝）'
$outDir = Join-Path $R 'output'
New-Item -ItemType Directory -Path $outDir -Force | Out-Null
$zip = Join-Path $outDir "MinidoracatClientPatches-$GAME_VERSION-$PACKAGE_VERSION.zip"
if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }
Compress-Archive -Path "$pkg\*" -DestinationPath $zip
Write-Host "完成：$zip"
Write-Host "未壓縮套件：$pkg"
