# build-client.ps1 — 建置 PZ client loose-class patch（invisible-entities 觀測＋門檻修復）
# 與 server build（build.ps1）完全隔離：獨立 work\out-client 與 dist-client\，不進 server manifest。
# client 與 server 的 projectzomboid.jar SHA-256 相同（e4661ca9…54b8），共用 work\projectzomboid.jar。
$ErrorActionPreference = 'Stop'
# patch 版本（出包檔名用）：v1=256MB、v1.1=1GB+floor 觀測、v1.2=4GB
$PATCH_VERSION = 'v1.2'
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
$helperEntries = @('zombie/mdc/TexturePipelineGuard.class')
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

Write-Host "[8/8] 打包玩家安裝 zip（SHA 閘門注入 install/uninstall.bat）..."
$pkg = "$R\dist-client\pkg"
Remove-Item -Recurse -Force $pkg -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$pkg\patch-files" | Out-Null
# payload 放 patch-files\ 暫存區：install.bat 先驗 jar SHA／衝突，通過才複製到遊戲目錄並回驗
Copy-Item -Recurse "$R\dist-client\java\zombie" "$pkg\patch-files\zombie"

$jarSha   = (Get-FileHash -Algorithm SHA256 "$R\work\projectzomboid.jar").Hash.ToLower()
$tiamSha  = (Get-FileHash -Algorithm SHA256 "$R\dist-client\java\zombie\core\textures\TextureIDAssetManager.class").Hash.ToLower()
$guardSha = (Get-FileHash -Algorithm SHA256 "$R\dist-client\java\zombie\mdc\TexturePipelineGuard.class").Hash.ToLower()
foreach ($bat in @('install', 'uninstall')) {
    $body = (Get-Content -Raw "$R\deploy-client\$bat.bat.template") `
        -replace '__JAR_SHA__', $jarSha `
        -replace '__TIAM_SHA__', $tiamSha `
        -replace '__GUARD_SHA__', $guardSha
    if ($body -match '__[A-Z_]+__') { throw "$bat.bat 模板還有未注入的 placeholder" }
    # CRLF 強制：LF-only 批次檔在 cmd 會出現幽靈解析錯誤（「這個時候不應有…」，實測）
    $body = $body -replace "`r?`n", "`r`n"
    # ASCII 無 BOM：cmd 對 BOM 開頭的 @echo off 會直接報錯
    [System.IO.File]::WriteAllText("$pkg\$bat.bat", $body, [System.Text.Encoding]::ASCII)
}
# 檔名一律 ASCII：Compress-Archive 對非 ASCII 檔名會寫出 OEM 亂碼 entry（實測）
Copy-Item "$R\deploy-client\README-INSTALL.txt" $pkg -Force
$zip = "$R\dist-client\MinidoracatClientPatch-TexPipeline-42.20.0-$PATCH_VERSION.zip"
Get-ChildItem "$R\dist-client\MinidoracatClientPatch-*.zip" -ErrorAction SilentlyContinue | Remove-Item
Compress-Archive -Path "$pkg\*" -DestinationPath $zip
Write-Host "完成：$zip"
