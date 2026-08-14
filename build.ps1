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

Remove-Item -Recurse -Force "$R\work\out", "$R\work\gen", "$R\dist\java" -ErrorAction SilentlyContinue
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
    'zombie/network/MinidoracatJoinMetrics.class',
    'zombie/mdc/VehicleIntersectPrefilter.class',
    'zombie/mdc/GlassAttachmentGuard.class',
    'zombie/mdc/ZombieAuthThrottle.class',
    'zombie/characters/animals/behavior/AnimalSpottedPrefilter.class',
    'zombie/mdc/VehicleCouldSeeGate.class',
    'zombie/mdc/ChunkRequestPacker.class',
    'zombie/mdc/ContainerCycleGuard.class',
    'zombie/mdc/ContainerCycleGuard$State.class',
    'zombie/mdc/ChunkLoadGuard.class',
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
java -cp "$R\work\out;$ASM_CP" SmokeCheck "$R\dist\java" "$R\work\projectzomboid.jar"
Assert-Ok "SmokeCheck"

Write-Host "[8/10] LoginMetrics／JoinMetrics 行為與例外 precedence 驗證..."
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.network.LoginMetricsBehaviorTest
Assert-Ok "LoginMetricsBehaviorTest"
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.network.JoinMetricsBehaviorTest
Assert-Ok "JoinMetricsBehaviorTest"

Write-Host "[9/10] entity removal 等價性、碰撞與 fallback 驗證..."
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.FastIdentityArrayRemovalTest
Assert-Ok "FastIdentityArrayRemovalTest"

Write-Host "[9a/10] 容器環守衛（W5）行為驗證（含原版必爆負對照）＋kill switch..."
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.ContainerCycleGuardTest
Assert-Ok "ContainerCycleGuardTest"
# 事故當下的緊急降級路徑，第一次跑它的時機不該是事故現場
java "-Dmdc.cycleGuard.maxDepth=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.ContainerCycleGuardTest
Assert-Ok "ContainerCycleGuardTest（maxDepth=0 kill switch）"

Write-Host "[9b/10] chunk 供給併包（W4-1）行為驗證＋kill switch 模式..."
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.ChunkRequestPackerTest
Assert-Ok "ChunkRequestPackerTest"
# 緊急降級旋鈕必須真的能停刀（正式服不重新部署即可回到 vanilla）
java "-Dmdc.chunkPacker.windowBudget=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.ChunkRequestPackerTest
Assert-Ok "ChunkRequestPackerTest（windowBudget=0 kill switch）"
java "-Dmdc.chunkPacker.batch=0" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.ChunkRequestPackerTest
Assert-Ok "ChunkRequestPackerTest（batch=0 kill switch）"

Write-Host "[9c/10] 地圖格載入捕手（W6）行為驗證（含替身必拋負對照）＋kill switch..."
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.ChunkLoadGuardTest
Assert-Ok "ChunkLoadGuardTest"
# 事故當下的緊急降級路徑，第一次跑它的時機不該是事故現場。
# 傳 disabled 讓測試自己斷言旋鈕真的生效——只看 exit code 的話，property 名稱打錯會變成
# 「把 enabled 版再跑一遍、照樣 exit 0」，降級路徑其實從未被測到。
java "-Dmdc.chunkLoadGuard.enabled=false" -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.ChunkLoadGuardTest disabled
Assert-Ok "ChunkLoadGuardTest（enabled=false kill switch）"

Write-Host "[10/10] entity removal 尺度 benchmark（時間只報告，不設機器相依閾值）..."
java -cp "$R\work\out;$R\dist\java;$R\work\projectzomboid.jar" zombie.mdc.FastIdentityArrayRemovalBenchmark
Assert-Ok "FastIdentityArrayRemovalBenchmark"

Copy-Item "$R\deploy\install.sh", "$R\deploy\uninstall.sh" "$R\dist\" -Force
Write-Host "完成：dist\java（loose classes）＋ dist\manifest.txt ＋ install/uninstall.sh"
