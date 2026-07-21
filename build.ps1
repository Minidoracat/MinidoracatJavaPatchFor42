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

Write-Host "[1/5] 編譯 patcher..."
javac -encoding UTF-8 -cp $ASM_CP -d "$R\work\out" (Get-ChildItem "$R\patcher\src\*.java").FullName
Assert-Ok "javac patcher"

Write-Host "[2/5] 編譯 LogFilter（對遊戲 jar）..."
javac -encoding UTF-8 -cp "$R\work\projectzomboid.jar" -d "$R\dist\java" (Get-ChildItem "$R\patcher\game" -Recurse -Filter *.java).FullName
Assert-Ok "javac LogFilter"

Write-Host "[3/5] 執行 bytecode 手術..."
java -cp "$R\work\out;$ASM_CP" Patcher "$R\work\projectzomboid.jar" "$R\dist\java" "$R\dist\manifest.txt"
Assert-Ok "Patcher"

# LogFilter 入 manifest（origSha=- 表無 jar 原版；install.sh preflight 據此驗 payload 完整性）
$lfSha = (Get-FileHash -Algorithm SHA256 "$R\dist\java\zombie\mdc\LogFilter.class").Hash.ToLower()
Add-Content -Path "$R\dist\manifest.txt" -Value "zombie/mdc/LogFilter.class`t-`t$lfSha`t0hits" -NoNewline
Add-Content -Path "$R\dist\manifest.txt" -Value "`n" -NoNewline

Write-Host "[4/6] 連結驗證（-Xverify:all）..."
java -Xverify:all -cp "$R\work\out" LoadCheck "$R\dist\java" "$R\work\projectzomboid.jar" "$R\dist\manifest.txt"
Assert-Ok "LoadCheck"

Write-Host "[5/6] JVMS 資料流驗證（CheckClassAdapter）..."
java -cp "$R\work\out;$ASM_CP" BytecodeVerify "$R\dist\java" "$R\work\projectzomboid.jar" "$R\dist\manifest.txt"
Assert-Ok "BytecodeVerify"

Write-Host "[6/6] 守衛語意驗證（smoke＋負對照＋結構斷言）..."
java -cp "$R\work\out;$ASM_CP" SmokeCheck "$R\dist\java" "$R\work\projectzomboid.jar"
Assert-Ok "SmokeCheck"

Copy-Item "$R\deploy\install.sh", "$R\deploy\uninstall.sh" "$R\dist\" -Force
Write-Host "完成：dist\java（loose classes）＋ dist\manifest.txt ＋ install/uninstall.sh"
