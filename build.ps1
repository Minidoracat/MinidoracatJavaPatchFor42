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

Remove-Item -Recurse -Force "$R\work\out", "$R\dist\java" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$R\work\out", "$R\dist\java" | Out-Null
$ASM_CP = "$R\lib\asm-9.8.jar;$R\lib\asm-tree-9.8.jar;$R\lib\asm-analysis-9.8.jar;$R\lib\asm-util-9.8.jar"

Write-Host "[1/10] 編譯 patcher..."
javac -encoding UTF-8 -cp $ASM_CP -d "$R\work\out" (Get-ChildItem "$R\patcher\src\*.java").FullName
Assert-Ok "javac patcher"

Write-Host "[2/10] 編譯 runtime helpers（對遊戲 jar）..."
javac -encoding UTF-8 -cp "$R\work\projectzomboid.jar" -d "$R\dist\java" (Get-ChildItem "$R\patcher\game" -Recurse -Filter *.java).FullName
Assert-Ok "javac runtime helpers"

Write-Host "[3/10] 編譯全部行為測試與 benchmark..."
javac -encoding UTF-8 -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" -d "$R\work\out" `
    (Get-ChildItem "$R\patcher\tests" -Recurse -Filter *.java).FullName
Assert-Ok "javac tests"

Write-Host "[4/10] 執行 bytecode 手術..."
java -cp "$R\work\out;$ASM_CP" Patcher "$R\work\projectzomboid.jar" "$R\dist\java" "$R\dist\manifest.txt"
Assert-Ok "Patcher"

# Runtime helpers 置於 manifest 最前（origSha=- 表無 jar 原版）；部署時先 helper、再 patched caller。
$helperEntries = @(
    'zombie/mdc/LogFilter.class',
    'zombie/network/MinidoracatLoginMetrics.class',
    'zombie/mdc/FastIdentityArrayRemoval.class',
    'zombie/mdc/FastIdentityArrayRemoval$State.class',
    'zombie/mdc/PopmanBufferGuard.class'
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

Write-Host "[5/10] 連結驗證（-Xverify:all）..."
java -Xverify:all -cp "$R\work\out" LoadCheck "$R\dist\java" "$R\work\projectzomboid.jar" "$R\dist\manifest.txt"
Assert-Ok "LoadCheck"

Write-Host "[6/10] JVMS 資料流驗證（CheckClassAdapter）..."
java -cp "$R\work\out;$ASM_CP" BytecodeVerify "$R\dist\java" "$R\work\projectzomboid.jar" "$R\dist\manifest.txt"
Assert-Ok "BytecodeVerify"

Write-Host "[7/10] 守衛語意驗證（smoke＋負對照＋結構斷言）..."
java -cp "$R\work\out;$ASM_CP" SmokeCheck "$R\dist\java" "$R\work\projectzomboid.jar"
Assert-Ok "SmokeCheck"

Write-Host "[8/10] LoginMetrics 行為與例外 precedence 驗證..."
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.network.LoginMetricsBehaviorTest
Assert-Ok "LoginMetricsBehaviorTest"

Write-Host "[9/10] entity removal 等價性、碰撞與 fallback 驗證..."
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.FastIdentityArrayRemovalTest
Assert-Ok "FastIdentityArrayRemovalTest"

Write-Host "[10/10] entity removal 尺度 benchmark（時間只報告，不設機器相依閾值）..."
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.FastIdentityArrayRemovalBenchmark
Assert-Ok "FastIdentityArrayRemovalBenchmark"

Copy-Item "$R\deploy\install.sh", "$R\deploy\uninstall.sh" "$R\dist\" -Force
Write-Host "完成：dist\java（loose classes）＋ dist\manifest.txt ＋ install/uninstall.sh"
