# build-client.ps1 — 建置 PZ client loose-class patch（invisible-entities 觀測＋門檻修復）
# 與 server build（build.ps1）完全隔離：獨立 work\out-client 與 dist-client\，不進 server manifest。
# client 與 server 的 projectzomboid.jar 逐版 class 內容相同，共用 work\projectzomboid.jar
#（42.20.2 起兩側整檔 SHA 可能因重新打包而異，install 閘以 build 當下的 work jar SHA 注入）。
$ErrorActionPreference = 'Stop'
# patch 版本（出包檔名用）：v1=256MB、v1.1=1GB+floor 觀測、v1.2=4GB、v2.0=洩漏根治第一波
$PATCH_VERSION = 'v2.0'
# 支援的遊戲版本（出包檔名與 install.bat 訊息；整 jar SHA 閘由 work jar 自動注入）
$GAME_VERSION = '42.20.2'
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

Remove-Item -Recurse -Force "$R\work\out-client", "$R\dist-client" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$R\work\out-client", "$R\dist-client\java" | Out-Null
$ASM_CP = "$R\lib\asm-9.8.jar;$R\lib\asm-tree-9.8.jar;$R\lib\asm-analysis-9.8.jar;$R\lib\asm-util-9.8.jar"

Write-Host "[1/8] 編譯 patcher..."
javac -encoding UTF-8 -cp $ASM_CP -d "$R\work\out-client" (Get-ChildItem "$R\patcher\src\*.java").FullName
Assert-Ok "javac patcher"

Write-Host "[2/8] 編譯 client helper（對遊戲 jar）..."
javac -encoding UTF-8 -cp "$R\work\projectzomboid.jar" -d "$R\dist-client\java" `
    (Get-ChildItem "$R\patcher\game-client" -Recurse -Filter *.java).FullName
Assert-Ok "javac client helper"

Write-Host "[3/8] 編譯 client 行為測試..."
javac -encoding UTF-8 -cp "$R\dist-client\java;$R\work\projectzomboid.jar" -d "$R\work\out-client" `
    (Get-ChildItem "$R\patcher\tests-client" -Recurse -Filter *.java).FullName
Assert-Ok "javac client tests"

Write-Host "[4/8] 執行 bytecode 手術（client 集合）..."
java -cp "$R\work\out-client;$ASM_CP" Patcher "$R\work\projectzomboid.jar" "$R\dist-client\java" "$R\dist-client\manifest.txt" client
Assert-Ok "Patcher client"

# helper 條目前置（origSha=- 表無 jar 原版）；部署順序＝先 helper、再 patched caller
$helperEntries = @(
    'zombie/mdc/TexturePipelineGuard.class',
    'zombie/core/textures/MinidoracatTextureLeakGuard.class'
)
$manifestLines = foreach ($entry in $helperEntries) {
    $helperSha = (Get-FileHash -Algorithm SHA256 "$R\dist-client\java\$entry").Hash.ToLower()
    "$entry`t-`t$helperSha`t0hits"
}
$manifestLines += Get-Content "$R\dist-client\manifest.txt"
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText(
    "$R\dist-client\manifest.txt",
    [string]::Join("`n", $manifestLines) + "`n",
    $utf8NoBom)
Write-Host "client manifest -> $R\dist-client\manifest.txt（$($manifestLines.Count) classes）"

Write-Host "[5/8] 連結驗證（-Xverify:all，client 模式）..."
java -Xverify:all -cp "$R\work\out-client" LoadCheck "$R\dist-client\java" "$R\work\projectzomboid.jar" "$R\dist-client\manifest.txt" client
Assert-Ok "LoadCheck client"

Write-Host "[6/8] JVMS 資料流驗證（CheckClassAdapter）..."
java -cp "$R\work\out-client;$ASM_CP" BytecodeVerify "$R\dist-client\java" "$R\work\projectzomboid.jar" "$R\dist-client\manifest.txt"
Assert-Ok "BytecodeVerify client"

Write-Host "[7/8] 守衛語意驗證（client 模式）＋行為測試..."
java -cp "$R\work\out-client;$ASM_CP" SmokeCheck "$R\dist-client\java" "$R\work\projectzomboid.jar" client
Assert-Ok "SmokeCheck client"
java -cp "$R\work\out-client;$R\dist-client\java;$R\work\projectzomboid.jar" zombie.mdc.TexturePipelineGuardBehaviorTest
Assert-Ok "TexturePipelineGuardBehaviorTest"
java -cp "$R\work\out-client;$R\dist-client\java;$R\work\projectzomboid.jar" zombie.core.textures.MinidoracatTextureLeakGuardBehaviorTest
Assert-Ok "MinidoracatTextureLeakGuardBehaviorTest"

Write-Host "[8/8] 打包玩家安裝 zip（SHA 閘門注入 install/uninstall.bat）..."
$pkg = "$R\dist-client\pkg"
Remove-Item -Recurse -Force $pkg -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$pkg\patch-files" | Out-Null
# payload 放 patch-files\ 暫存區：install.bat 先驗 jar SHA／衝突，通過才複製到遊戲目錄並回驗
Copy-Item -Recurse "$R\dist-client\java\zombie" "$pkg\patch-files\zombie"

$jarSha = (Get-FileHash -Algorithm SHA256 "$R\work\projectzomboid.jar").Hash.ToLower()
# payload 逐檔生成 install 的衝突/回驗段與 uninstall 的 removeone 段（線性 goto、唯一標籤）
$payload = Get-ChildItem "$R\dist-client\java" -Recurse -Filter *.class | ForEach-Object {
    [pscustomobject]@{
        Rel = $_.FullName.Substring("$R\dist-client\java\".Length)
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
# 檔名一律 ASCII：Compress-Archive 對非 ASCII 檔名會寫出 OEM 亂碼 entry（實測）
Copy-Item "$R\deploy-client\README-INSTALL.txt" $pkg -Force
$zip = "$R\dist-client\MinidoracatClientPatch-TexPipeline-$GAME_VERSION-$PATCH_VERSION.zip"
Get-ChildItem "$R\dist-client\MinidoracatClientPatch-*.zip" -ErrorAction SilentlyContinue | Remove-Item
Compress-Archive -Path "$pkg\*" -DestinationPath $zip

# 發布到 output\（gitignore）：zip＋未壓縮目錄一站式，舊版自動清掉避免混發
$out = "$R\output"
New-Item -ItemType Directory -Force $out | Out-Null
Get-ChildItem "$out\MinidoracatClientPatch-*" -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force
Copy-Item $zip $out
Copy-Item -Recurse $pkg "$out\MinidoracatClientPatch-TexPipeline-$GAME_VERSION-$PATCH_VERSION"
Write-Host "完成：$zip"
Write-Host "output -> $out（zip＋未壓縮目錄）"
