#Requires -Version 5.1
<#
    Run-InstallerTests.ps1 — deploy-client\Manage-Patches.ps1 的真實 CLI 往返測試

    完全在 %TEMP% 底下的合成遊戲目錄操作：假的 ProjectZomboid64.exe、用 zip 做的假 projectzomboid.jar
    （只有幾個 class 名稱，讓安裝器認得哪些 package 在 jar 裡）、原版格式的 ProjectZomboid64.json，
    ＋合成安裝包（agent jar 是純文字檔）。每個案例都真的用 `powershell.exe -File Manage-Patches.ps1 ...`
    跑一次，檢查結束代碼與磁碟結果（含啟動設定逐位元組比對）。

    絕對不會碰真實遊戲目錄或存檔：遊戲目錄路徑一律組在 [IO.Path]::GetTempPath() 底下，
    啟動前會再驗一次。

    用法：powershell -NoProfile -ExecutionPolicy Bypass -File .\Run-InstallerTests.ps1 [-KeepTemp]
#>
[CmdletBinding()]
param([switch]$KeepTemp)

$ErrorActionPreference = 'Stop'

# 從 PowerShell 7 終端機啟動時，PSModulePath 會把 pwsh 7 的模組目錄排在前面，
# Windows PowerShell 5.1 會撿到不相容的 Microsoft.PowerShell.Utility 而失去 Get-FileHash。
# 這裡（以及子行程）一律先剔除 pwsh 7 的項目。（.bat 用 set "PSModulePath=" 達到同樣效果）
$env:PSModulePath = (($env:PSModulePath -split ';') |
    Where-Object { $_ -and $_ -notmatch '(?i)\\PowerShell\\7\\' -and $_ -notmatch '(?i)\\Program Files\\PowerShell\\' -and $_ -notmatch '(?i)\\Documents\\PowerShell\\' }) -join ';'

$RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$Installer = Join-Path $RepoRoot 'deploy-client\Manage-Patches.ps1'
if (-not (Test-Path -LiteralPath $Installer)) { throw "找不到安裝器：$Installer" }

$TempBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$TempRoot = Join-Path $TempBase ('mdc-installer-tests-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
# 目錄名故意含空白、括號與中文：cmd／PowerShell 的路徑處理最常在這裡炸掉
$GameDir = Join-Path $TempRoot 'Project Zomboid (x86) 測試目錄'
if (-not ([System.IO.Path]::GetFullPath($GameDir)).StartsWith($TempBase, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "安全檢查失敗：測試遊戲目錄不在 TEMP 底下（$GameDir）"
}

$AGENT_REL = '.mdc-patches/MinidoracatClientPatches.jar'
$LOADER_REL = '.mdc-patches/mdc-agent-loader.dll'
$LAUNCHER_REL = 'ProjectZomboid64.json'
$ARG_PREFIX = "-agentpath:$LOADER_REL=$AGENT_REL=modules="
$C_LOADER = 'agent-loader-dll'

# ---------------------------------------------------------------- 測試框架
$script:Passed = 0
$script:Failed = 0
$script:FailedNames = @()
$script:Case = ''

function Start-Case([string]$name) {
    $script:Case = $name
    Write-Host ''
    Write-Host "=== $name" -ForegroundColor Cyan
}
function Check([bool]$ok, [string]$desc) {
    if ($ok) {
        $script:Passed++
        Write-Host "    [PASS] $desc" -ForegroundColor Green
    } else {
        $script:Failed++
        $script:FailedNames += "$($script:Case) -> $desc"
        Write-Host "    [FAIL] $desc" -ForegroundColor Red
        if ($script:LastOut) {
            Write-Host '    ---- 最近一次安裝器輸出 ----' -ForegroundColor DarkYellow
            foreach ($line in ($script:LastOut -split "`r?`n")) { Write-Host "    | $line" -ForegroundColor DarkYellow }
        }
    }
}

# ---------------------------------------------------------------- 檔案工具
function Write-TextFile([string]$path, [string]$text) {
    $dir = Split-Path -Parent $path
    if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))
}
function Get-Sha([string]$path) { (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Get-TextSha([string]$text) {
    $tmp = Join-Path $TempRoot ('sha-' + [Guid]::NewGuid().ToString('N').Substring(0, 8) + '.tmp')
    Write-TextFile $tmp $text
    $h = Get-Sha $tmp
    Remove-Item -LiteralPath $tmp -Force
    return $h
}
function GameFile([string]$rel) { return (Join-Path $GameDir ($rel -replace '/', '\')) }
function Test-GameFile([string]$rel) { return (Test-Path -LiteralPath (GameFile $rel) -PathType Leaf) }
function Get-GameText([string]$rel) { return [System.IO.File]::ReadAllText((GameFile $rel)) }
function Get-GameBytesSha([string]$rel) { return (Get-Sha (GameFile $rel)) }
function Test-GameFileIs([string]$rel, [string]$text) {
    if (-not (Test-GameFile $rel)) { return $false }
    return ((Get-GameText $rel) -eq $text)
}

# 假 projectzomboid.jar：真的 zip，只放 class 名稱（安裝器用它判斷哪些 package 在 jar 裡）
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
function New-FakeJar([string]$path, [string]$marker) {
    if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
    $zip = [System.IO.Compression.ZipFile]::Open($path, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($n in @('META-INF/MANIFEST.MF', 'zombie/iso/IsoChunk.class', 'zombie/iso/WorldStreamer.class',
                'zombie/core/textures/TextureIDAssetManager.class', 'zombie/vehicles/BaseVehicle.class',
                'zombie/Lua/LuaManager$Exposer.class', 'zombie/core/Core.class', 'zombie/other/Vanilla.class',
                'se/krka/kahlua/integration/LuaCaller.class')) {
            $w = New-Object System.IO.StreamWriter($zip.CreateEntry($n).Open())
            try { $w.Write("$marker $n") } finally { $w.Dispose() }
        }
    } finally { $zip.Dispose() }
}

# 原版 42.22.2 的 ProjectZomboid64.json（tab 縮排、LF）
$ORIG_JSON = "{`n`t`"mainClass`": `"zombie/gameStates/MainScreenState`",`n`t`"classpath`": [`n`t`t`".`",`n`t`t`"projectzomboid.jar`"`n`t],`n`t`"vmArgs`": [`n`t`t`"-Djava.awt.headless=true`",`n`t`t`"--enable-native-access=ALL-UNNAMED`",`n`t`t`"-Xmx3072m`",`n`t`t`"-Djava.library.path=win64/;.`"`n`t],`n`t`"windows`": {`n`t`t`"10.0.17134`": {`n`t`t`t`"vmArgs`": [`n`t`t`t`t`"-XX:+UseZGC`"`n`t`t`t]`n`t`t}`n`t}`n}`n"

function Reset-GameDir([string]$jar = $script:JarGood) {
    if (Test-Path -LiteralPath $GameDir) { Remove-Item -LiteralPath $GameDir -Recurse -Force }
    New-Item -ItemType Directory -Path $GameDir -Force | Out-Null
    Write-TextFile (Join-Path $GameDir 'ProjectZomboid64.exe') 'stub-exe'
    Copy-Item -LiteralPath $jar -Destination (Join-Path $GameDir 'projectzomboid.jar')
    Write-TextFile (GameFile $LAUNCHER_REL) $ORIG_JSON
}

function Get-InstalledState {
    $p = GameFile '.mdc-patches/state.json'
    if (-not (Test-Path -LiteralPath $p -PathType Leaf)) { return $null }
    return ([System.IO.File]::ReadAllText($p) | ConvertFrom-Json)
}
function Get-InstalledModuleIds {
    $s = Get-InstalledState
    if (-not $s) { return @() }
    if ($null -eq $s.modules) { return @() }
    return @(@($s.modules) | ForEach-Object { $_.id })
}
function Test-ModuleSet([string[]]$expected) {
    $actual = @(Get-InstalledModuleIds)
    return (((@($actual) | Sort-Object) -join ',') -eq ((@($expected) | Sort-Object) -join ','))
}
function Get-TopVmArgs { return @(([System.IO.File]::ReadAllText((GameFile $LAUNCHER_REL)) | ConvertFrom-Json).vmArgs) }
function Get-OurArgs { return @(Get-TopVmArgs | Where-Object { $_ -like '-agentpath:*mdc-agent-loader.dll*' }) }
# 啟動設定裡恰好一條自己的參數，模組清單（依 manifest 順序）正好是 $mods，而且排在頂層 vmArgs 最後
function Test-ArgModules([string]$mods) {
    $ours = @(Get-OurArgs)
    $all = @(Get-TopVmArgs)
    return ($ours.Count -eq 1 -and $ours[0] -ceq "$ARG_PREFIX$mods" -and $all[0] -ceq "$ARG_PREFIX$mods")
}
function Test-JsonIsOriginal { return ((Get-GameText $LAUNCHER_REL) -ceq $ORIG_JSON) }

# ---------------------------------------------------------------- 執行安裝器
# 預設 -Lang zh：中文斷言不受 Windows 顯示語言影響；英文案例明確傳 'en'。
# InputLines：互動案例依序餵給 Read-Host 的回答（stdin 讀完後 Read-Host 回 null，安裝器會以錯誤結束，不會卡住）。
function Invoke-Installer([string[]]$Arguments, [string]$Lang = 'zh', [string[]]$InputLines) {
    # 參數驗證失敗會寫 stderr；Windows PowerShell 在 Stop 下會把 2>&1 的 stderr 當終止錯誤
    $ErrorActionPreference = 'Continue'
    $full = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $Installer) + $Arguments + @('-Lang', $Lang)
    if ($InputLines) { $out = ($InputLines | & powershell.exe @full 2>&1 | Out-String) }
    else { $out = (& powershell.exe @full 2>&1 | Out-String) }
    $code = $LASTEXITCODE
    $script:LastOut = $out
    Write-Host "  > Manage-Patches.ps1 $($Arguments -join ' ') -Lang $Lang  => exit $code" -ForegroundColor DarkGray
    return @{ Code = $code; Out = $out }
}
function Invoke-Install([string[]]$mods, [string]$pkgDir) {
    return Invoke-Installer @('-NonInteractive', '-Action', 'install', '-GameDir', $GameDir, '-PackageDir', $pkgDir, '-Modules', ($mods -join ','))
}
function Invoke-Uninstall([string[]]$mods) {
    return Invoke-Installer @('-NonInteractive', '-Action', 'uninstall', '-GameDir', $GameDir, '-Modules', ($mods -join ','))
}
function Invoke-UninstallAll([string]$pkgDir) {
    $a = @('-NonInteractive', '-Action', 'uninstall', '-GameDir', $GameDir, '-All')
    if ($pkgDir) { $a += @('-PackageDir', $pkgDir) }
    return Invoke-Installer $a
}
function Invoke-Status([string]$pkgDir, [string]$Lang = 'zh') {
    $a = @('-NonInteractive', '-Action', 'status', '-GameDir', $GameDir)
    if ($pkgDir) { $a += @('-PackageDir', $pkgDir) }
    return Invoke-Installer $a $Lang
}

# ---------------------------------------------------------------- 合成安裝包
function New-TestPackage {
    param([string]$Dir, [string]$PackageVersion, [string]$JarSha, [string]$AgentText, [array]$ModuleSpecs, [array]$LegacySpecs)

    if (Test-Path -LiteralPath $Dir) { Remove-Item -LiteralPath $Dir -Recurse -Force }
    New-Item -ItemType Directory -Path $Dir -Force | Out-Null
    $agentSrc = 'agent/MinidoracatClientPatches.jar'
    $loaderSrc = 'agent/mdc-agent-loader.dll'
    Write-TextFile (Join-Path $Dir ($agentSrc -replace '/', '\')) $AgentText
    Write-TextFile (Join-Path $Dir ($loaderSrc -replace '/', '\')) $C_LOADER
    $mods = @()
    foreach ($m in $ModuleSpecs) {
        $entry = [ordered]@{ id = $m.id; name = $m.name; version = $m.version; requires = @($m.requires); patchedClasses = @($m.classes) }
        if ($m.group) { $entry['exclusiveGroup'] = $m.group }
        $mods += $entry
    }
    $manifest = [ordered]@{
        schemaVersion  = 2
        gameVersion    = '42.22.2-test'
        packageVersion = $PackageVersion
        jarSha256      = $JarSha
        agent          = [ordered]@{ source = $agentSrc; sha256 = (Get-TextSha $AgentText) }
        loader         = [ordered]@{ source = $loaderSrc; sha256 = (Get-TextSha $C_LOADER) }
        modules        = @($mods)
    }
    if ($LegacySpecs) {
        $legacy = @()
        foreach ($lp in $LegacySpecs) {
            $lf = @()
            foreach ($f in $lp.files) { $lf += [ordered]@{ path = $f.p; sha256 = (Get-TextSha $f.c) } }
            $legacy += [ordered]@{ id = $lp.id; modules = @($lp.modules); files = @($lf) }
        }
        $manifest['legacyPackages'] = @($legacy)
    }
    Write-TextFile (Join-Path $Dir 'manifest.json') (ConvertTo-Json -InputObject $manifest -Depth 12)
    return $Dir
}

# ---------------------------------------------------------------- 內容常數
$C_AGENT_V1 = 'agent-jar-v1'
$C_AGENT_V2 = 'agent-jar-v2-CHANGED'
$C_LEGACY_GUARD = 'legacy-v3.0-oldguard'
$C_LEGACY_TIDAM_STD = 'legacy-v3.0-standard-tidam'
$C_LEGACY_TIDAM_LOW = 'legacy-v3.0-lowmem-tidam'
$P_FIX_TIDAM = 'zombie/core/textures/TextureIDAssetManager.class'
$P_FIX_WS = 'zombie/iso/WorldStreamer.class'
$P_VEH = 'zombie/vehicles/BaseVehicle.class'
$P_LEGACY_GUARD = 'zombie/mdc/TexturePipelineGuard.class'
$JSON_AOT = '{"mainClass":"zombie/gameStates/MainScreenState","classpath":["pzopt/aot/pzopt.jar","projectzomboid.jar"],"vmArgs":["-Xmx4096m","-XX:AOTCacheOutput=pzopt/aot/pzopt.aot"]}'

$FullClasses = @('zombie/core/textures/TextureIDAssetManager', 'zombie/iso/WorldStreamer', 'zombie/iso/IsoChunk', 'zombie/vehicles/BaseVehicle')
$ModuleSpecs = @(
    @{ id = 'core'; name = '共用核心'; version = '0.1.0'; requires = @(); classes = @('zombie/Lua/LuaManager$Exposer', 'zombie/core/Core') }
    @{ id = 'profiler'; name = '開發效能分析器'; version = '0.1.0'; requires = @('core'); classes = @('se/krka/kahlua/integration/LuaCaller') }
    @{ id = 'client-fixes-standard'; name = '客戶端修復（標準）'; version = '3.0'; requires = @('core'); group = 'client-fixes'; classes = $FullClasses }
    @{ id = 'client-fixes-lowmem'; name = '客戶端修復（低記憶體）'; version = '3.0'; requires = @('core'); group = 'client-fixes'; classes = $FullClasses }
    @{ id = 'client-fixes-compat'; name = '客戶端修復・相容版'; version = '3.0'; requires = @('core'); group = 'client-fixes'; classes = @('zombie/vehicles/BaseVehicle') }
)
$LegacySpecs = @(
    @{ id = 'legacy-client-v3.0-standard'; modules = @('client-fixes-standard'); files = @(
            @{ p = $P_LEGACY_GUARD; c = $C_LEGACY_GUARD }
            @{ p = $P_FIX_TIDAM; c = $C_LEGACY_TIDAM_STD }
        )
    }
    @{ id = 'legacy-client-v3.0-lowmem'; modules = @('client-fixes-lowmem'); files = @(
            @{ p = $P_LEGACY_GUARD; c = $C_LEGACY_GUARD }
            @{ p = $P_FIX_TIDAM; c = $C_LEGACY_TIDAM_LOW }
        )
    }
)

# 0.2.x（state schemaVersion 1）的 loose class 安裝
$V1_FILES = @(
    @{ id = 'core'; files = @(@{ p = 'zombie/mdc/MdcPatchRuntime.class'; c = 'v1-runtime' }, @{ p = 'zombie/core/Core.class'; c = 'v1-core' }) }
    @{ id = 'client-fixes-lowmem'; files = @(@{ p = $P_FIX_TIDAM; c = 'v1-lowmem-tidam' }, @{ p = 'zombie/mdc/TexturePipelineGuard.class'; c = 'v1-guard' }) }
)
function Set-V1Install([string]$tamperPath) {
    $mods = @()
    foreach ($m in $V1_FILES) {
        $files = @()
        foreach ($f in $m.files) {
            Write-TextFile (GameFile $f.p) $f.c
            $files += [ordered]@{ path = $f.p; sha256 = (Get-TextSha $f.c) }
        }
        $mods += [ordered]@{ id = $m.id; name = $m.id; version = '0.2.11'; files = @($files) }
    }
    $state = [ordered]@{ schemaVersion = 1; jarSha256 = ('e1a69eb7' * 8); packageVersion = '0.2.11'; modules = @($mods) }
    Write-TextFile (GameFile '.mdc-patches/state.json') (ConvertTo-Json -InputObject $state -Depth 12)
    if ($tamperPath) { Write-TextFile (GameFile $tamperPath) 'hand-edited' }
}

# ---------------------------------------------------------------- 中斷交易佈景
# 模擬「安裝到一半被砍掉」：完整發布的新 agent jar ＋ 原檔備份 ＋ 未提交的 journal。
$TX_ID = 'deadbeefcafe'

function Set-PendingTransaction {
    param([switch]$SkipBackupFile, [string]$TxId = $TX_ID, $Entries = $null)
    if ($SkipBackupFile) {
        New-Item -ItemType Directory -Path (GameFile ".mdc-patches/backup/$TxId") -Force | Out-Null
    } else {
        Write-TextFile (GameFile ".mdc-patches/backup/$TxId/$AGENT_REL") $C_AGENT_V1
    }
    Write-TextFile (GameFile $AGENT_REL) $C_AGENT_V2
    if ($null -eq $Entries) {
        $Entries = @(
            [ordered]@{ path = $AGENT_REL; backup = $AGENT_REL
                backupSha256 = (Get-TextSha $C_AGENT_V1); sha256 = (Get-TextSha $C_AGENT_V2) }
            [ordered]@{ path = '.mdc-patches/state.json'; backup = $null; backupSha256 = $null; sha256 = $null }
        )
    }
    $journal = [ordered]@{
        schemaVersion = 1
        txId          = $TxId
        startedUtc    = '2026-01-01T00:00:00Z'
        committed     = $false
        entries       = @($Entries)
        createdDirs   = @()
    }
    Write-TextFile (GameFile '.mdc-patches/journal.json') (ConvertTo-Json -InputObject $journal -Depth 12)
}

# ---------------------------------------------------------------- 準備
New-Item -ItemType Directory -Path $TempRoot -Force | Out-Null
Write-Host "測試沙箱：$TempRoot" -ForegroundColor Yellow
$script:JarGood = Join-Path $TempRoot 'jar-good.jar'
$script:JarOther = Join-Path $TempRoot 'jar-other.jar'
New-FakeJar $script:JarGood 'build-42.22.2'
New-FakeJar $script:JarOther 'build-42.23.0'
Reset-GameDir
$JarSha = Get-Sha (Join-Path $GameDir 'projectzomboid.jar')

$PkgV1 = New-TestPackage -Dir (Join-Path $TempRoot 'pkg-v1') -PackageVersion 'v4.0' -JarSha $JarSha -AgentText $C_AGENT_V1 `
    -ModuleSpecs $ModuleSpecs -LegacySpecs $LegacySpecs
$PkgV2 = New-TestPackage -Dir (Join-Path $TempRoot 'pkg-v2') -PackageVersion 'v4.1' -JarSha $JarSha -AgentText $C_AGENT_V2 `
    -ModuleSpecs $ModuleSpecs -LegacySpecs $LegacySpecs
$PkgSolo = New-TestPackage -Dir (Join-Path $TempRoot 'pkg-solo') -PackageVersion 'v4.0-solo' -JarSha $JarSha -AgentText 'solo-agent' `
    -ModuleSpecs @(@{ id = 'solo'; name = '單一模組'; version = '0.1.0'; requires = @(); classes = @() })

try {
    # ------------------------------------------------------------ 1
    Start-Case '1. 乾淨目錄查狀態'
    $r = Invoke-Status $PkgV1
    Check ($r.Code -eq 0) '結束代碼 0'
    Check ($r.Out -match '未安裝任何模組') '報告未安裝'
    Check ($r.Out -match '可安裝') '列出可安裝模組'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches'))) 'status 不會建立任何狀態檔'
    Check (Test-JsonIsOriginal) 'status 不改啟動設定'

    # ------------------------------------------------------------ 2
    Start-Case '2. 選模組安裝（profiler 自動帶入 core）'
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '結束代碼 0'
    Check (Test-GameFileIs $AGENT_REL $C_AGENT_V1) 'agent jar 放進 .mdc-patches'
    Check (Test-GameFileIs $LOADER_REL $C_LOADER) 'agent 載入器放進 .mdc-patches'
    Check (Test-ArgModules 'core,profiler') '啟動設定加上一條 -agentpath（載入器＝agent jar＝core,profiler），排在頂層 vmArgs 最前面'
    $j = [System.IO.File]::ReadAllText((GameFile $LAUNCHER_REL)) | ConvertFrom-Json
    Check (@($j.windows.'10.0.17134'.vmArgs) -join ',' -ceq '-XX:+UseZGC') 'windows 區段沒動'
    Check ((@(Get-TopVmArgs) | Select-Object -Skip 1) -join ',' -ceq '-Djava.awt.headless=true,--enable-native-access=ALL-UNNAMED,-Xmx3072m,-Djava.library.path=win64/;.') '其他 vmArgs 原樣、原順序'
    Check (Test-ModuleSet @('core', 'profiler')) 'state.json 記錄 core + profiler'
    Check ((Get-InstalledState).schemaVersion -eq 2 -and (Get-InstalledState).agent.path -ceq $AGENT_REL) 'state.json 是 schemaVersion 2，記錄 agent 路徑'
    Check ((Get-InstalledState).agent.sha256 -eq (Get-TextSha $C_AGENT_V1)) 'state.json 記錄 agent SHA'
    Check ((Get-InstalledState).loader.path -ceq $LOADER_REL -and (Get-InstalledState).loader.sha256 -eq (Get-TextSha $C_LOADER)) 'state.json 記錄 agent 載入器的路徑與 SHA'
    Check ((Get-InstalledState).packageVersion -eq 'v4.0') 'state.json 記錄 packageVersion'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches/journal.json'))) '沒有殘留 journal'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches/backup'))) '沒有殘留備份'

    # ------------------------------------------------------------ 3
    Start-Case '3. 加裝 client-fixes-standard（保留既有模組）'
    $r = Invoke-Install @('client-fixes-standard') $PkgV1
    Check ($r.Code -eq 0) '結束代碼 0'
    Check (Test-ArgModules 'core,profiler,client-fixes-standard') '啟動參數換成三個模組，仍只有一條'
    Check (Test-ModuleSet @('core', 'profiler', 'client-fixes-standard')) 'state.json 三個模組'

    # ------------------------------------------------------------ 4
    Start-Case '4. 切換互斥變體 standard -> lowmem'
    $r = Invoke-Install @('client-fixes-lowmem') $PkgV1
    Check ($r.Code -eq 0) '結束代碼 0'
    Check (Test-ArgModules 'core,profiler,client-fixes-lowmem') '啟動參數換成 lowmem'
    Check (Test-ModuleSet @('core', 'profiler', 'client-fixes-lowmem')) 'state.json 只留 lowmem 變體'

    # ------------------------------------------------------------ 5
    Start-Case '5. 卸載單一模組（profiler）不影響其它模組'
    $r = Invoke-Uninstall @('profiler')
    Check ($r.Code -eq 0) '結束代碼 0'
    Check (Test-ArgModules 'core,client-fixes-lowmem') '啟動參數只剩 core + lowmem'
    Check (Test-GameFileIs $AGENT_REL $C_AGENT_V1) 'agent jar 保留'
    Check (Test-ModuleSet @('core', 'client-fixes-lowmem')) 'state.json 只剩 core + lowmem'

    # ------------------------------------------------------------ 6
    Start-Case '6. 只卸 core：仍被依賴時自動保留'
    $r = Invoke-Uninstall @('core')
    Check ($r.Code -eq 0) '結束代碼 0'
    Check ($r.Out -match '仍被其它保留的模組需要') '有說明 core 被保留'
    Check (Test-ArgModules 'core,client-fixes-lowmem') '啟動參數不變'
    Check (Test-ModuleSet @('core', 'client-fixes-lowmem')) 'state.json 不變'

    # ------------------------------------------------------------ 7
    Start-Case '7. 全部卸載（-All）：啟動設定逐位元組回到原樣，不碰別人的檔案'
    Write-TextFile (GameFile 'zombie/other/SomeoneElseMod.class') 'third-party-file'
    $r = Invoke-UninstallAll
    Check ($r.Code -eq 0) '結束代碼 0'
    Check (Test-JsonIsOriginal) 'ProjectZomboid64.json 與安裝前逐位元組相同'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches'))) '.mdc-patches（agent jar、state）已清掉'
    Check (Test-GameFileIs 'zombie/other/SomeoneElseMod.class' 'third-party-file') '第三方檔案原封不動'

    # ------------------------------------------------------------ 8
    Start-Case '8. 遊戲版本不符：拒裝且不寫任何檔案'
    Reset-GameDir $script:JarOther
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 5) '結束代碼 5（版本不符）'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches'))) '沒有建立任何檔案'
    Check (Test-JsonIsOriginal) '啟動設定沒被改'

    # ------------------------------------------------------------ 9
    Start-Case '9. 外來 loose 檔蓋掉要修補的 class：拒裝'
    Reset-GameDir
    Write-TextFile (GameFile $P_FIX_WS) 'another-patch-owns-this'
    $r = Invoke-Install @('client-fixes-standard') $PkgV1
    Check ($r.Code -eq 7) '結束代碼 7（衝突）'
    Check ($r.Out -match '蓋掉本包要修補的 class') '說明是蓋掉要修補的 class'
    Check (Test-GameFileIs $P_FIX_WS 'another-patch-owns-this') '外來檔案沒有被動'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches'))) '沒有建立狀態檔'
    Check (Test-JsonIsOriginal) '啟動設定沒被改'

    # ------------------------------------------------------------ 10
    Start-Case '10. 舊版 v3.0 包整組吻合：同一筆交易刪掉它的 loose class'
    Reset-GameDir
    Write-TextFile (GameFile $P_LEGACY_GUARD) $C_LEGACY_GUARD
    Write-TextFile (GameFile $P_FIX_TIDAM) $C_LEGACY_TIDAM_STD
    $r = Invoke-Install @('client-fixes-standard') $PkgV1
    Check ($r.Code -eq 0) '結束代碼 0'
    Check ($r.Out -match 'legacy-client-v3\.0-standard') '有辨識出舊版包'
    Check (-not (Test-GameFile $P_FIX_TIDAM) -and -not (Test-GameFile $P_LEGACY_GUARD)) '舊版包的 loose class 全部刪掉'
    Check (-not (Test-Path -LiteralPath (GameFile 'zombie'))) '清空的目錄一併移除'
    Check (Test-ModuleSet @('core', 'client-fixes-standard')) 'state.json 正確'
    Check (Test-ArgModules 'core,client-fixes-standard') '啟動參數正確'
    $r = Invoke-UninstallAll
    Check ($r.Code -eq 0 -and (Test-JsonIsOriginal) -and -not (Test-Path -LiteralPath (GameFile '.mdc-patches'))) '接管後可以正常全部卸載'

    # ------------------------------------------------------------ 11
    Start-Case '11. 舊版包只吻合一部分：拒裝（不猜版本）'
    Reset-GameDir
    Write-TextFile (GameFile $P_LEGACY_GUARD) $C_LEGACY_GUARD
    $r = Invoke-Install @('client-fixes-standard') $PkgV1
    Check ($r.Code -eq 7) '結束代碼 7（無法辨識的舊版 patch）'
    Check (Test-GameFileIs $P_LEGACY_GUARD $C_LEGACY_GUARD) '舊檔沒有被刪'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches'))) '沒有安裝任何東西'
    Check ($r.Out -match 'uninstall') '有指引使用者用舊版 uninstaller'

    # ------------------------------------------------------------ 12
    Start-Case '12. agent jar 被變造：全部卸載拒絕刪除'
    Reset-GameDir
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '前置安裝成功'
    Write-TextFile (GameFile $AGENT_REL) 'user-hand-edited-this'
    $r = Invoke-UninstallAll
    Check ($r.Code -eq 8) '結束代碼 8（檔案被變造）'
    Check (Test-GameFileIs $AGENT_REL 'user-hand-edited-this') '變造的檔案沒有被刪'
    Check (Test-ArgModules 'core,profiler') '啟動設定沒被改（整筆中止）'
    Check (Test-ModuleSet @('core', 'profiler')) 'state.json 沒有被更動'

    # ------------------------------------------------------------ 13
    Start-Case '13. 寫入中途失敗：完整回復，既有安裝不毀'
    Reset-GameDir
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '前置安裝 v1 成功'
    $jsonBefore = Get-GameText $LAUNCHER_REL
    # 只拿 Read 存取＋FileShare.Read 開著啟動設定：讀得到（前置檢查與備份會過），但原子替換會被擋下，
    # 交易正好在新 agent jar 已寫入、啟動設定還沒換時失敗。
    $locked = [System.IO.File]::Open((GameFile $LAUNCHER_REL), [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read)
    try {
        $r = Invoke-Install @('client-fixes-standard') $PkgV2
    } finally {
        $locked.Close(); $locked.Dispose()
    }
    Check ($r.Code -eq 9) '結束代碼 9（已回復）'
    Check (Test-GameFileIs $AGENT_REL $C_AGENT_V1) '先寫進去的 agent jar 已回復成安裝前內容'
    Check ((Get-GameText $LAUNCHER_REL) -ceq $jsonBefore) '啟動設定維持原內容'
    Check ((Get-InstalledState).packageVersion -eq 'v4.0') 'state.json 仍是安裝前的版本'
    Check (Test-ModuleSet @('core', 'profiler')) 'state.json 模組清單不變'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches/journal.json'))) 'journal 已清除'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches/backup'))) '備份已清除'

    # ------------------------------------------------------------ 14
    Start-Case '14. 中斷的交易：status 唯讀不復原，install 才復原'
    Reset-GameDir
    Set-PendingTransaction
    $r = Invoke-Status
    Check ($r.Code -eq 0) 'status 結束代碼 0'
    Check ($r.Out -match '未完成') 'status 有提醒有未完成的交易'
    Check (Test-GameFileIs $AGENT_REL $C_AGENT_V2) 'status 沒有動尚未提交的檔案'
    Check (Test-Path -LiteralPath (GameFile '.mdc-patches/journal.json')) 'status 沒有清掉 journal'
    Check (Test-Path -LiteralPath (GameFile ".mdc-patches/backup/$TX_ID")) 'status 沒有清掉備份'
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) 'install 結束代碼 0'
    Check ($r.Out -match '復原') 'install 動手前先復原了上一次的中斷交易'
    Check (Test-GameFileIs $AGENT_REL $C_AGENT_V1) 'agent jar 是這次安裝的內容'
    Check (Test-ModuleSet @('core', 'profiler')) '復原後照常完成安裝'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches/journal.json'))) 'journal 已清除'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches/backup'))) '備份目錄已清除'

    # ------------------------------------------------------------ 15
    Start-Case '15. 參數錯誤都給非 0 且可分辨的代碼'
    Reset-GameDir
    $r = Invoke-Installer @('-NonInteractive', '-Action', 'menu', '-GameDir', $GameDir, '-PackageDir', $PkgV1)
    Check ($r.Code -eq 2) 'menu + -NonInteractive => 2'
    $r = Invoke-Installer @('-NonInteractive', '-Action', 'install', '-GameDir', $GameDir, '-PackageDir', $PkgV1)
    Check ($r.Code -eq 2) 'install 未指定模組 => 2'
    $r = Invoke-Install @('no-such-module') $PkgV1
    Check ($r.Code -eq 2) '未知模組 id => 2'
    $r = Invoke-Install @('client-fixes-standard', 'client-fixes-lowmem') $PkgV1
    Check ($r.Code -eq 2) '同時指定兩個互斥變體 => 2'
    $r = Invoke-Installer @('-NonInteractive', '-Action', 'status', '-GameDir', (Join-Path $TempRoot 'not-a-game'))
    Check ($r.Code -eq 3) '不是遊戲目錄 => 3'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches')) -and (Test-JsonIsOriginal)) '所有參數錯誤都沒有寫入任何東西'

    # ------------------------------------------------------------ 16
    Start-Case '16. -All 安裝：互斥組取 manifest 預設變體'
    Reset-GameDir
    $r = Invoke-Installer @('-NonInteractive', '-Action', 'install', '-GameDir', $GameDir, '-PackageDir', $PkgV1, '-All')
    Check ($r.Code -eq 0) '結束代碼 0'
    Check (Test-ModuleSet @('core', 'profiler', 'client-fixes-standard')) '裝到 core + profiler + 預設變體'
    Check (Test-ArgModules 'core,profiler,client-fixes-standard') '啟動參數正確'
    $r = Invoke-Installer @('-NonInteractive', '-Action', 'install', '-GameDir', $GameDir, '-PackageDir', $PkgV1, '-All')
    Check ($r.Code -eq 0) '重複執行不報錯'
    Check ($r.Out -match '不需要任何變更') '重複執行判定為無變更'

    # ------------------------------------------------------------ 17
    Start-Case '17. 卸載後可以重裝（往返一輪）'
    $r = Invoke-UninstallAll
    Check ($r.Code -eq 0 -and (Test-JsonIsOriginal)) '全卸成功、啟動設定回原樣'
    $r = Invoke-Install @('profiler', 'client-fixes-lowmem') $PkgV1
    Check ($r.Code -eq 0) '重裝成功'
    Check (Test-ModuleSet @('core', 'profiler', 'client-fixes-lowmem')) '重裝後模組正確'
    Check (Test-ArgModules 'core,profiler,client-fixes-lowmem') '重裝後啟動參數正確'
    $r = Invoke-Status $PkgV1
    Check ($r.Code -eq 0) 'status 成功'
    Check ($r.Out -match '正常' -and $r.Out -match '已啟用') 'status 回報安裝完整、已啟用'

    # ------------------------------------------------------------ 18
    Start-Case '18. 復原失敗：journal 與備份都保留，修好後可重跑'
    Reset-GameDir
    Set-PendingTransaction -SkipBackupFile
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 10) '結束代碼 10（回復失敗）'
    Check ($r.Out -match '備份檔遺失') '有指出備份檔遺失'
    Check (Test-Path -LiteralPath (GameFile '.mdc-patches/journal.json')) 'journal 保留著（還能重跑復原）'
    Check (Test-Path -LiteralPath (GameFile ".mdc-patches/backup/$TX_ID")) '備份目錄保留著'
    Check (Test-GameFileIs $AGENT_REL $C_AGENT_V2) '沒有半套復原'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches/state.json'))) '沒有寫入任何安裝狀態'
    Write-TextFile (GameFile ".mdc-patches/backup/$TX_ID/$AGENT_REL") $C_AGENT_V1
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '補回備份後重跑成功'
    Check (Test-GameFileIs $AGENT_REL $C_AGENT_V1) '這次有復原成功並完成安裝'
    Check (Test-ModuleSet @('core', 'profiler')) '復原後完成安裝'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches/backup'))) '成功之後才清掉備份'

    # ------------------------------------------------------------ 19
    Start-Case '19. 被竄改的 journal（路徑跳出遊戲目錄）：拒絕依它動檔案'
    Reset-GameDir
    Set-PendingTransaction -Entries @(
        [ordered]@{ path = '../evil-outside.class'; backup = '../evil-outside.class' }
        [ordered]@{ path = $AGENT_REL; backup = $AGENT_REL }
    )
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 7) '結束代碼 7（路徑不安全）'
    Check (-not (Test-Path -LiteralPath (Join-Path $TempRoot 'evil-outside.class'))) '沒有寫到遊戲目錄外'
    Check (Test-Path -LiteralPath (GameFile '.mdc-patches/journal.json')) 'journal 留著給人工檢查'
    Check (Test-GameFileIs $AGENT_REL $C_AGENT_V2) '一個檔案都沒動'
    Check (Test-JsonIsOriginal) '啟動設定沒動'

    # ------------------------------------------------------------ 20
    Start-Case '20. journal 的 txId 格式不合法：拒絕'
    Reset-GameDir
    Set-PendingTransaction -TxId 'ZZZZZZZZZZZZ'
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 7) '結束代碼 7'
    Check ($r.Out -match 'txId') '有指出 txId 不合法'
    Check (Test-Path -LiteralPath (GameFile '.mdc-patches/journal.json')) 'journal 留著'
    Check (Test-GameFileIs $AGENT_REL $C_AGENT_V2) '沒有動任何檔案'

    # ------------------------------------------------------------ 21
    Start-Case '21. journal 的 backup 指向別的路徑、或目標是別的檔案：拒絕'
    Reset-GameDir
    Set-PendingTransaction -Entries @(
        [ordered]@{ path = $AGENT_REL; backup = 'zombie/mdc/Other.class'
            backupSha256 = (Get-TextSha $C_AGENT_V1); sha256 = (Get-TextSha $C_AGENT_V2) }
        [ordered]@{ path = '.mdc-patches/state.json'; backup = $null; backupSha256 = $null; sha256 = $null }
    )
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 7) '備份路徑不符：結束代碼 7'
    Check (Test-GameFileIs $AGENT_REL $C_AGENT_V2) '沒有被亂還原成別的檔案內容'
    Reset-GameDir
    Set-PendingTransaction -Entries @(
        [ordered]@{ path = 'projectzomboid.jar'; backup = $null; backupSha256 = $null; sha256 = (Get-TextSha 'x') }
    )
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 7) '目標是遊戲 jar：結束代碼 7（交易只准碰自己的檔案與啟動設定）'
    Check ((Get-Sha (GameFile 'projectzomboid.jar')) -eq $JarSha) '遊戲 jar 沒被動'

    # ------------------------------------------------------------ 22
    Start-Case '22. -All 不會把已安裝的 lowmem 換成預設 standard'
    Reset-GameDir
    $r = Invoke-Install @('client-fixes-lowmem') $PkgV1
    Check ($r.Code -eq 0) '前置：先裝 lowmem'
    $r = Invoke-Installer @('-NonInteractive', '-Action', 'install', '-GameDir', $GameDir, '-PackageDir', $PkgV1, '-All')
    Check ($r.Code -eq 0) '-All 結束代碼 0'
    Check (Test-ModuleSet @('core', 'profiler', 'client-fixes-lowmem')) '-All 沿用已安裝的變體並補上其它模組'
    Check (Test-ArgModules 'core,profiler,client-fixes-lowmem') '啟動參數仍是 lowmem'

    # ------------------------------------------------------------ 23
    Start-Case '23. 只加裝 profiler 不會丟掉辨識出的舊版客戶端修復'
    Reset-GameDir
    Write-TextFile (GameFile $P_LEGACY_GUARD) $C_LEGACY_GUARD
    Write-TextFile (GameFile $P_FIX_TIDAM) $C_LEGACY_TIDAM_LOW
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '結束代碼 0'
    Check (Test-ModuleSet @('core', 'profiler', 'client-fixes-lowmem')) '舊版修復被接管成對應模組，而不是被丟掉'
    Check (Test-ArgModules 'core,profiler,client-fixes-lowmem') '啟動參數含 lowmem'
    Check (-not (Test-GameFile $P_LEGACY_GUARD) -and -not (Test-GameFile $P_FIX_TIDAM)) '舊版 loose class 清掉'

    # ------------------------------------------------------------ 24
    Start-Case '24. 沒有 state.json：整組吻合的舊版包可卸載，認不出的殘檔明確拒絕'
    Reset-GameDir
    Write-TextFile (GameFile $P_LEGACY_GUARD) $C_LEGACY_GUARD
    Write-TextFile (GameFile $P_FIX_TIDAM) $C_LEGACY_TIDAM_STD
    $r = Invoke-Installer @('-NonInteractive', '-Action', 'uninstall', '-GameDir', $GameDir, '-PackageDir', $PkgV1, '-Modules', 'profiler')
    Check ($r.Code -eq 2) '非互動又沒有 -All：舊版包只能整組移除（代碼 2）'
    Check (Test-GameFileIs $P_LEGACY_GUARD $C_LEGACY_GUARD) '被擋下時舊版檔沒有被刪'
    $r = Invoke-UninstallAll $PkgV1
    Check ($r.Code -eq 0) '整組吻合：結束代碼 0'
    Check ($r.Out -match 'legacy-client-v3\.0-standard') '卸載計畫列出辨識到的舊版包'
    Check ($r.Out -notmatch '沒有東西需要卸載') '沒有回報「沒東西要卸載」'
    Check (-not (Test-GameFile $P_LEGACY_GUARD) -and -not (Test-GameFile $P_FIX_TIDAM)) '舊版包的檔案都已移除'
    Check (-not (Test-Path -LiteralPath (GameFile 'zombie'))) '清空的目錄一併移除'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches')) -and (Test-JsonIsOriginal)) '沒有留下狀態目錄、啟動設定沒動'
    Reset-GameDir
    Write-TextFile (GameFile $P_LEGACY_GUARD) $C_LEGACY_GUARD
    $r = Invoke-UninstallAll $PkgV1
    Check ($r.Code -eq 7) '只吻合一部分：結束代碼 7（拒絕）'
    Check ($r.Out -match 'uninstall') '有指引使用者用舊版 uninstaller'
    Check (Test-GameFileIs $P_LEGACY_GUARD $C_LEGACY_GUARD) '舊版檔沒有被刪'
    $r = Invoke-UninstallAll
    Check ($r.Code -eq 7) '沒有 manifest 也一樣拒絕（自家 namespace 還有 class）'
    $r = Invoke-Installer @('-Action', 'uninstall', '-GameDir', $GameDir, '-PackageDir', $PkgV1)
    Check ($r.Code -eq 7) '雙擊卸載入口同樣拒絕，不能由空 state 分支回報成功'

    # ------------------------------------------------------------ 25
    Start-Case '25. 單模組的 state.json 仍是 JSON array'
    Reset-GameDir
    $r = Invoke-Install @('solo') $PkgSolo
    Check ($r.Code -eq 0) '結束代碼 0'
    Check (Test-ModuleSet @('solo')) '只有一個模組'
    Check (Test-ArgModules 'solo') '啟動參數只有一個模組'
    $rawState = [System.IO.File]::ReadAllText((GameFile '.mdc-patches/state.json'))
    Check ($rawState -match '"modules"\s*:\s*\[') 'modules 是 JSON array（不是單一物件）'

    # ------------------------------------------------------------ 26
    Start-Case '26. 同一遊戲目錄的並行操作被鎖擋下'
    Reset-GameDir
    $alias = Join-Path $TempRoot 'game-alias'
    New-Item -ItemType Junction -Path $alias -Target $GameDir | Out-Null
    $otherLock = [System.IO.File]::Open((Join-Path $alias 'ProjectZomboid64.exe'),
        [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::None)
    try {
        $r = Invoke-Install @('profiler') $PkgV1
    } finally {
        $otherLock.Dispose()
        [System.IO.Directory]::Delete($alias)
    }
    Check ($r.Code -eq 4) '結束代碼 4（另一個安裝器正在處理同一個目錄）'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches')) -and (Test-JsonIsOriginal)) '被擋下時沒有寫入任何東西'
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '鎖釋放後可以正常安裝'

    # ------------------------------------------------------------ 27
    # 沒辦法在測試裡真的開一份 Project Zomboid，但可以做出「行程名叫 java、
    # 命令列含遊戲目錄」的外部 JDK 情境——這正是只看 Process.Path 會漏掉的那條。
    Start-Case '27. 外部 JDK 執行中（命令列含遊戲目錄）：拒絕動檔案'
    Reset-GameDir
    $fakeJavaDir = Join-Path $TempRoot 'fake-jdk'
    New-Item -ItemType Directory -Path $fakeJavaDir -Force | Out-Null
    $fakeJava = Join-Path $fakeJavaDir 'java.exe'
    Copy-Item -LiteralPath (Join-Path $env:WINDIR 'System32\cmd.exe') -Destination $fakeJava -Force
    $fakeProc = Start-Process -FilePath $fakeJava -ArgumentList @('/k', "rem `"$GameDir`"") -PassThru -WindowStyle Hidden
    try {
        Start-Sleep -Milliseconds 800
        $r = Invoke-Install @('profiler') $PkgV1
    } finally {
        Stop-Process -Id $fakeProc.Id -Force -ErrorAction SilentlyContinue
    }
    Check ($r.Code -eq 4) '結束代碼 4（JVM 使用中）'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches')) -and (Test-JsonIsOriginal)) '沒有寫入任何東西'

    Start-Case '28. journal 不能以復原名義刪除或覆寫第三方 class'
    foreach ($withBackup in @($false, $true)) {
        Reset-GameDir
        $foreign = 'zombie/other/SomeoneElseMod.class'
        Write-TextFile (GameFile $foreign) 'foreign-original'
        $backup = $null
        $backupSha = $null
        if ($withBackup) {
            $backup = $foreign
            Write-TextFile (GameFile ".mdc-patches/backup/$TX_ID/$foreign") 'foreign-replacement'
            $backupSha = Get-TextSha 'foreign-replacement'
        }
        Set-PendingTransaction -Entries @(
            [ordered]@{ path = $foreign; backup = $backup; backupSha256 = $backupSha; sha256 = (Get-TextSha 'intended-payload') }
            [ordered]@{ path = '.mdc-patches/state.json'; backup = $null; backupSha256 = $null; sha256 = $null }
        )
        $r = Invoke-Install @('profiler') $PkgV1
        Check ($r.Code -eq 7) "不明所有權拒絕（backup=$withBackup）"
        Check (Test-GameFileIs $foreign 'foreign-original') '第三方 class 原封不動'
        Check (Test-Path -LiteralPath (GameFile '.mdc-patches/journal.json')) '保留交易紀錄供檢查'
    }

    Start-Case '29. 單筆 state 復原不依賴已損毀的現況或安裝包'
    Reset-GameDir
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '先建立完整安裝'
    $beforeState = Get-GameText '.mdc-patches/state.json'
    $afterState = $beforeState.Replace('v4.0', 'v4.1')
    $agentNow = Get-GameText $AGENT_REL
    Remove-Item -LiteralPath (GameFile $AGENT_REL)
    Set-PendingTransaction -Entries @([ordered]@{
        path = '.mdc-patches/state.json'; backup = '.mdc-patches/state.json'
        backupSha256 = (Get-TextSha $beforeState); sha256 = (Get-TextSha $afterState)
    })
    Write-TextFile (GameFile $AGENT_REL) $agentNow
    Write-TextFile (GameFile ".mdc-patches/backup/$TX_ID/.mdc-patches/state.json") $beforeState
    Write-TextFile (GameFile '.mdc-patches/state.json') $afterState
    $r = Invoke-Uninstall @('profiler')
    Check ($r.Code -eq 0) '無 manifest 也能以備份 state 復原再卸載'
    Check (Test-ModuleSet @('core')) '單筆交易還原後正確保留 core'
    Check (Test-ArgModules 'core') '啟動參數只剩 core'

    Start-Case '30. journal 不得清除與交易無關的空目錄'
    Reset-GameDir
    Set-PendingTransaction
    New-Item -ItemType Directory -Path (GameFile 'foreign-empty') | Out-Null
    $journalPath = GameFile '.mdc-patches/journal.json'
    $journal = [System.IO.File]::ReadAllText($journalPath) | ConvertFrom-Json
    $journal.createdDirs = @('foreign-empty')
    Write-TextFile $journalPath (ConvertTo-Json -InputObject $journal -Depth 12)
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 7) '不相關目錄使整筆復原拒絕'
    Check (Test-Path -LiteralPath (GameFile 'foreign-empty') -PathType Container) '第三方空目錄仍存在'
    Check (Test-GameFileIs $AGENT_REL $C_AGENT_V2) '拒絕發生在任何檔案復原之前'

    Start-Case '31. 檔案早已就位但沒有安裝紀錄：仍要寫下 state.json'
    Reset-GameDir
    Write-TextFile (GameFile $AGENT_REL) $C_AGENT_V1
    $r = Invoke-Install @('core') $PkgV1
    Check ($r.Code -eq 0) '結束代碼 0'
    Check (Test-ModuleSet @('core')) '寫下了 state.json'
    Check (Test-ArgModules 'core') '補上啟動參數'
    $r = Invoke-Install @('core') $PkgV1
    Check ($r.Code -eq 0 -and $r.Out -match '不需要任何變更') '再裝一次判定為無變更'

    Start-Case '32. 狀態目錄被換成目錄接點：拒絕，不寫到遊戲目錄外'
    Reset-GameDir
    $outsideState = Join-Path $TempRoot 'outside-state'
    if (Test-Path -LiteralPath $outsideState) { Remove-Item -LiteralPath $outsideState -Recurse -Force }
    New-Item -ItemType Directory -Path $outsideState -Force | Out-Null
    New-Item -ItemType Junction -Path (GameFile '.mdc-patches') -Target $outsideState | Out-Null
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 7) '結束代碼 7（路徑上有接點）'
    Check (@(Get-ChildItem -LiteralPath $outsideState -Force).Count -eq 0) '沒有把 agent jar／state／journal／備份寫到遊戲目錄外'
    Check (Test-JsonIsOriginal) '啟動設定沒動'
    # 只拆接點本身，不能讓後續的 Remove-Item -Recurse 穿過去砍掉目標目錄
    [System.IO.Directory]::Delete((GameFile '.mdc-patches'))

    Start-Case '33. manifest 的 agent source 指向安裝包外：拒絕'
    Reset-GameDir
    $PkgEvilSource = Join-Path $TempRoot 'pkg-evil-source'
    if (Test-Path -LiteralPath $PkgEvilSource) { Remove-Item -LiteralPath $PkgEvilSource -Recurse -Force }
    Copy-Item -LiteralPath $PkgSolo -Destination $PkgEvilSource -Recurse -Force
    Write-TextFile (Join-Path $TempRoot 'outside-agent.jar') 'solo-agent'
    $evilManifestPath = Join-Path $PkgEvilSource 'manifest.json'
    $evilManifest = [System.IO.File]::ReadAllText($evilManifestPath) | ConvertFrom-Json
    $evilManifest.agent.source = '../outside-agent.jar'
    Write-TextFile $evilManifestPath (ConvertTo-Json -InputObject $evilManifest -Depth 12)
    $r = Invoke-Install @('solo') $PkgEvilSource
    Check ($r.Code -eq 7) '結束代碼 7（來源路徑不安全）'
    Check (-not (Test-GameFile $AGENT_REL)) '沒有從安裝包外複製檔案進遊戲目錄'

    Start-Case '34. manifest 的模組 id 含會寫進啟動參數的特殊字元：以安裝包錯誤拒絕'
    Reset-GameDir
    $PkgBadId = Join-Path $TempRoot 'pkg-bad-id'
    if (Test-Path -LiteralPath $PkgBadId) { Remove-Item -LiteralPath $PkgBadId -Recurse -Force }
    Copy-Item -LiteralPath $PkgSolo -Destination $PkgBadId -Recurse -Force
    $badManifestPath = Join-Path $PkgBadId 'manifest.json'
    $badManifest = [System.IO.File]::ReadAllText($badManifestPath) | ConvertFrom-Json
    $badManifest.modules[0].id = 'solo,evil" -Xmx1m'
    Write-TextFile $badManifestPath (ConvertTo-Json -InputObject $badManifest -Depth 12)
    $r = Invoke-Install @('solo') $PkgBadId
    Check ($r.Code -eq 6) '結束代碼 6，而不是寫進啟動設定'
    Check ((Test-JsonIsOriginal) -and -not (Test-Path -LiteralPath (GameFile '.mdc-patches'))) '沒有寫入任何東西'

    Start-Case '35. 沒有 journal 的備份：保留資料，不擅自當成可刪孤兒'
    Reset-GameDir
    Write-TextFile (GameFile ".mdc-patches/backup/$TX_ID/$AGENT_REL") $C_AGENT_V1
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '結束代碼 0'
    Check (Test-GameFileIs ".mdc-patches/backup/$TX_ID/$AGENT_REL" $C_AGENT_V1) '未辨識備份保留原內容'
    Check (Test-ModuleSet @('core', 'profiler')) '安裝照常完成'

    Start-Case '36. 舊版紀錄裡的 Windows 裝置名稱：在任何寫入前拒絕'
    Reset-GameDir
    $deviceState = [ordered]@{ schemaVersion = 1; jarSha256 = ('0' * 64); packageVersion = '0.2.11'
        modules = @([ordered]@{ id = 'core'; name = 'core'; version = '0.2.11'; files = @([ordered]@{ path = 'zombie/mdc/NUL.class'; sha256 = ('0' * 64) }) }) }
    Write-TextFile (GameFile '.mdc-patches/state.json') (ConvertTo-Json -InputObject $deviceState -Depth 12)
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 7) '裝置路徑以不安全路徑拒絕'
    Check (-not (Test-GameFile $AGENT_REL) -and (Test-JsonIsOriginal)) '尚未寫入任何東西'

    Start-Case '37. 預測得到的暫存路徑連到其他檔案：不得截斷外部資料'
    Reset-GameDir
    $sentinel = Join-Path $TempRoot 'unrelated-sentinel.txt'
    Write-TextFile $sentinel 'unrelated original bytes'
    New-Item -ItemType Directory -Path (GameFile '.mdc-patches') -Force | Out-Null
    New-Item -ItemType HardLink -Path (GameFile '.mdc-patches/state.json.tmp') -Target $sentinel | Out-Null
    New-Item -ItemType HardLink -Path (GameFile '.mdc-patches/journal.json.tmp') -Target $sentinel | Out-Null
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '安裝成功'
    Check ([System.IO.File]::ReadAllText($sentinel) -eq 'unrelated original bytes') '外部資料完全保留'
    Check (Test-ModuleSet @('core', 'profiler')) '安裝紀錄完整'

    Start-Case '38. 明示遊戲目錄無效：不得改用有效的環境變數目錄'
    Reset-GameDir
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '先建立可卸載的對照安裝'
    $previousGameDir = $env:PZ_GAMEDIR
    try {
        $env:PZ_GAMEDIR = $GameDir
        $r = Invoke-Installer @('-NonInteractive', '-Action', 'uninstall', '-All',
            '-GameDir', (Join-Path $TempRoot 'missing-game'))
        Check ($r.Code -eq 3) '無效明示路徑以 exit 3 拒絕'
        Check (Test-ModuleSet @('core', 'profiler')) '沒有卸載另一份有效遊戲'
        Check (Test-ArgModules 'core,profiler') '另一份遊戲的啟動設定不變'
    } finally { $env:PZ_GAMEDIR = $previousGameDir }

    Start-Case '39. CLI 大寫模組 ID：切換變體仍安裝指定的模組'
    Reset-GameDir
    $r = Invoke-Install @('client-fixes-standard') $PkgV1
    Check ($r.Code -eq 0) '先安裝 standard'
    $r = Invoke-Install @('CLIENT-FIXES-LOWMEM') $PkgV1
    Check ($r.Code -eq 0) '大小寫不影響合法模組選擇'
    Check (Test-ModuleSet @('core', 'client-fixes-lowmem')) 'lowmem 確實取代 standard'
    Check (Test-ArgModules 'core,client-fixes-lowmem') '啟動參數用 manifest 的小寫 id'

    Start-Case '40. agent 來源子目錄是接點：SHA 相同仍不得離開套件'
    Reset-GameDir
    $PkgJunctionSource = Join-Path $TempRoot 'pkg-junction-source'
    Copy-Item -LiteralPath $PkgSolo -Destination $PkgJunctionSource -Recurse -Force
    $outsideAgent = Join-Path $TempRoot 'outside-agent-dir'
    Move-Item -LiteralPath (Join-Path $PkgJunctionSource 'agent') -Destination $outsideAgent
    New-Item -ItemType Junction -Path (Join-Path $PkgJunctionSource 'agent') -Target $outsideAgent | Out-Null
    try {
        $r = Invoke-Install @('solo') $PkgJunctionSource
        Check ($r.Code -eq 7) '來源接點以 exit 7 拒絕'
        Check (-not (Test-GameFile $AGENT_REL)) '沒有複製套件外的來源檔'
    } finally { [System.IO.Directory]::Delete((Join-Path $PkgJunctionSource 'agent')) }

    Start-Case '41. 已知 class 路徑上的第三方內容不能由 journal 冒認'
    Reset-GameDir
    Write-TextFile (GameFile $P_FIX_WS) 'third-party-content'
    Set-PendingTransaction -Entries @(
        [ordered]@{ path = $P_FIX_WS; backup = $null; backupSha256 = $null; sha256 = (Get-TextSha 'our-old-worldstreamer') }
    )
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 7) '內容不是該交易的影像，拒絕復原'
    Check (Test-GameFileIs $P_FIX_WS 'third-party-content') '已知路徑也不會刪除不明內容'
    Check (Test-GameFile '.mdc-patches/journal.json') 'journal 保留'

    Start-Case '42. 完整發布後被外部改動：保留現況與備份'
    Reset-GameDir
    Set-PendingTransaction
    Write-TextFile (GameFile $AGENT_REL) 'unknown-external-change'
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 7) '未知影像拒絕'
    Check (Test-GameFileIs $AGENT_REL 'unknown-external-change') '未知現況不被覆蓋'
    Check (Test-GameFileIs ".mdc-patches/backup/$TX_ID/$AGENT_REL" $C_AGENT_V1) '前影像備份保留'
    Write-TextFile (GameFile $AGENT_REL) $C_AGENT_V1
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '人工還原可辨認影像後可繼續'

    Start-Case '43. 復原終態無法發布：不能先刪唯一備份'
    Reset-GameDir
    Set-PendingTransaction
    $journalLock = [System.IO.File]::Open((GameFile '.mdc-patches/journal.json'),
        [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read)
    try { $r = Invoke-Install @('profiler') $PkgV1 }
    finally { $journalLock.Dispose() }
    Check ($r.Code -eq 10) '終態寫入受阻，明確回報復原未完成'
    Check (Test-GameFileIs $AGENT_REL $C_AGENT_V1) 'agent jar 已正確復原'
    Check (Test-GameFileIs ".mdc-patches/backup/$TX_ID/$AGENT_REL" $C_AGENT_V1) '未發布終態前備份仍在'
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '釋放 journal 後安全重入'
    Check (-not (Test-GameFile '.mdc-patches/journal.json')) '完成後清除交易'

    Start-Case '44. agent jar 是 hardlink：修復時不能寫穿外部檔案'
    Reset-GameDir
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '前置安裝成功'
    $sentinel = Join-Path $TempRoot 'hardlink-agent-sentinel.txt'
    Write-TextFile $sentinel 'keep-external-bytes'
    Remove-Item -LiteralPath (GameFile $AGENT_REL)
    New-Item -ItemType HardLink -Path (GameFile $AGENT_REL) -Target $sentinel | Out-Null
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '只替換遊戲目錄內的檔名'
    Check ($r.Out -match '將重新寫入修復') '提示是修復'
    Check (Test-GameFileIs $AGENT_REL $C_AGENT_V1) 'agent jar 修復成功'
    Check ([System.IO.File]::ReadAllText($sentinel) -eq 'keep-external-bytes') '外部同 inode 內容保持不變'

    Start-Case '45. 備份損毀：整批復原之前拒絕'
    Reset-GameDir
    Set-PendingTransaction
    Write-TextFile (GameFile ".mdc-patches/backup/$TX_ID/$AGENT_REL") 'corrupt-backup'
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 10) '備份指紋不符'
    Check (Test-GameFileIs $AGENT_REL $C_AGENT_V2) '沒有用損毀備份蓋掉現況'
    Check (Test-GameFile '.mdc-patches/journal.json') '保留交易供修復備份後重跑'

    Start-Case '46. 復原目標變成目錄：不得複製進目錄後冒稱成功'
    Reset-GameDir
    Set-PendingTransaction
    Remove-Item -LiteralPath (GameFile $AGENT_REL)
    New-Item -ItemType Directory -Path (GameFile $AGENT_REL) | Out-Null
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 7) '目標類型不同，拒絕'
    Check (@(Get-ChildItem -LiteralPath (GameFile $AGENT_REL)).Count -eq 0) '沒有把 backup 複製到錯誤子路徑'
    Check (Test-GameFileIs ".mdc-patches/backup/$TX_ID/$AGENT_REL" $C_AGENT_V1) '唯一備份保留'

    Start-Case '47. 全新安裝中斷且原包已不在：由交易影像復原（含啟動設定）'
    Reset-GameDir
    $jsonWithArg = $ORIG_JSON.Replace("`t`t`"-Djava.library.path=win64/;.`"`n", "`t`t`"-Djava.library.path=win64/;.`",`n`t`t`"$($ARG_PREFIX)core`"`n")
    Write-TextFile (GameFile $AGENT_REL) $C_AGENT_V1
    Write-TextFile (GameFile ".mdc-patches/backup/$TX_ID/$LAUNCHER_REL") $ORIG_JSON
    Write-TextFile (GameFile $LAUNCHER_REL) $jsonWithArg
    $journal = [ordered]@{
        schemaVersion = 1; txId = $TX_ID; committed = $false
        entries = @(
            [ordered]@{ path = $AGENT_REL; backup = $null; backupSha256 = $null; sha256 = (Get-TextSha $C_AGENT_V1) }
            [ordered]@{ path = $LAUNCHER_REL; backup = $LAUNCHER_REL; backupSha256 = (Get-TextSha $ORIG_JSON); sha256 = (Get-TextSha $jsonWithArg) }
        )
        createdDirs = @()
    }
    Write-TextFile (GameFile '.mdc-patches/journal.json') (ConvertTo-Json -InputObject $journal -Depth 12)
    $r = Invoke-UninstallAll
    Check ($r.Code -eq 0) '不依賴 manifest 或尚未提交的 state'
    Check (-not (Test-GameFile $AGENT_REL)) '本交易寫入的 agent jar 被移除'
    Check (Test-JsonIsOriginal) '啟動設定回到交易前'
    Check (-not (Test-GameFile '.mdc-patches/journal.json')) '交易已安全清除'

    # ------------------------------------------------------------ 48-51：英文介面
    # 遊戲目錄名本身含中文，檢查 CJK 前先把路徑拿掉
    $cjk = '[\p{IsCJKUnifiedIdeographs}\u3000-\u303f\uff00-\uffef]'

    Start-Case '48. -Lang en：乾淨目錄查狀態輸出英文'
    Reset-GameDir
    $r = Invoke-Status $PkgV1 'en'
    Check ($r.Code -eq 0) '結束代碼 0'
    Check ($r.Out -match 'no modules installed') '報告未安裝（英文）'
    Check ($r.Out -match 'Available') '列出可安裝模組（英文）'
    Check ($r.Out.Replace($GameDir, '') -notmatch $cjk) 'status 輸出沒有中日韓字元'

    Start-Case '49. -Lang en：安裝結果與中文路徑相同'
    Reset-GameDir
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '中文對照安裝成功'
    $zhState = [System.IO.File]::ReadAllText((GameFile '.mdc-patches/state.json'))
    $zhJson = Get-GameText $LAUNCHER_REL
    Reset-GameDir
    $r = Invoke-Installer @('-NonInteractive', '-Action', 'install', '-GameDir', $GameDir, '-PackageDir', $PkgV1, '-Modules', 'profiler') 'en'
    Check ($r.Code -eq 0) '結束代碼 0'
    Check (Test-ModuleSet @('core', 'profiler')) '安裝 core + profiler'
    Check ([System.IO.File]::ReadAllText((GameFile '.mdc-patches/state.json')) -eq $zhState) 'state.json 與中文安裝完全相同'
    Check ((Get-GameText $LAUNCHER_REL) -ceq $zhJson) '啟動設定與中文安裝完全相同'
    Check ($r.Out -match 'Done: 2 module\(s\) installed') '英文成功訊息'
    Check ($r.Out.Replace($GameDir, '') -notmatch $cjk) 'install 輸出沒有中日韓字元'

    Start-Case '50. -Lang en：參數錯誤給英文訊息與代碼 2'
    Reset-GameDir
    $r = Invoke-Installer @('-NonInteractive', '-Action', 'install', '-GameDir', $GameDir, '-PackageDir', $PkgV1) 'en'
    Check ($r.Code -eq 2) 'install 未指定模組 => 2'
    Check ($r.Out -match 'No modules to install were given') '英文錯誤訊息'
    Check ($r.Out -match 'Exit code: 2') '英文結束代碼行'

    Start-Case '51. -Lang 只接受 auto/zh/en'
    Reset-GameDir
    $r = Invoke-Status $PkgV1 'xx'
    Check ($r.Code -ne 0) '非法語言代碼 => 非 0'
    Check ($r.Out -match 'ParameterArgumentValidationError') '由參數驗證擋下'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches'))) '沒有寫入任何東西'

    # ------------------------------------------------------------ 52-57：和替換遊戲 class 的其他 MOD 共存
    Start-Case '52. 別的 MOD 蓋掉完整版要修補的 class：拒裝並指向相容版，相容版裝得上'
    Reset-GameDir
    Write-TextFile (GameFile $P_FIX_TIDAM) 'pzopt-tidam'
    $r = Invoke-Install @('client-fixes-standard') $PkgV1
    Check ($r.Code -eq 7) '完整版結束代碼 7'
    Check ($r.Out -match 'client-fixes-compat') '訊息指向 client-fixes-compat'
    Check ($r.Out -match '不要只刪除上面列出的檔案') '提醒不要只刪列出的檔案'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches')) -and (Test-JsonIsOriginal)) '沒有寫入任何東西'
    $r = Invoke-Installer @('-NonInteractive', '-Action', 'install', '-GameDir', $GameDir, '-PackageDir', $PkgV1, '-Modules', 'client-fixes-standard') 'en'
    Check ($r.Code -eq 7 -and $r.Out -match 'install the compatible version instead') '英文訊息同樣指向相容版'
    $r = Invoke-Install @('client-fixes-compat') $PkgV1
    Check ($r.Code -eq 0) '相容版結束代碼 0'
    Check ($r.Out -match 'SecurityException') '警告外來 class 在 42.22 本身就會出事'
    Check (Test-ModuleSet @('core', 'client-fixes-compat')) 'state.json 記錄 core + compat'
    Check (Test-ArgModules 'core,client-fixes-compat') '啟動參數是相容版'
    Check (Test-GameFileIs $P_FIX_TIDAM 'pzopt-tidam') '別的 MOD 的檔案沒被動'
    Reset-GameDir
    Write-TextFile (GameFile $P_VEH) 'pzopt-vehicle'
    $r = Invoke-Install @('client-fixes-standard') $PkgV1
    Check ($r.Code -eq 7) '相容版也會撞到的 class：結束代碼 7'
    Check ($r.Out -notmatch 'client-fixes-compat') '不建議裝不上的相容版'
    Check ($r.Out -match '自己的卸載器把它完整移除後再安裝') '給一般的移除指引'
    Check (Test-GameFileIs $P_VEH 'pzopt-vehicle') '外來檔案原封不動'

    Start-Case '53. 互動選單：完整版裝不上時預設推薦相容版'
    Reset-GameDir
    Write-TextFile (GameFile $P_FIX_TIDAM) 'pzopt-tidam'
    $r = Invoke-Installer @('-Action', 'install', '-GameDir', $GameDir, '-PackageDir', $PkgV1) 'zh' @('1', '', 'Y')
    Check ($r.Code -eq 0) '直接按 Enter 安裝成功'
    Check ($r.Out -match '相容版：只含車輛的修補') '選單列出相容版'
    Check ($r.Out -match '建議選 \[3\] 相容版') '推薦相容版並說明原因'
    Check (Test-ModuleSet @('core', 'client-fixes-compat')) '預設選到相容版'
    Reset-GameDir
    $r = Invoke-Installer @('-Action', 'install', '-GameDir', $GameDir, '-PackageDir', $PkgV1) 'zh' @('1', '2', 'Y')
    Check ($r.Code -eq 0) '乾淨目錄的選單照常運作'
    Check ($r.Out -notmatch '完整版裝不上') '乾淨目錄不推薦相容版'
    Check (Test-ModuleSet @('core', 'client-fixes-lowmem')) '照選擇安裝省記憶體版'

    Start-Case '54. 同一個 class 的外來內部類別也算蓋掉；名字相近的別的 class 只警告'
    Reset-GameDir
    $foreignInner = 'zombie/vehicles/BaseVehicle$Foreign.class'
    Write-TextFile (GameFile $foreignInner) 'pzopt-inner'
    $r = Invoke-Install @('client-fixes-compat') $PkgV1
    Check ($r.Code -eq 7) '結束代碼 7'
    Check ($r.Out.Contains($foreignInner)) '訊息指出那個內部類別檔'
    Check (Test-GameFileIs $foreignInner 'pzopt-inner') '外來檔案原封不動'
    Reset-GameDir
    Write-TextFile (GameFile 'zombie/vehicles/basevehicle$lower.class') 'pzopt-inner'
    $r = Invoke-Install @('client-fixes-compat') $PkgV1
    Check ($r.Code -eq 7) '檔名大小寫不同也算同一個 class'
    Reset-GameDir
    Write-TextFile (GameFile 'zombie/vehicles/BaseVehicleHelper.class') 'other-class'
    $r = Invoke-Install @('client-fixes-compat') $PkgV1
    Check ($r.Code -eq 0) '名字相近的別的 class 不擋安裝'
    Check ($r.Out -match 'SecurityException') '但警告它在 42.22 本身就會出事'
    Check (Test-GameFileIs 'zombie/vehicles/BaseVehicleHelper.class' 'other-class') '別的 class 原封不動'

    Start-Case '55. PZ_Optimization AOT 快取：拒絕安裝；卸載照樣能清掉自己那一條'
    Reset-GameDir
    Write-TextFile (GameFile $LAUNCHER_REL) $JSON_AOT
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 7) 'AOT 快取開著：安裝結束代碼 7'
    Check ($r.Out -match 'aotCache') '告訴使用者怎麼關 AOT 快取'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches'))) '沒有寫入任何檔案'
    Check ((Get-GameText $LAUNCHER_REL) -ceq $JSON_AOT) '啟動設定沒被改'
    $r = Invoke-Status $PkgV1
    Check ($r.Code -eq 0 -and $r.Out -match 'aotCache') 'status 只警告不失敗'
    Reset-GameDir
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '前置安裝成功'
    # 模擬 pzopt 之後把啟動設定改成 AOT 形態（它只改自己的參數，別人的照留）
    $aotWithOurs = $JSON_AOT.Replace('"-XX:AOTCacheOutput=pzopt/aot/pzopt.aot"]', "`"-XX:AOTCacheOutput=pzopt/aot/pzopt.aot`",`"$($ARG_PREFIX)core,profiler`"]")
    Write-TextFile (GameFile $LAUNCHER_REL) $aotWithOurs
    $r = Invoke-UninstallAll
    Check ($r.Code -eq 0) 'AOT 形態下全部卸載成功'
    Check ((@(Get-TopVmArgs) -join '|') -ceq '-Xmx4096m|-XX:AOTCacheOutput=pzopt/aot/pzopt.aot') '只拿掉自己那一條，pzopt 的參數保留'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches'))) 'agent jar 與 state 都清掉'

    Start-Case '56. 啟動設定的其他形狀'
    Reset-GameDir
    Write-TextFile (GameFile $LAUNCHER_REL) '{"classpath":["projectzomboid.jar","."],"vmArgs":[]}'
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '. 排在 jar 後面不影響 agent：可以安裝'
    Check (Test-ArgModules 'core,profiler') '空的 vmArgs 也能加上參數'
    Reset-GameDir
    Write-TextFile (GameFile $LAUNCHER_REL) '{"classpath":["other.jar","projectzomboid.jar"],"vmArgs":[]}'
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 7 -and $r.Out -match '驗證遊戲檔案的完整性' -and $r.Out -notmatch 'aotCache') 'jar 前面另有 jar：結束代碼 7，給還原的一般指引'
    Reset-GameDir
    Write-TextFile (GameFile $LAUNCHER_REL) '{not json'
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 7 -and $r.Out -match 'ProjectZomboid64.json 無法解析') '無法解析：結束代碼 7'
    Reset-GameDir
    Write-TextFile (GameFile $LAUNCHER_REL) '{"classpath":[".","projectzomboid.jar"]}'
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 7 -and $r.Out -match '格式和原版不同') '沒有 vmArgs：結束代碼 7'
    Reset-GameDir
    Remove-Item -LiteralPath (GameFile $LAUNCHER_REL)
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 7 -and $r.Out -match '沒有 ProjectZomboid64.json') '沒有啟動設定：結束代碼 7'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches'))) '以上都沒有寫入任何檔案'

    Start-Case '57. 舊版包登記的原版 class 路徑上是別的 MOD 的檔案：不當成認不出的舊版包'
    Reset-GameDir
    Write-TextFile (GameFile $P_FIX_TIDAM) 'pzopt-tidam'
    $r = Invoke-Install @('client-fixes-compat') $PkgV1
    Check ($r.Code -eq 0) '相容版結束代碼 0'
    Check ($r.Out -notmatch '舊版 patch') '沒有誤報成舊版包'
    Check (Test-GameFileIs $P_FIX_TIDAM 'pzopt-tidam') '別的 MOD 的檔案沒被動'
    $r = Invoke-Install @('client-fixes-standard') $PkgV1
    Check ($r.Code -eq 7 -and $r.Out -notmatch '舊版 patch' -and $r.Out -match 'client-fixes-compat') '完整版以一般衝突拒裝並指向相容版，不說是舊版包'
    Reset-GameDir
    Write-TextFile (GameFile $P_FIX_TIDAM) 'pzopt-tidam'
    $r = Invoke-UninstallAll $PkgV1
    Check ($r.Code -eq 0 -and $r.Out -match '沒有東西需要卸載') '沒裝過本包時卸載回報沒東西可卸，不誤報舊版包'
    Write-TextFile (GameFile $P_LEGACY_GUARD) 'unknown-old-guard'
    $r = Invoke-Install @('client-fixes-compat') $PkgV1
    Check ($r.Code -eq 7 -and $r.Out -match '舊版 patch') '舊版包自己的 helper 還在時照樣當成認不出的舊版包擋下'

    # ------------------------------------------------------------ 58-66：agent 機制
    Start-Case '58. 從 0.2.x loose class 安裝升級：同一筆交易刪掉舊檔，模組沿用'
    Reset-GameDir
    Set-V1Install
    $r = Invoke-Status $PkgV1
    Check ($r.Code -eq 0 -and $r.Out -match '0\.2\.x 的舊版安裝' -and $r.Out -match 'SecurityException') 'status 說明舊版安裝在 42.22 會出事'
    $r = Invoke-Installer @('-NonInteractive', '-Action', 'install', '-GameDir', $GameDir, '-PackageDir', $PkgV1, '-All')
    Check ($r.Code -eq 0) '結束代碼 0'
    Check ($r.Out -match '移除舊版 0\.2\.11') '安裝計畫列出要移除舊版 loose class'
    Check (-not (Test-Path -LiteralPath (GameFile 'zombie'))) '0.2.x 的 loose class（含 zombie/mdc helper）全部刪掉'
    Check (Test-ModuleSet @('core', 'profiler', 'client-fixes-lowmem')) '沿用舊安裝的 lowmem 變體，-All 補上 profiler'
    Check ((Get-InstalledState).schemaVersion -eq 2) 'state.json 升級成 schemaVersion 2'
    Check (Test-ArgModules 'core,profiler,client-fixes-lowmem') '啟動參數正確'
    Check (Test-GameFileIs $AGENT_REL $C_AGENT_V1) 'agent jar 已放好'

    Start-Case '59. 0.2.x 的檔案被改過：拒絕升級，什麼都不動'
    Reset-GameDir
    Set-V1Install 'zombie/core/Core.class'
    $r = Invoke-Install @('client-fixes-standard') $PkgV1
    Check ($r.Code -eq 8) '結束代碼 8'
    Check (Test-GameFileIs 'zombie/core/Core.class' 'hand-edited') '被改過的檔案沒被刪'
    Check (Test-GameFileIs 'zombie/mdc/MdcPatchRuntime.class' 'v1-runtime') '其他舊檔也沒動（整筆中止）'
    Check (-not (Test-GameFile $AGENT_REL) -and (Test-JsonIsOriginal)) '沒有寫入 agent 或啟動參數'

    Start-Case '60. 0.2.x 安裝只能整組移除'
    Reset-GameDir
    Set-V1Install
    $r = Invoke-Uninstall @('client-fixes-lowmem')
    Check ($r.Code -eq 2) '非互動只移除一部分：代碼 2'
    Check (Test-GameFile $P_FIX_TIDAM) '被擋下時沒刪檔'
    $r = Invoke-UninstallAll
    Check ($r.Code -eq 0) '-All 結束代碼 0'
    Check (-not (Test-Path -LiteralPath (GameFile 'zombie')) -and -not (Test-Path -LiteralPath (GameFile '.mdc-patches'))) 'loose class 與 state 都清掉'
    Check (Test-JsonIsOriginal) '啟動設定沒動'

    Start-Case '61. 啟動設定被 Steam 換回原版：status 說沒啟用，卸載照樣清檔，重裝補回'
    Reset-GameDir
    $r = Invoke-Install @('client-fixes-standard') $PkgV1
    Check ($r.Code -eq 0) '前置安裝成功'
    Write-TextFile (GameFile $LAUNCHER_REL) $ORIG_JSON
    $r = Invoke-Status $PkgV1
    Check ($r.Code -eq 0 -and $r.Out -match '已安裝但沒有啟用' -and $r.Out -match '遊戲更新後要重新安裝') 'status：已安裝但沒有啟用，遊戲更新後要重新安裝新版'
    $r = Invoke-Uninstall @('client-fixes-standard')
    Check ($r.Code -eq 0 -and (Test-JsonIsOriginal)) '只移除一部分時不把參數補回去'
    Check (Test-ModuleSet @('core')) '紀錄只剩 core'
    $r = Invoke-Install @('client-fixes-standard') $PkgV1
    Check ($r.Code -eq 0 -and (Test-ArgModules 'core,client-fixes-standard')) '重裝補回啟動參數'
    Write-TextFile (GameFile $LAUNCHER_REL) $ORIG_JSON
    $r = Invoke-UninstallAll
    Check ($r.Code -eq 0) '全部卸載結束代碼 0'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches')) -and (Test-JsonIsOriginal)) 'agent jar 與 state 清掉，啟動設定不變'

    Start-Case '62. 保留使用者自己的修改與其他 Java agent，卸載後逐位元組回到使用者的版本'
    Reset-GameDir
    $userJson = $ORIG_JSON.Replace('"-Xmx3072m"', '"-Xmx8g"').Replace("`t`t`"-Djava.library.path=win64/;.`"`n", "`t`t`"-Djava.library.path=win64/;.`",`n`t`t`"-javaagent:ZombieBuddy.jar`",`n`t`t`"-Duser.language=CH`"`n").Replace('"-XX:+UseZGC"', "`"-XX:+UseZGC`",`n`t`t`t`t`"-agentlib:zbNative`"")
    Write-TextFile (GameFile $LAUNCHER_REL) $userJson
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '結束代碼 0'
    Check ($r.Out -match 'ZombieBuddy\.jar' -and $r.Out -match 'zbNative') '列出其他 Java agent、說明不動它們'
    $args62 = @(Get-TopVmArgs)
    Check (($args62 -join '|') -ceq "$($ARG_PREFIX)core,profiler|-Djava.awt.headless=true|--enable-native-access=ALL-UNNAMED|-Xmx8g|-Djava.library.path=win64/;.|-javaagent:ZombieBuddy.jar|-Duser.language=CH") '使用者的參數原樣、自己那條排在最前面（先於其他 Java agent）'
    $j = [System.IO.File]::ReadAllText((GameFile $LAUNCHER_REL)) | ConvertFrom-Json
    Check ((@($j.windows.'10.0.17134'.vmArgs) -join '|') -ceq '-XX:+UseZGC|-agentlib:zbNative') 'windows 區段原樣'
    $r = Invoke-UninstallAll
    Check ($r.Code -eq 0 -and (Get-GameText $LAUNCHER_REL) -ceq $userJson) '卸載後逐位元組回到使用者的版本'

    Start-Case '63. 自己的參數重複或寫成絕對路徑：安裝後只剩一條'
    Reset-GameDir
    $dupJson = $ORIG_JSON.Replace("`t`t`"-Djava.library.path=win64/;.`"`n", "`t`t`"$($ARG_PREFIX)core`",`n`t`t`"-Djava.library.path=win64/;.`",`n`t`t`"-agentpath:C:\\Games\\PZ\\.mdc-patches\\mdc-agent-loader.dll=C:\\Games\\PZ\\.mdc-patches\\MinidoracatClientPatches.jar=modules=core`",`n`t`t`"-javaagent:$AGENT_REL=modules=core`"`n")
    Write-TextFile (GameFile $LAUNCHER_REL) $dupJson
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0 -and (Test-ArgModules 'core,profiler')) '只剩一條正確的參數'
    Check (@(Get-TopVmArgs | Where-Object { $_ -like '*MinidoracatClientPatches*' }).Count -eq 1) '舊的三條都拿掉了（含直接以 -javaagent 指向本包 jar 的寫法）'

    Start-Case '64. 帶 BOM、CRLF 的啟動設定：保留 BOM 與換行'
    Reset-GameDir
    $crlf = $ORIG_JSON.Replace("`n", "`r`n")
    [System.IO.File]::WriteAllText((GameFile $LAUNCHER_REL), $crlf, (New-Object System.Text.UTF8Encoding($true)))
    $bomSha = Get-GameBytesSha $LAUNCHER_REL
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0 -and (Test-ArgModules 'core,profiler')) '安裝成功'
    $raw = [System.IO.File]::ReadAllBytes((GameFile $LAUNCHER_REL))
    Check ($raw[0] -eq 0xEF -and $raw[1] -eq 0xBB -and $raw[2] -eq 0xBF) 'BOM 保留'
    Check (-not ([System.Text.Encoding]::UTF8.GetString($raw) -match "[^`r]`n")) '換行仍是 CRLF'
    $r = Invoke-UninstallAll
    Check ($r.Code -eq 0 -and (Get-GameBytesSha $LAUNCHER_REL) -eq $bomSha) '卸載後逐位元組回到原檔'

    Start-Case '65. agent jar 被手動刪掉：status 警告遊戲會起不來，卸載拿掉啟動參數'
    Reset-GameDir
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '前置安裝成功'
    Remove-Item -LiteralPath (GameFile $AGENT_REL)
    $r = Invoke-Status $PkgV1
    Check ($r.Code -eq 0 -and $r.Out -match '遊戲會起不來') 'status 警告'
    $r = Invoke-UninstallAll
    Check ($r.Code -eq 0 -and (Test-JsonIsOriginal) -and -not (Test-Path -LiteralPath (GameFile '.mdc-patches'))) '卸載後啟動設定回原樣、狀態清掉'

    Start-Case '66. 遊戲更新換了 jar：status 說明修補不會啟用；新版包照常安裝'
    Reset-GameDir
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '前置安裝成功'
    Copy-Item -LiteralPath $script:JarOther -Destination (GameFile 'projectzomboid.jar') -Force
    $r = Invoke-Status $PkgV1
    Check ($r.Code -eq 0 -and $r.Out -match '遊戲已經更新') 'status 說明遊戲已更新、修補不會啟用'
    $PkgNew = New-TestPackage -Dir (Join-Path $TempRoot 'pkg-new-game') -PackageVersion 'v5.0' -JarSha (Get-Sha $script:JarOther) -AgentText 'agent-for-new-game' -ModuleSpecs $ModuleSpecs
    $r = Invoke-Install @('profiler') $PkgNew
    Check ($r.Code -eq 0 -and $r.Out -match '不同遊戲版本') '新版包照常安裝（提示舊安裝是別的遊戲版本）'
    Check ((Get-InstalledState).packageVersion -eq 'v5.0' -and (Test-GameFileIs $AGENT_REL 'agent-for-new-game') -and (Test-ArgModules 'core,profiler')) '換成新版 agent，啟動參數仍只有一條'

    Start-Case '67. 巢狀 class 的修補（LuaManager$Exposer）：資料夾裡的外層或同名內部類別都算蓋掉'
    Reset-GameDir
    Write-TextFile (GameFile 'zombie/Lua/LuaManager.class') 'foreign-outer'
    $r = Invoke-Install @('core') $PkgV1
    Check ($r.Code -eq 7 -and $r.Out -match '蓋掉本包要修補的 class') '外層 LuaManager.class：結束代碼 7'
    Check ((Test-JsonIsOriginal) -and -not (Test-Path -LiteralPath (GameFile '.mdc-patches'))) '什麼都沒寫'
    Reset-GameDir
    Write-TextFile (GameFile 'zombie/Lua/LuaManager$Exposer.class') 'foreign-inner'
    $r = Invoke-Install @('core') $PkgV1
    Check ($r.Code -eq 7) '同名內部類別 LuaManager$Exposer.class：結束代碼 7'
    Check (Test-GameFileIs 'zombie/Lua/LuaManager$Exposer.class' 'foreign-inner') '外來檔案原封不動'

    Start-Case '68. 安裝紀錄被刪掉但啟動設定還留著本包的參數：卸載拿掉它，不回「沒東西要卸載」'
    Reset-GameDir
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0 -and (Test-ArgModules 'core,profiler')) '前置安裝成功'
    Remove-Item -LiteralPath (GameFile '.mdc-patches') -Recurse -Force
    $r = Invoke-UninstallAll
    Check ($r.Code -eq 0 -and $r.Out -notmatch '沒有東西需要卸載') '結束代碼 0，有動作'
    Check (Test-JsonIsOriginal) '啟動設定逐位元組回到原樣（遊戲不會因為找不到 agent jar 起不來）'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches'))) '沒有留下狀態目錄'
    $r = Invoke-UninstallAll
    Check ($r.Code -eq 0 -and $r.Out -match '沒有東西需要卸載') '再跑一次：沒東西要卸載'
    Reset-GameDir
    $r = Invoke-Install @('profiler') $PkgV1
    Remove-Item -LiteralPath (GameFile '.mdc-patches') -Recurse -Force
    $brokenJson = (Get-GameText $LAUNCHER_REL) + '}{ not json'
    Write-TextFile (GameFile $LAUNCHER_REL) $brokenJson
    $r = Invoke-UninstallAll
    Check ($r.Code -eq 7 -and $r.Out -notmatch '沒有東西需要卸載') '啟動設定解析不了、裡面還有本包的參數：結束代碼 7，不回報沒東西要卸載'
    Check ((Get-GameText $LAUNCHER_REL) -ceq $brokenJson) '啟動設定沒被動'
    Write-TextFile (GameFile $LAUNCHER_REL) '{ not json at all'
    $r = Invoke-UninstallAll
    Check ($r.Code -eq 0 -and $r.Out -match '沒有東西需要卸載') '啟動設定解析不了但沒提到本包：照舊回報沒東西要卸載'
    Write-TextFile (GameFile $LAUNCHER_REL) '{ "vmArgs": ["-agentpath:.mdc-patches/mdc-agent-loader.dll=other.jar" not json'
    $r = Invoke-UninstallAll
    Check ($r.Code -eq 7 -and $r.Out -notmatch '沒有東西需要卸載') '啟動設定解析不了、只提到本包的載入器：結束代碼 7'
    [System.IO.File]::WriteAllBytes((GameFile $LAUNCHER_REL), [byte[]](0x7B, 0xFF, 0xFE, 0x7D))
    $badSha = Get-GameBytesSha $LAUNCHER_REL
    $r = Invoke-UninstallAll
    Check ($r.Code -eq 7 -and $r.Out -notmatch '沒有東西需要卸載' -and (Get-GameBytesSha $LAUNCHER_REL) -eq $badSha) '啟動設定不是合法 UTF-8（讀不出文字）：結束代碼 7，檔案沒被動'
    Reset-GameDir
    $r = Invoke-Install @('profiler') $PkgV1
    Remove-Item -LiteralPath (GameFile '.mdc-patches') -Recurse -Force
    $r = Invoke-Uninstall @('profiler')
    Check ($r.Code -eq 2 -and -not (Test-JsonIsOriginal)) '非互動又沒有 -All：只能整條拿掉，要求 -All（代碼 2），什麼都不動'
    Write-TextFile (GameFile $P_LEGACY_GUARD) $C_LEGACY_GUARD
    Write-TextFile (GameFile $P_FIX_TIDAM) $C_LEGACY_TIDAM_STD
    $r = Invoke-UninstallAll $PkgV1
    Check ($r.Code -eq 0 -and (Test-JsonIsOriginal)) '同時有整組吻合的舊版包：同一筆交易拿掉啟動參數'
    Check (-not (Test-GameFile $P_LEGACY_GUARD) -and -not (Test-GameFile $P_FIX_TIDAM) -and -not (Test-Path -LiteralPath (GameFile '.mdc-patches'))) '舊版包的檔案也移除，沒有留下狀態目錄'

    Start-Case '69. zombie/mdc 底下沒有紀錄的 class（舊版殘留）：拒裝，不讓它蓋掉 agent jar 的同名 class'
    Reset-GameDir
    Write-TextFile (GameFile 'zombie/mdc/MdcPatchRuntime.class') 'stale-helper'
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 7 -and $r.Out.Contains('zombie/mdc/MdcPatchRuntime.class')) '結束代碼 7，訊息指出那個檔'
    Check (Test-GameFileIs 'zombie/mdc/MdcPatchRuntime.class' 'stale-helper') '殘留檔原封不動'
    Check ((Test-JsonIsOriginal) -and -not (Test-Path -LiteralPath (GameFile '.mdc-patches'))) '什麼都沒寫'

    Start-Case '70. Windows 智慧型應用程式控制在強制模式：拒裝且不寫任何檔案；已裝的 status 提醒移除，卸載照常'
    Reset-GameDir
    $env:MDC_PATCHES_TEST_SAC = '1'
    try {
        $r = Invoke-Install @('profiler') $PkgV1
        Check ($r.Code -eq 11 -and $r.Out.Contains('mdc-agent-loader.dll') -and $r.Out.Contains('Uninstall-Patches.bat')) '結束代碼 11，訊息說明載入器會被擋、已裝的怎麼移除'
        Check ((Test-JsonIsOriginal) -and -not (Test-Path -LiteralPath (GameFile '.mdc-patches'))) '什麼都沒寫'
        $env:MDC_PATCHES_TEST_SAC = '0'
        $r = Invoke-Install @('profiler') $PkgV1
        Check ($r.Code -eq 0 -and (Test-ModuleSet @('core', 'profiler'))) '關閉時照常安裝'
        $env:MDC_PATCHES_TEST_SAC = '1'
        $r = Invoke-Status $PkgV1
        Check ($r.Code -eq 0 -and $r.Out.Contains('Uninstall-Patches.bat')) '裝好之後才變成強制模式：status 提醒全部移除'
        $r = Invoke-UninstallAll $PkgV1
        Check ($r.Code -eq 0 -and (Test-JsonIsOriginal) -and -not (Test-Path -LiteralPath (GameFile '.mdc-patches'))) '強制模式下照樣卸載得掉'
        $env:MDC_PATCHES_TEST_SAC = '2'
        $r = Invoke-Install @('profiler') $PkgV1
        Check ($r.Code -eq 0 -and (Test-ModuleSet @('core', 'profiler')) -and $r.Out.Contains('Uninstall-Patches.bat')) '評估模式：照常安裝並警告之後可能被擋'
    } finally { Remove-Item Env:MDC_PATCHES_TEST_SAC -ErrorAction SilentlyContinue }
}
finally {
    Write-Host ''
    if ($KeepTemp) {
        Write-Host "保留測試沙箱：$TempRoot" -ForegroundColor Yellow
    } else {
        Remove-Item -LiteralPath $TempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Write-Host ''
Write-Host "通過 $script:Passed，失敗 $script:Failed" -ForegroundColor $(if ($script:Failed -eq 0) { 'Green' } else { 'Red' })
foreach ($f in $script:FailedNames) { Write-Host "  失敗：$f" -ForegroundColor Red }
if ($script:Failed -ne 0) { exit 1 }
exit 0
