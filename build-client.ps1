# build-client.ps1 — 建置 PZ client loose-class patch（invisible-entities 觀測＋門檻修復）
# 與 server build（build.ps1）完全隔離：獨立 work\out-client 與 dist-client\，不進 server manifest。
# client 與 server 的 projectzomboid.jar 逐版 class 內容相同（42.20.3 實測連整檔 SHA 都同），
# install 閘以 build 當下的 work jar SHA 注入。
# 用法：.\build-client.ps1 [-Variant standard|lowmem]
#   standard＝觀測＋根治＋門檻 50MB→4GB（高 RAM 受害 client）
#   lowmem  ＝觀測＋根治、不動門檻（≤8GB RAM 機器；redirect 指向 LowMem 入口，
#             effective 門檻烘進 helper——Patcher/LoadCheck/SmokeCheck 全走顯式
#             client-lowmem mode，忘傳＝建置失敗而非默默出錯包）
param([ValidateSet('standard', 'lowmem')][string]$Variant = 'standard')
$ErrorActionPreference = 'Stop'
# patch 版本（出包檔名用）：v1=256MB、v1.1=1GB+floor 觀測、v1.2=4GB、v2.0=洩漏根治第一波、
# v2.1=chunk 串流觀測（黑邊鑑識）、v2.2=W4-2 逾時 8s→15s（42.20.3 隨 vanilla 刪除該方法而撤刀）、
# v3.0=42.20.3 重建：觀測擴充第 4 headCall（ChunkNotReady 新協定）＋lowmem 變體
$PATCH_VERSION = if ($Variant -eq 'lowmem') { 'v3.0-lowmem' } else { 'v3.0' }
$CLIENT_MODE = if ($Variant -eq 'lowmem') { 'client-lowmem' } else { 'client' }
# 支援的遊戲版本（出包檔名與 install.bat 訊息；整 jar SHA 閘由 work jar 自動注入）
$GAME_VERSION = '42.20.3'
$DIST = if ($Variant -eq 'lowmem') { "$PSScriptRoot\dist-client-lowmem" } else { "$PSScriptRoot\dist-client" }
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$R = $PSScriptRoot

$JDK = "$env:USERPROFILE\scoop\apps\temurin25-jdk\current"
if (-not (Test-Path "$JDK\bin\javac.exe")) { throw "找不到 JDK 25（$JDK）——scoop install java/temurin25-jdk" }
Set-Alias -Name javac -Value "$JDK\bin\javac.exe"
Set-Alias -Name java -Value "$JDK\bin\java.exe"

function Assert-Ok([string]$step) {
    if ($LASTEXITCODE -ne 0) { throw "$step 失敗（exit=$LASTEXITCODE）——建置中止" }
}

if (-not (Test-Path "$R\work\projectzomboid.jar")) {
    throw "缺 work\projectzomboid.jar —— 可從本機 client 安裝複製（SHA 與伺服器相同）"
}

Remove-Item -Recurse -Force "$R\work\out-client", "$R\work\gen-client", "$DIST" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$R\work\out-client", "$R\work\gen-client", "$DIST\java" | Out-Null

# 版本指紋（與出包 zip 檔名同源，杜絕手寫漂移）
$gitSha = (& git rev-parse --short HEAD 2>$null)
if (-not $gitSha) { $gitSha = 'nogit' }
# dirty 判定只看會進到產物的東西（見 build.ps1 同段說明）
if ((& git status --porcelain --untracked-files=no 2>$null) -or
    (& git status --porcelain -- patcher 2>$null)) { $gitSha = "$gitSha+dirty" }
$builtAt = (Get-Date -Format 'yyyy-MM-ddTHH:mm')
$jarSha8 = (Get-FileHash -Algorithm SHA256 "$R\work\projectzomboid.jar").Hash.ToLower().Substring(0, 8)
$ASM_CP = "$R\lib\asm-9.8.jar;$R\lib\asm-tree-9.8.jar;$R\lib\asm-analysis-9.8.jar;$R\lib\asm-util-9.8.jar"

Write-Host "[1/8] 編譯 patcher..."
javac -encoding UTF-8 -cp $ASM_CP -d "$R\work\out-client" (Get-ChildItem "$R\patcher\src\*.java").FullName
Assert-Ok "javac patcher"

Write-Host "[2/8] 生成 PatchInfo（版本指紋）＋編譯 client helper（對遊戲 jar）..."
java -cp "$R\work\out-client" PatchInfoGen "$R\work\gen-client" 'client' "$PATCH_VERSION($gitSha)" $builtAt $jarSha8
Assert-Ok "PatchInfoGen"
javac -encoding UTF-8 -cp "$R\work\projectzomboid.jar" -d "$DIST\java" `
    ((Get-ChildItem "$R\patcher\game-client" -Recurse -Filter *.java).FullName + (Get-ChildItem "$R\work\gen-client" -Recurse -Filter *.java).FullName)
Assert-Ok "javac client helper"

Write-Host "[3/8] 編譯 client 行為測試..."
javac -encoding UTF-8 -cp "$DIST\java;$R\work\projectzomboid.jar" -d "$R\work\out-client" `
    (Get-ChildItem "$R\patcher\tests-client" -Recurse -Filter *.java).FullName
Assert-Ok "javac client tests"

Write-Host "[4/8] 執行 bytecode 手術（client 集合）..."
java -cp "$R\work\out-client;$ASM_CP" Patcher "$R\work\projectzomboid.jar" "$DIST\java" "$DIST\manifest.txt" $CLIENT_MODE
Assert-Ok "Patcher client"

# helper 條目前置（origSha=- 表無 jar 原版）；部署順序＝先 helper、再 patched caller
$helperEntries = @(
    'zombie/mdc/TexturePipelineGuard.class',
    'zombie/core/textures/MinidoracatTextureLeakGuard.class',
    'zombie/mdc/ChunkStreamObserver.class',
    'zombie/mdc/PatchInfo.class'
)
$manifestLines = foreach ($entry in $helperEntries) {
    $helperSha = (Get-FileHash -Algorithm SHA256 "$DIST\java\$entry").Hash.ToLower()
    "$entry`t-`t$helperSha`t0hits"
}
$manifestLines += Get-Content "$DIST\manifest.txt"
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText(
    "$DIST\manifest.txt",
    [string]::Join("`n", $manifestLines) + "`n",
    $utf8NoBom)
Write-Host "client manifest -> $DIST\manifest.txt（$($manifestLines.Count) classes）"

Write-Host "[5/8] 連結驗證（-Xverify:all，client 模式）..."
java -Xverify:all -cp "$R\work\out-client" LoadCheck "$DIST\java" "$R\work\projectzomboid.jar" "$DIST\manifest.txt" $CLIENT_MODE
Assert-Ok "LoadCheck client"

Write-Host "[6/8] JVMS 資料流驗證（CheckClassAdapter）..."
java -cp "$R\work\out-client;$ASM_CP" BytecodeVerify "$DIST\java" "$R\work\projectzomboid.jar" "$DIST\manifest.txt"
Assert-Ok "BytecodeVerify client"

Write-Host "[7/8] 守衛語意驗證（client 模式）＋行為測試..."
java -cp "$R\work\out-client;$ASM_CP" SmokeCheck "$DIST\java" "$R\work\projectzomboid.jar" $CLIENT_MODE
Assert-Ok "SmokeCheck client"
java -cp "$R\work\out-client;$DIST\java;$R\work\projectzomboid.jar" zombie.mdc.TexturePipelineGuardBehaviorTest
Assert-Ok "TexturePipelineGuardBehaviorTest"
java -cp "$R\work\out-client;$DIST\java;$R\work\projectzomboid.jar" zombie.core.textures.MinidoracatTextureLeakGuardBehaviorTest
Assert-Ok "MinidoracatTextureLeakGuardBehaviorTest"
java -cp "$R\work\out-client;$DIST\java;$R\work\projectzomboid.jar" zombie.mdc.ChunkStreamObserverBehaviorTest
Assert-Ok "ChunkStreamObserverBehaviorTest"

Write-Host "[8/8] 打包玩家安裝 zip（SHA 閘門注入 install/uninstall.bat）..."
$pkg = "$DIST\pkg"
Remove-Item -Recurse -Force $pkg -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$pkg\patch-files" | Out-Null
# payload 放 patch-files\ 暫存區：install.bat 先驗 jar SHA／衝突，通過才複製到遊戲目錄並回驗
Copy-Item -Recurse "$DIST\java\zombie" "$pkg\patch-files\zombie"

$jarSha = (Get-FileHash -Algorithm SHA256 "$R\work\projectzomboid.jar").Hash.ToLower()
# payload 逐檔生成 install 的衝突/回驗段與 uninstall 的 removeone 段（線性 goto、唯一標籤）
$payload = Get-ChildItem "$DIST\java" -Recurse -Filter *.class | ForEach-Object {
    [pscustomobject]@{
        Rel = $_.FullName.Substring("$DIST\java\".Length)
        Sha = (Get-FileHash -Algorithm SHA256 $_.FullName).Hash.ToLower()
    }
}
$conflictChecks = ''; $verifyChecks = ''; $removeCalls = ''; $srcChecks = ''; $rollbackDeletes = ''; $ci = 0
foreach ($f in $payload) {
    $ci++
    $srcChecks += "call :hash `"%SRC%patch-files\$($f.Rel)`"`n" +
        "if /i `"%HASH%`"==`"$($f.Sha)`" goto :src$ci`n" +
        "echo [ERROR] patch-files\$($f.Rel) is corrupted or incomplete. Re-extract the whole zip.`n" +
        "goto :fail`n" +
        ":src$ci`n"
    $conflictChecks += "if not exist `"%GAMEDIR%$($f.Rel)`" goto :conf$ci`n" +
        "call :hash `"%GAMEDIR%$($f.Rel)`"`n" +
        "if /i `"%HASH%`"==`"$($f.Sha)`" goto :conf$ci`n" +
        "echo [ERROR] A different loose patch already exists at $($f.Rel) - remove it first.`n" +
        "goto :fail`n" +
        ":conf$ci`n"
    $verifyChecks += "call :hash `"%GAMEDIR%$($f.Rel)`"`n" +
        "if /i not `"%HASH%`"==`"$($f.Sha)`" goto :verifyfail`n"
    $removeCalls += "call :removeone `"%GAMEDIR%$($f.Rel)`" `"$($f.Sha)`"`n"
    $rollbackDeletes += "del /q `"%GAMEDIR%$($f.Rel)`" 2>nul`n"
}
Write-Host "install/uninstall 閘門涵蓋 $($payload.Count) 個 payload 檔"
foreach ($bat in @('install', 'uninstall')) {
    $body = (Get-Content -Raw "$R\deploy-client\$bat.bat.template") `
        -replace '__JAR_SHA__', $jarSha `
        -replace '__GAME_VERSION__', $GAME_VERSION `
        -replace '__SRC_CHECKS__', $srcChecks.TrimEnd("`n") `
        -replace '__CONFLICT_CHECKS__', $conflictChecks.TrimEnd("`n") `
        -replace '__VERIFY_CHECKS__', $verifyChecks.TrimEnd("`n") `
        -replace '__ROLLBACK_DELETES__', $rollbackDeletes.TrimEnd("`n") `
        -replace '__REMOVE_CALLS__', $removeCalls.TrimEnd("`n")
    if ($body -match '__[A-Z_]+__') { throw "$bat.bat 模板還有未注入的 placeholder" }
    # CRLF 強制：LF-only 批次檔在 cmd 會出現幽靈解析錯誤（「這個時候不應有…」，實測）
    $body = $body -replace "`r?`n", "`r`n"
    # ASCII 無 BOM：cmd 對 BOM 開頭的 @echo off 會直接報錯
    [System.IO.File]::WriteAllText("$pkg\$bat.bat", $body, [System.Text.Encoding]::ASCII)
}
# README 依 variant 生成（template 置換；lowmem 的門檻/橫幅/建議段與 standard 相反，
# 共用單一檔案會攜帶錯誤安裝指示——外部 codex post-fix review 抓到）
$limitSection = if ($Variant -eq 'lowmem') {
    "2.（低記憶體變體）**不放寬** 50MB 門檻（保持原版行為）——本包給 16GB 以下 RAM 的`n" +
    "   電腦使用：4GB 天花板的一般記憶體（RAM）預算對這類機器過大。洩漏根治（第 1 點）`n" +
    "   已讓水位可回收，50MB 門檻恢復「短暫等待」的原版設計語意。"
} else {
    "2.（保險）把遊戲貼圖載入管線的 50MB 記憶體門檻放寬到 4GB——原版超過 50MB 時`n" +
    "   載入執行緒會無限等待；根治後水位應遠低於此，這層變成第二道保險。`n" +
    "`n" +
    "記憶體說明：放寬的是一般記憶體（RAM）的使用上限，不是顯示記憶體（VRAM）。`n" +
    "原本會「卡住等待」的時刻改為「多用一些 RAM 繼續載入」，最壞情況可能比原版`n" +
    "多用數 GB 的 RAM（給大量探索留的餘裕）。這是給受影響玩家的實驗版本，**建議`n" +
    "32GB 以上 RAM 的電腦使用**；RAM 較小（16GB 以下）請改用 lowmem 版本。"
}
$effectiveLimit = if ($Variant -eq 'lowmem') { '52428800' } else { '4294967296' }
$readme = (Get-Content -Raw "$R\deploy-client\README-INSTALL.txt.template") `
    -replace '__GAME_VERSION__', $GAME_VERSION `
    -replace '__PATCH_VERSION__', $PATCH_VERSION `
    -replace '__PAYLOAD_COUNT__', $payload.Count `
    -replace '__EFFECTIVE_LIMIT__', $effectiveLimit `
    -replace '__LIMIT_SECTION__', $limitSection
if ($readme -match '__[A-Z_]+__') { throw "README 模板還有未注入的 placeholder" }
[System.IO.File]::WriteAllText("$pkg\README-INSTALL.txt", ($readme -replace "`r?`n", "`r`n"),
    [System.Text.UTF8Encoding]::new($false))
$zip = "$DIST\MinidoracatClientPatch-TexPipeline-$GAME_VERSION-$PATCH_VERSION.zip"
Get-ChildItem "$DIST\MinidoracatClientPatch-*.zip" -ErrorAction SilentlyContinue | Remove-Item
Compress-Archive -Path "$pkg\*" -DestinationPath $zip

# 發布到 output\（gitignore）：zip＋未壓縮目錄一站式，舊版自動清掉避免混發
$out = "$R\output"
New-Item -ItemType Directory -Force $out | Out-Null
Remove-Item "$out\MinidoracatClientPatch-TexPipeline-$GAME_VERSION-$PATCH_VERSION.zip" -ErrorAction SilentlyContinue
Remove-Item -Recurse "$out\MinidoracatClientPatch-TexPipeline-$GAME_VERSION-$PATCH_VERSION" -ErrorAction SilentlyContinue
Copy-Item $zip $out
Copy-Item -Recurse $pkg "$out\MinidoracatClientPatch-TexPipeline-$GAME_VERSION-$PATCH_VERSION"
Write-Host "完成：$zip"
Write-Host "output -> $out（zip＋未壓縮目錄）"
