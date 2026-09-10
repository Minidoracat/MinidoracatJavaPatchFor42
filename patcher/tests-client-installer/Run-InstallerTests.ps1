#Requires -Version 5.1
<#
    Run-InstallerTests.ps1 — deploy-client\Manage-Patches.ps1 的真實 CLI 往返測試

    完全在 %TEMP% 底下的合成遊戲目錄操作：假的 ProjectZomboid64.exe／projectzomboid.jar
    ＋合成 payload（純文字檔，不是真的 class）。每個案例都真的用
    `powershell.exe -File Manage-Patches.ps1 ...` 跑一次，檢查結束代碼與磁碟結果。

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

$JAR_GOOD = 'FAKE-PROJECTZOMBOID-JAR-42.20.4'
$JAR_OTHER = 'FAKE-PROJECTZOMBOID-JAR-42.21.0'

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
function Test-GameFileIs([string]$rel, [string]$text) {
    if (-not (Test-GameFile $rel)) { return $false }
    return ((Get-GameText $rel) -eq $text)
}

function Reset-GameDir([string]$jarContent = $JAR_GOOD) {
    if (Test-Path -LiteralPath $GameDir) { Remove-Item -LiteralPath $GameDir -Recurse -Force }
    New-Item -ItemType Directory -Path $GameDir -Force | Out-Null
    Write-TextFile (Join-Path $GameDir 'ProjectZomboid64.exe') 'stub-exe'
    Write-TextFile (Join-Path $GameDir 'projectzomboid.jar') $jarContent
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

# ---------------------------------------------------------------- 執行安裝器
function Invoke-Installer([string[]]$Arguments) {
    $full = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $Installer) + $Arguments
    $out = (& powershell.exe @full 2>&1 | Out-String)
    $code = $LASTEXITCODE
    $script:LastOut = $out
    Write-Host "  > Manage-Patches.ps1 $($Arguments -join ' ')  => exit $code" -ForegroundColor DarkGray
    return @{ Code = $code; Out = $out }
}
function Invoke-Install([string[]]$mods, [string]$pkgDir) {
    return Invoke-Installer @('-NonInteractive', '-Action', 'install', '-GameDir', $GameDir, '-PackageDir', $pkgDir, '-Modules', ($mods -join ','))
}
function Invoke-Uninstall([string[]]$mods) {
    return Invoke-Installer @('-NonInteractive', '-Action', 'uninstall', '-GameDir', $GameDir, '-Modules', ($mods -join ','))
}

# ---------------------------------------------------------------- 合成安裝包
# files 用陣列保序：交易的寫入順序＝manifest 的模組／檔案順序，rollback 案例靠它決定
# 哪個檔案先被寫進去。
function New-TestPackage {
    param([string]$Dir, [string]$PackageVersion, [string]$JarSha, [array]$ModuleSpecs, [array]$LegacySpecs)

    if (Test-Path -LiteralPath $Dir) { Remove-Item -LiteralPath $Dir -Recurse -Force }
    New-Item -ItemType Directory -Path $Dir -Force | Out-Null

    $mods = @()
    foreach ($m in $ModuleSpecs) {
        $files = @()
        foreach ($f in $m.files) {
            # source 用 payload/<moduleId>/<path>：standard 與 lowmem 共用同一個遊戲內路徑，
            # 但 payload 內容不同，來源必須分開放
            $srcRel = "payload/$($m.id)/$($f.p)"
            $srcFull = Join-Path $Dir ($srcRel -replace '/', '\')
            Write-TextFile $srcFull $f.c
            $files += [ordered]@{ path = $f.p; source = $srcRel; sha256 = (Get-Sha $srcFull) }
        }
        $entry = [ordered]@{
            id       = $m.id
            name     = $m.name
            version  = $m.version
            requires = @($m.requires)
        }
        if ($m.group) { $entry['exclusiveGroup'] = $m.group }
        $entry['files'] = @($files)
        $mods += $entry
    }

    $manifest = [ordered]@{
        schemaVersion  = 1
        gameVersion    = '42.20.4-test'
        packageVersion = $PackageVersion
        jarSha256      = $JarSha
        modules        = @($mods)
    }
    if ($LegacySpecs) {
        $legacy = @()
        foreach ($lp in $LegacySpecs) {
            $lf = @()
            foreach ($f in $lp.files) { $lf += [ordered]@{ path = $f.p; sha256 = (Get-TextSha $f.c) } }
            $legacy += [ordered]@{
                id        = $lp.id
                modules   = @($lp.modules)
                jarSha256 = $JarSha
                files     = @($lf)
            }
        }
        $manifest['legacyPackages'] = @($legacy)
    }

    $json = ConvertTo-Json -InputObject $manifest -Depth 12
    Write-TextFile (Join-Path $Dir 'manifest.json') $json
    return $Dir
}

# ---------------------------------------------------------------- 內容常數
$C_CORE_RT_V1 = 'core-runtime-v1'
$C_CORE_PI_V1 = 'core-patchinfo-v1'
$C_CORE_RT_V2 = 'core-runtime-v2-CHANGED'
$C_CORE_PI_V2 = 'core-patchinfo-v2-CHANGED'
$C_PROF_MAIN = 'profiler-main-v1'
$C_PROF_CALLER = 'luacaller-with-profiling-v1'
$C_FIX_STD_TIDAM = 'fixes-standard-textureidassetmanager-v1'
$C_FIX_LOW_TIDAM = 'fixes-lowmem-textureidassetmanager-v1'
$C_FIX_SHARED_WS = 'fixes-shared-worldstreamer-v1'
$C_LEGACY_GUARD = 'legacy-v3.0-oldguard'
$C_LEGACY_TIDAM_STD = 'legacy-v3.0-standard-tidam'
$C_LEGACY_TIDAM_LOW = 'legacy-v3.0-lowmem-tidam'

$P_CORE_RT = 'zombie/mdc/MdcPatchRuntime.class'
$P_CORE_PI = 'zombie/mdc/PatchInfo.class'
$P_PROF_MAIN = 'zombie/mdc/MdcProfiler.class'
$P_PROF_CALLER = 'se/krka/kahlua/integration/LuaCaller.class'
$P_FIX_TIDAM = 'zombie/core/textures/TextureIDAssetManager.class'
$P_FIX_WS = 'zombie/iso/WorldStreamer.class'
$P_LEGACY_GUARD = 'zombie/mdc/TexturePipelineGuard.class'
# 單檔／單模組安裝包：PowerShell 管線可能展開單元素集合；
# runtime（Java org.json）要求 modules／files 維持 JSONArray，這裡驗證序列化契約。
$C_SOLO = 'single-file-module-v1'
$P_SOLO = 'zombie/ui/MdcSoloFixture.class'

function Get-ModuleSpecs([string]$coreRt, [string]$corePi) {
    return @(
        @{ id = 'core'; name = '共用核心'; version = '0.1.0'; requires = @(); files = @(
                @{ p = $P_CORE_RT; c = $coreRt }
                @{ p = $P_CORE_PI; c = $corePi }
            )
        }
        @{ id = 'profiler'; name = '開發效能分析器'; version = '0.1.0'; requires = @('core'); files = @(
                @{ p = $P_PROF_MAIN; c = $C_PROF_MAIN }
                @{ p = $P_PROF_CALLER; c = $C_PROF_CALLER }
            )
        }
        @{ id = 'client-fixes-standard'; name = '客戶端修復（標準）'; version = '3.0'; requires = @('core'); group = 'client-fixes'; files = @(
                @{ p = $P_FIX_TIDAM; c = $C_FIX_STD_TIDAM }
                @{ p = $P_FIX_WS; c = $C_FIX_SHARED_WS }
            )
        }
        @{ id = 'client-fixes-lowmem'; name = '客戶端修復（低記憶體）'; version = '3.0'; requires = @('core'); group = 'client-fixes'; files = @(
                @{ p = $P_FIX_TIDAM; c = $C_FIX_LOW_TIDAM }
                @{ p = $P_FIX_WS; c = $C_FIX_SHARED_WS }
            )
        }
    )
}

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

# ---------------------------------------------------------------- 中斷交易佈景
# 模擬「安裝到一半被砍掉」：完整發布的新 class ＋ 原檔備份 ＋ 未提交的 journal。
$TX_ID = 'deadbeefcafe'

function Set-PendingTransaction {
    param([switch]$SkipBackupFile, [string]$TxId = $TX_ID, $Entries = $null)
    if ($SkipBackupFile) {
        New-Item -ItemType Directory -Path (GameFile ".mdc-patches/backup/$TxId") -Force | Out-Null
    } else {
        Write-TextFile (GameFile ".mdc-patches/backup/$TxId/$P_CORE_RT") $C_CORE_RT_V1
    }
    Write-TextFile (GameFile $P_CORE_RT) $C_CORE_RT_V2
    if ($null -eq $Entries) {
        $Entries = @(
            [ordered]@{ path = $P_CORE_RT; backup = $P_CORE_RT
                backupSha256 = (Get-TextSha $C_CORE_RT_V1); sha256 = (Get-TextSha $C_CORE_RT_V2) }
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
Reset-GameDir
$JarSha = Get-Sha (Join-Path $GameDir 'projectzomboid.jar')

$PkgV1 = New-TestPackage -Dir (Join-Path $TempRoot 'pkg-v1') -PackageVersion 'v4.0' -JarSha $JarSha `
    -ModuleSpecs (Get-ModuleSpecs $C_CORE_RT_V1 $C_CORE_PI_V1) -LegacySpecs $LegacySpecs
$PkgV2 = New-TestPackage -Dir (Join-Path $TempRoot 'pkg-v2') -PackageVersion 'v4.1' -JarSha $JarSha `
    -ModuleSpecs (Get-ModuleSpecs $C_CORE_RT_V2 $C_CORE_PI_V2) -LegacySpecs $LegacySpecs
$SoloSpecs = @(
    @{ id = 'solo'; name = '單檔模組'; version = '0.1.0'; requires = @(); files = @(
            @{ p = $P_SOLO; c = $C_SOLO }
        )
    }
)
$PkgSolo = New-TestPackage -Dir (Join-Path $TempRoot 'pkg-solo') -PackageVersion 'v4.0-solo' -JarSha $JarSha `
    -ModuleSpecs $SoloSpecs

# 舊版包的檔案內容剛好與新版一模一樣（模組的 class 這一版沒改動時就是這樣）：
# 檔案層面沒事可做，但安裝紀錄還是得寫下來，否則之後沒人認得這些檔案。
$SameSpecs = @(
    @{ id = 'core'; name = '共用核心'; version = '0.1.0'; requires = @(); files = @(
            @{ p = $P_CORE_RT; c = $C_CORE_RT_V1 }
            @{ p = $P_CORE_PI; c = $C_CORE_PI_V1 }
        )
    }
)
$SameLegacy = @(
    @{ id = 'legacy-identical-v3.0'; modules = @('core'); files = @(
            @{ p = $P_CORE_RT; c = $C_CORE_RT_V1 }
            @{ p = $P_CORE_PI; c = $C_CORE_PI_V1 }
        )
    }
)
$PkgSame = New-TestPackage -Dir (Join-Path $TempRoot 'pkg-same') -PackageVersion 'v4.0-same' -JarSha $JarSha `
    -ModuleSpecs $SameSpecs -LegacySpecs $SameLegacy

try {
    # ------------------------------------------------------------ 1
    Start-Case '1. 乾淨目錄查狀態'
    $r = Invoke-Installer @('-NonInteractive', '-Action', 'status', '-GameDir', $GameDir, '-PackageDir', $PkgV1)
    Check ($r.Code -eq 0) '結束代碼 0'
    Check ($r.Out -match '未安裝任何模組') '報告未安裝'
    Check ($r.Out -match '可安裝') '列出可安裝模組'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches'))) 'status 不會建立任何狀態檔'

    # ------------------------------------------------------------ 2
    Start-Case '2. 選模組安裝（profiler 自動帶入 core）'
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '結束代碼 0'
    Check (Test-GameFileIs $P_CORE_RT $C_CORE_RT_V1) 'core runtime 已寫入'
    Check (Test-GameFileIs $P_CORE_PI $C_CORE_PI_V1) 'core patchinfo 已寫入'
    Check (Test-GameFileIs $P_PROF_MAIN $C_PROF_MAIN) 'profiler 主檔已寫入'
    Check (Test-GameFileIs $P_PROF_CALLER $C_PROF_CALLER) 'LuaCaller 已寫入'
    Check (-not (Test-GameFile $P_FIX_TIDAM)) '沒有安裝未選的 client-fixes'
    Check (Test-ModuleSet @('core', 'profiler')) 'state.json 記錄 core + profiler'
    Check ((Get-InstalledState).packageVersion -eq 'v4.0') 'state.json 記錄 packageVersion'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches/journal.json'))) '沒有殘留 journal'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches/backup'))) '沒有殘留備份'

    # ------------------------------------------------------------ 3
    Start-Case '3. 加裝 client-fixes-standard（保留既有模組）'
    $r = Invoke-Install @('client-fixes-standard') $PkgV1
    Check ($r.Code -eq 0) '結束代碼 0'
    Check (Test-GameFileIs $P_FIX_TIDAM $C_FIX_STD_TIDAM) 'standard 變體檔案已寫入'
    Check (Test-GameFileIs $P_FIX_WS $C_FIX_SHARED_WS) 'WorldStreamer 已寫入'
    Check (Test-GameFileIs $P_PROF_MAIN $C_PROF_MAIN) 'profiler 未被動到'
    Check (Test-ModuleSet @('core', 'profiler', 'client-fixes-standard')) 'state.json 三個模組'

    # ------------------------------------------------------------ 4
    Start-Case '4. 切換互斥變體 standard -> lowmem'
    $r = Invoke-Install @('client-fixes-lowmem') $PkgV1
    Check ($r.Code -eq 0) '結束代碼 0'
    Check (Test-GameFileIs $P_FIX_TIDAM $C_FIX_LOW_TIDAM) 'TextureIDAssetManager 換成 lowmem 內容'
    Check (Test-GameFileIs $P_FIX_WS $C_FIX_SHARED_WS) '兩變體共用且內容相同的檔案保留'
    Check (Test-ModuleSet @('core', 'profiler', 'client-fixes-lowmem')) 'state.json 只留 lowmem 變體'
    Check (Test-GameFileIs $P_PROF_MAIN $C_PROF_MAIN) 'profiler 未受影響'

    # ------------------------------------------------------------ 5
    Start-Case '5. 卸載單一模組（profiler）不影響其它模組'
    $r = Invoke-Uninstall @('profiler')
    Check ($r.Code -eq 0) '結束代碼 0'
    Check (-not (Test-GameFile $P_PROF_MAIN)) 'profiler 主檔已移除'
    Check (-not (Test-GameFile $P_PROF_CALLER)) 'LuaCaller 已移除'
    Check (-not (Test-Path -LiteralPath (GameFile 'se'))) '空的 se\ 目錄已清掉'
    Check (Test-GameFileIs $P_CORE_RT $C_CORE_RT_V1) 'core 保留'
    Check (Test-GameFileIs $P_FIX_TIDAM $C_FIX_LOW_TIDAM) 'client-fixes 保留'
    Check (Test-ModuleSet @('core', 'client-fixes-lowmem')) 'state.json 只剩 core + lowmem'

    # ------------------------------------------------------------ 6
    Start-Case '6. 只卸 core：仍被依賴時自動保留'
    $r = Invoke-Uninstall @('core')
    Check ($r.Code -eq 0) '結束代碼 0'
    Check ($r.Out -match '仍被其它保留的模組需要') '有說明 core 被保留'
    Check (Test-GameFileIs $P_CORE_RT $C_CORE_RT_V1) 'core 檔案沒有被刪'
    Check (Test-ModuleSet @('core', 'client-fixes-lowmem')) 'state.json 不變'

    # ------------------------------------------------------------ 7
    Start-Case '7. 全部卸載（-All）不碰別人的檔案'
    Write-TextFile (GameFile 'zombie/other/SomeoneElseMod.class') 'third-party-file'
    $r = Invoke-Installer @('-NonInteractive', '-Action', 'uninstall', '-GameDir', $GameDir, '-All')
    Check ($r.Code -eq 0) '結束代碼 0'
    Check (-not (Test-GameFile $P_CORE_RT)) 'core 已移除'
    Check (-not (Test-GameFile $P_FIX_TIDAM)) 'client-fixes 已移除'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches'))) '.mdc-patches 已清掉'
    Check (Test-GameFileIs 'zombie/other/SomeoneElseMod.class' 'third-party-file') '第三方檔案原封不動'
    Check (Test-Path -LiteralPath (GameFile 'zombie')) '仍有別人檔案的 zombie\ 目錄不會被刪'

    # ------------------------------------------------------------ 8
    Start-Case '8. 遊戲版本不符：拒裝且不寫任何檔案'
    Reset-GameDir $JAR_OTHER
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 5) '結束代碼 5（版本不符）'
    Check (-not (Test-Path -LiteralPath (GameFile 'zombie'))) '沒有建立任何 loose class'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches'))) '沒有建立狀態檔'

    # ------------------------------------------------------------ 9
    Start-Case '9. 外來 loose 檔衝突：拒裝'
    Reset-GameDir
    Write-TextFile (GameFile $P_FIX_WS) 'another-patch-owns-this'
    $r = Invoke-Install @('client-fixes-standard') $PkgV1
    Check ($r.Code -eq 7) '結束代碼 7（衝突）'
    Check (Test-GameFileIs $P_FIX_WS 'another-patch-owns-this') '外來檔案沒有被覆蓋'
    Check (-not (Test-GameFile $P_CORE_RT)) '衝突時不會先寫入其它檔案'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches'))) '沒有建立狀態檔'

    # ------------------------------------------------------------ 10
    Start-Case '10. 舊版包整組吻合：接管並清掉殘檔'
    Reset-GameDir
    Write-TextFile (GameFile $P_LEGACY_GUARD) $C_LEGACY_GUARD
    Write-TextFile (GameFile $P_FIX_TIDAM) $C_LEGACY_TIDAM_STD
    $r = Invoke-Install @('client-fixes-standard') $PkgV1
    Check ($r.Code -eq 0) '結束代碼 0'
    Check ($r.Out -match 'legacy-client-v3\.0-standard') '有辨識出舊版包'
    Check (Test-GameFileIs $P_FIX_TIDAM $C_FIX_STD_TIDAM) '舊檔已更新為新版內容'
    Check (-not (Test-GameFile $P_LEGACY_GUARD)) '舊版獨有的殘檔已移除'
    Check (Test-ModuleSet @('core', 'client-fixes-standard')) 'state.json 正確'

    # ------------------------------------------------------------ 11
    Start-Case '11. 舊版包只吻合一部分：拒裝（不猜版本）'
    Reset-GameDir
    Write-TextFile (GameFile $P_LEGACY_GUARD) $C_LEGACY_GUARD
    $r = Invoke-Install @('client-fixes-standard') $PkgV1
    Check ($r.Code -eq 7) '結束代碼 7（無法辨識的舊版 patch）'
    Check (Test-GameFileIs $P_LEGACY_GUARD $C_LEGACY_GUARD) '舊檔沒有被刪'
    Check (-not (Test-GameFile $P_CORE_RT)) '沒有安裝任何東西'
    Check ($r.Out -match 'uninstall') '有指引使用者用舊版 uninstaller'

    # ------------------------------------------------------------ 12
    Start-Case '12. 已安裝檔案被變造：卸載拒絕刪除'
    Reset-GameDir
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '前置安裝成功'
    Write-TextFile (GameFile $P_PROF_MAIN) 'user-hand-edited-this'
    $r = Invoke-Uninstall @('profiler')
    Check ($r.Code -eq 8) '結束代碼 8（檔案被變造）'
    Check (Test-GameFileIs $P_PROF_MAIN 'user-hand-edited-this') '變造的檔案沒有被刪'
    Check (Test-GameFile $P_PROF_CALLER) '同模組其它檔案也沒被刪（整筆中止）'
    Check (Test-ModuleSet @('core', 'profiler')) 'state.json 沒有被更動'

    # ------------------------------------------------------------ 13
    Start-Case '13. 寫入中途失敗：完整回復，既有安裝不毀'
    Reset-GameDir
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '前置安裝 v1 成功'
    # 只拿 Read 存取＋FileShare.Read 開著第二個 core 檔：其它人讀得到（前置檢查與備份會過），
    # 但原子替換會被共用模式擋下，交易正好在發布到一半時失敗。
    $locked = [System.IO.File]::Open((GameFile $P_CORE_PI), [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read)
    try {
        $r = Invoke-Install @('core') $PkgV2
    } finally {
        $locked.Close(); $locked.Dispose()
    }
    Check ($r.Code -eq 9) '結束代碼 9（已回復）'
    Check (Test-GameFileIs $P_CORE_RT $C_CORE_RT_V1) '先寫進去的檔案已回復成安裝前內容'
    Check (Test-GameFileIs $P_CORE_PI $C_CORE_PI_V1) '被鎖住的檔案維持原內容'
    Check (Test-GameFileIs $P_PROF_MAIN $C_PROF_MAIN) '既有 profiler 安裝沒有被破壞'
    Check ((Get-InstalledState).packageVersion -eq 'v4.0') 'state.json 仍是安裝前的版本'
    Check (Test-ModuleSet @('core', 'profiler')) 'state.json 模組清單不變'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches/journal.json'))) 'journal 已清除'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches/backup'))) '備份已清除'

    # ------------------------------------------------------------ 14
    Start-Case '14. 中斷的交易：status 唯讀不復原，install 才復原'
    Reset-GameDir
    Set-PendingTransaction
    $r = Invoke-Installer @('-NonInteractive', '-Action', 'status', '-GameDir', $GameDir)
    Check ($r.Code -eq 0) 'status 結束代碼 0'
    Check ($r.Out -match '未完成') 'status 有提醒有未完成的交易'
    Check (Test-GameFileIs $P_CORE_RT $C_CORE_RT_V2) 'status 沒有動尚未提交的檔案'
    Check (Test-Path -LiteralPath (GameFile '.mdc-patches/journal.json')) 'status 沒有清掉 journal'
    Check (Test-Path -LiteralPath (GameFile ".mdc-patches/backup/$TX_ID")) 'status 沒有清掉備份'
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) 'install 結束代碼 0'
    Check ($r.Out -match '復原') 'install 動手前先復原了上一次的中斷交易'
    Check (Test-GameFileIs $P_CORE_RT $C_CORE_RT_V1) '半套寫入的檔案已還原'
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
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches'))) '所有參數錯誤都沒有寫入任何東西'

    # ------------------------------------------------------------ 16
    Start-Case '16. -All 安裝：互斥組取 manifest 預設變體'
    Reset-GameDir
    $r = Invoke-Installer @('-NonInteractive', '-Action', 'install', '-GameDir', $GameDir, '-PackageDir', $PkgV1, '-All')
    Check ($r.Code -eq 0) '結束代碼 0'
    Check (Test-ModuleSet @('core', 'profiler', 'client-fixes-standard')) '裝到 core + profiler + 預設變體'
    Check (Test-GameFileIs $P_FIX_TIDAM $C_FIX_STD_TIDAM) '預設變體內容正確'
    $r = Invoke-Installer @('-NonInteractive', '-Action', 'install', '-GameDir', $GameDir, '-PackageDir', $PkgV1, '-All')
    Check ($r.Code -eq 0) '重複執行不報錯'
    Check ($r.Out -match '不需要任何變更') '重複執行判定為無變更'

    # ------------------------------------------------------------ 17
    Start-Case '17. 卸載後可以重裝（往返一輪）'
    $r = Invoke-Installer @('-NonInteractive', '-Action', 'uninstall', '-GameDir', $GameDir, '-All')
    Check ($r.Code -eq 0) '全卸成功'
    $r = Invoke-Install @('profiler', 'client-fixes-lowmem') $PkgV1
    Check ($r.Code -eq 0) '重裝成功'
    Check (Test-ModuleSet @('core', 'profiler', 'client-fixes-lowmem')) '重裝後模組正確'
    Check (Test-GameFileIs $P_FIX_TIDAM $C_FIX_LOW_TIDAM) '重裝後檔案內容正確'
    $r = Invoke-Installer @('-NonInteractive', '-Action', 'status', '-GameDir', $GameDir, '-PackageDir', $PkgV1)
    Check ($r.Code -eq 0) 'status 成功'
    Check ($r.Out -match '正常') 'status 回報安裝完整'

    # ------------------------------------------------------------ 18
    Start-Case '18. 復原失敗：journal 與備份都保留，修好後可重跑'
    Reset-GameDir
    Set-PendingTransaction -SkipBackupFile
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 10) '結束代碼 10（回復失敗）'
    Check ($r.Out -match '備份檔遺失') '有指出備份檔遺失'
    Check (Test-Path -LiteralPath (GameFile '.mdc-patches/journal.json')) 'journal 保留著（還能重跑復原）'
    Check (Test-Path -LiteralPath (GameFile ".mdc-patches/backup/$TX_ID")) '備份目錄保留著'
    Check (Test-GameFileIs $P_CORE_RT $C_CORE_RT_V2) '沒有半套復原'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches/state.json'))) '沒有寫入任何安裝狀態'
    # 把缺的備份補回去，同一份 journal 應該要能復原成功並繼續安裝
    Write-TextFile (GameFile ".mdc-patches/backup/$TX_ID/$P_CORE_RT") $C_CORE_RT_V1
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '補回備份後重跑成功'
    Check (Test-GameFileIs $P_CORE_RT $C_CORE_RT_V1) '這次有復原成功'
    Check (Test-ModuleSet @('core', 'profiler')) '復原後完成安裝'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches/backup'))) '成功之後才清掉備份'

    # ------------------------------------------------------------ 19
    Start-Case '19. 被竄改的 journal（路徑跳出遊戲目錄）：拒絕依它動檔案'
    Reset-GameDir
    Set-PendingTransaction -Entries @(
        [ordered]@{ path = '../evil-outside.class'; backup = '../evil-outside.class' }
        [ordered]@{ path = $P_CORE_RT; backup = $P_CORE_RT }
    )
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 7) '結束代碼 7（路徑不安全）'
    Check (-not (Test-Path -LiteralPath (Join-Path $TempRoot 'evil-outside.class'))) '沒有寫到遊戲目錄外'
    Check (Test-Path -LiteralPath (GameFile '.mdc-patches/journal.json')) 'journal 留著給人工檢查'
    Check (Test-GameFileIs $P_CORE_RT $C_CORE_RT_V2) '一個檔案都沒動'
    Check (-not (Test-GameFile $P_PROF_MAIN)) '沒有進行安裝'

    # ------------------------------------------------------------ 20
    Start-Case '20. journal 的 txId 格式不合法：拒絕'
    Reset-GameDir
    Set-PendingTransaction -TxId 'ZZZZZZZZZZZZ'
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 7) '結束代碼 7'
    Check ($r.Out -match 'txId') '有指出 txId 不合法'
    Check (Test-Path -LiteralPath (GameFile '.mdc-patches/journal.json')) 'journal 留著'
    Check (Test-GameFileIs $P_CORE_RT $C_CORE_RT_V2) '沒有動任何檔案'

    # ------------------------------------------------------------ 21
    Start-Case '21. journal 的 backup 指向別的路徑：拒絕'
    Reset-GameDir
    Set-PendingTransaction -Entries @(
        [ordered]@{ path = $P_CORE_RT; backup = $P_PROF_MAIN
            backupSha256 = (Get-TextSha $C_CORE_RT_V1); sha256 = (Get-TextSha $C_CORE_RT_V2) }
        [ordered]@{ path = '.mdc-patches/state.json'; backup = $null; backupSha256 = $null; sha256 = $null }
    )
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 7) '結束代碼 7'
    Check (Test-GameFileIs $P_CORE_RT $C_CORE_RT_V2) '沒有被亂還原成別的檔案內容'

    # ------------------------------------------------------------ 22
    Start-Case '22. -All 不會把已安裝的 lowmem 換成預設 standard'
    Reset-GameDir
    $r = Invoke-Install @('client-fixes-lowmem') $PkgV1
    Check ($r.Code -eq 0) '前置：先裝 lowmem'
    $r = Invoke-Installer @('-NonInteractive', '-Action', 'install', '-GameDir', $GameDir, '-PackageDir', $PkgV1, '-All')
    Check ($r.Code -eq 0) '-All 結束代碼 0'
    Check (Test-ModuleSet @('core', 'profiler', 'client-fixes-lowmem')) '-All 沿用已安裝的變體並補上其它模組'
    Check (Test-GameFileIs $P_FIX_TIDAM $C_FIX_LOW_TIDAM) 'lowmem 內容沒有被換成 standard'

    # ------------------------------------------------------------ 23
    Start-Case '23. 只加裝 profiler 不會刪掉辨識出的舊版客戶端修復'
    Reset-GameDir
    Write-TextFile (GameFile $P_LEGACY_GUARD) $C_LEGACY_GUARD
    Write-TextFile (GameFile $P_FIX_TIDAM) $C_LEGACY_TIDAM_LOW
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '結束代碼 0'
    Check (Test-ModuleSet @('core', 'profiler', 'client-fixes-lowmem')) '舊版修復被接管成對應模組，而不是被刪掉'
    Check (Test-GameFileIs $P_FIX_TIDAM $C_FIX_LOW_TIDAM) '修復檔升級成新版 lowmem 內容'
    Check (Test-GameFileIs $P_FIX_WS $C_FIX_SHARED_WS) '同模組缺的檔案補齊'
    Check (-not (Test-GameFile $P_LEGACY_GUARD)) '舊版獨有的殘檔清掉'
    Check (Test-GameFileIs $P_PROF_MAIN $C_PROF_MAIN) 'profiler 有裝上'

    # ------------------------------------------------------------ 24
    Start-Case '24. 沒有 state.json 但有舊版檔：卸載明確拒絕，不回報假成功'
    Reset-GameDir
    Write-TextFile (GameFile $P_LEGACY_GUARD) $C_LEGACY_GUARD
    Write-TextFile (GameFile $P_FIX_TIDAM) $C_LEGACY_TIDAM_STD
    $r = Invoke-Installer @('-NonInteractive', '-Action', 'uninstall', '-GameDir', $GameDir, '-PackageDir', $PkgV1, '-All')
    Check ($r.Code -eq 7) '結束代碼 7（拒絕）'
    Check ($r.Out -match 'uninstall') '有指引使用者用舊版 uninstaller'
    Check ($r.Out -notmatch '沒有東西需要卸載') '沒有回報「沒東西要卸載」'
    Check (Test-GameFileIs $P_LEGACY_GUARD $C_LEGACY_GUARD) '舊版檔沒有被刪'
    Check (Test-GameFileIs $P_FIX_TIDAM $C_LEGACY_TIDAM_STD) '舊版修復檔沒有被刪'
    $r = Invoke-Installer @('-NonInteractive', '-Action', 'uninstall', '-GameDir', $GameDir, '-All')
    Check ($r.Code -eq 7) '沒有 manifest 也一樣拒絕（自家 namespace 還有 class）'
    $r = Invoke-Installer @('-Action', 'uninstall', '-GameDir', $GameDir, '-PackageDir', $PkgV1)
    Check ($r.Code -eq 7) '雙擊卸載入口同樣拒絕，不能由空 state 分支回報成功'
    Check (Test-GameFileIs $P_LEGACY_GUARD $C_LEGACY_GUARD) '互動卸載不刪無主舊版檔案'

    # ------------------------------------------------------------ 25
    Start-Case '25. 單模組單檔案的 state.json 仍是 JSON array'
    Reset-GameDir
    $r = Invoke-Install @('solo') $PkgSolo
    Check ($r.Code -eq 0) '結束代碼 0'
    Check (Test-ModuleSet @('solo')) '只有一個模組'
    Check (Test-GameFileIs $P_SOLO $C_SOLO) '唯一的檔案有寫入'
    $rawState = [System.IO.File]::ReadAllText((GameFile '.mdc-patches/state.json'))
    Check ($rawState -match '"modules"\s*:\s*\[') 'modules 是 JSON array（不是單一物件）'
    Check ($rawState -match '"files"\s*:\s*\[') 'files 是 JSON array（不是單一物件）'

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
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches'))) '被擋下時沒有寫入任何狀態'
    Check (-not (Test-GameFile $P_CORE_RT)) '被擋下時沒有動遊戲檔案'
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
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches'))) '沒有寫入任何狀態'
    Check (-not (Test-GameFile $P_CORE_RT)) '沒有動遊戲檔案'

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
    Set-PendingTransaction -Entries @([ordered]@{
        path = '.mdc-patches/state.json'; backup = '.mdc-patches/state.json'
        backupSha256 = (Get-TextSha $beforeState); sha256 = (Get-TextSha $afterState)
    })
    Write-TextFile (GameFile $P_CORE_RT) $C_CORE_RT_V1
    Write-TextFile (GameFile ".mdc-patches/backup/$TX_ID/.mdc-patches/state.json") $beforeState
    Write-TextFile (GameFile '.mdc-patches/state.json') $afterState
    $r = Invoke-Uninstall @('profiler')
    Check ($r.Code -eq 0) '無 manifest 也能以備份 state 復原再卸載'
    Check (Test-ModuleSet @('core')) '單筆交易還原後正確保留 core'
    Check (Test-GameFileIs $P_CORE_RT $C_CORE_RT_V1) 'core 內容沒有改動'
    Check (-not (Test-GameFile $P_PROF_MAIN)) 'profiler 正常卸載'

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
    Check (Test-GameFileIs $P_CORE_RT $C_CORE_RT_V2) '拒絕發生在任何檔案復原之前'

    Start-Case '31. 舊版包內容與新版相同：不能只因為檔案不用動就跳過安裝紀錄'
    Reset-GameDir
    Write-TextFile (GameFile $P_CORE_RT) $C_CORE_RT_V1
    Write-TextFile (GameFile $P_CORE_PI) $C_CORE_PI_V1
    $r = Invoke-Install @('core') $PkgSame
    Check ($r.Code -eq 0) '結束代碼 0'
    Check (Test-ModuleSet @('core')) '接管後有寫下 state.json'
    Check ((Get-InstalledState).packageVersion -eq 'v4.0-same') 'state.json 記錄的是這次的安裝包版本'
    Check (Test-GameFileIs $P_CORE_RT $C_CORE_RT_V1) '既有檔案內容不變'
    # 有紀錄才卸載得掉：沒紀錄的話這些檔案會被當成無主舊版 patch 而拒絕處理
    $r = Invoke-Installer @('-NonInteractive', '-Action', 'uninstall', '-GameDir', $GameDir, '-All')
    Check ($r.Code -eq 0) '接管後可以正常卸載'
    Check (-not (Test-GameFile $P_CORE_RT)) '卸載後檔案已移除'
    Check (-not (Test-Path -LiteralPath (GameFile '.mdc-patches'))) '卸載後狀態目錄已清掉'

    Start-Case '32. 狀態目錄被換成目錄接點：拒絕，不寫到遊戲目錄外'
    Reset-GameDir
    $outsideState = Join-Path $TempRoot 'outside-state'
    if (Test-Path -LiteralPath $outsideState) { Remove-Item -LiteralPath $outsideState -Recurse -Force }
    New-Item -ItemType Directory -Path $outsideState -Force | Out-Null
    New-Item -ItemType Junction -Path (GameFile '.mdc-patches') -Target $outsideState | Out-Null
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 7) '結束代碼 7（路徑上有接點）'
    Check (@(Get-ChildItem -LiteralPath $outsideState -Force).Count -eq 0) '沒有把 state／journal／備份寫到遊戲目錄外'
    Check (-not (Test-GameFile $P_CORE_RT)) '沒有安裝任何檔案'
    # 只拆接點本身，不能讓後續的 Remove-Item -Recurse 穿過去砍掉目標目錄
    [System.IO.Directory]::Delete((GameFile '.mdc-patches'))

    Start-Case '33. manifest 的 source 指向安裝包外：拒絕'
    Reset-GameDir
    $PkgEvilSource = Join-Path $TempRoot 'pkg-evil-source'
    if (Test-Path -LiteralPath $PkgEvilSource) { Remove-Item -LiteralPath $PkgEvilSource -Recurse -Force }
    Copy-Item -LiteralPath $PkgSolo -Destination $PkgEvilSource -Recurse -Force
    Write-TextFile (Join-Path $TempRoot 'outside-payload.class') $C_SOLO
    $evilManifestPath = Join-Path $PkgEvilSource 'manifest.json'
    $evilManifest = [System.IO.File]::ReadAllText($evilManifestPath) | ConvertFrom-Json
    $evilManifest.modules[0].files[0].source = '../outside-payload.class'
    Write-TextFile $evilManifestPath (ConvertTo-Json -InputObject $evilManifest -Depth 12)
    $r = Invoke-Install @('solo') $PkgEvilSource
    Check ($r.Code -eq 7) '結束代碼 7（來源路徑不安全）'
    Check (-not (Test-GameFile $P_SOLO)) '沒有從安裝包外複製檔案進遊戲目錄'

    Start-Case '34. manifest 路徑含空白區段：以路徑不安全拒絕（不是內部錯誤）'
    Reset-GameDir
    $PkgBadPath = Join-Path $TempRoot 'pkg-bad-path'
    if (Test-Path -LiteralPath $PkgBadPath) { Remove-Item -LiteralPath $PkgBadPath -Recurse -Force }
    Copy-Item -LiteralPath $PkgSolo -Destination $PkgBadPath -Recurse -Force
    $badManifestPath = Join-Path $PkgBadPath 'manifest.json'
    $badManifest = [System.IO.File]::ReadAllText($badManifestPath) | ConvertFrom-Json
    $badManifest.modules[0].files[0].path = 'zombie//ui/MdcSoloFixture.class'
    Write-TextFile $badManifestPath (ConvertTo-Json -InputObject $badManifest -Depth 12)
    $r = Invoke-Install @('solo') $PkgBadPath
    Check ($r.Code -eq 7) '結束代碼 7，而不是內部錯誤 1'
    Check (-not (Test-Path -LiteralPath (GameFile 'zombie'))) '沒有建立任何檔案'

    Start-Case '35. 沒有 journal 的備份：保留資料，不擅自當成可刪孤兒'
    Reset-GameDir
    Write-TextFile (GameFile ".mdc-patches/backup/$TX_ID/$P_CORE_RT") $C_CORE_RT_V1
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '結束代碼 0'
    Check (Test-GameFileIs ".mdc-patches/backup/$TX_ID/$P_CORE_RT" $C_CORE_RT_V1) '未辨識備份保留原內容'
    Check (Test-ModuleSet @('core', 'profiler')) '安裝照常完成'

    Start-Case '36. Windows 裝置名稱：在任何安裝寫入前拒絕'
    Reset-GameDir
    $PkgDevice = Join-Path $TempRoot 'pkg-device'
    Copy-Item -LiteralPath $PkgSolo -Destination $PkgDevice -Recurse -Force
    $deviceManifest = [System.IO.File]::ReadAllText((Join-Path $PkgDevice 'manifest.json')) | ConvertFrom-Json
    $deviceManifest.modules[0].files[0].path = 'zombie/mdc/NUL.class'
    Write-TextFile (Join-Path $PkgDevice 'manifest.json') (ConvertTo-Json -InputObject $deviceManifest -Depth 12)
    $r = Invoke-Install @('solo') $PkgDevice
    Check ($r.Code -eq 7) '裝置路徑以不安全路徑拒絕'
    Check (-not (Test-Path -LiteralPath (GameFile 'zombie'))) '尚未建立 payload 目錄'

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
        Check (Test-GameFileIs $P_CORE_RT $C_CORE_RT_V1) '另一份遊戲的 class 不變'
    } finally { $env:PZ_GAMEDIR = $previousGameDir }

    Start-Case '39. CLI 大寫模組 ID：切換變體仍安裝指定的模組'
    Reset-GameDir
    $r = Invoke-Install @('client-fixes-standard') $PkgV1
    Check ($r.Code -eq 0) '先安裝 standard'
    $r = Invoke-Install @('CLIENT-FIXES-LOWMEM') $PkgV1
    Check ($r.Code -eq 0) '大小寫不影響合法模組選擇'
    Check (Test-ModuleSet @('core', 'client-fixes-lowmem')) 'lowmem 確實取代 standard，沒有變成單純卸載'

    Start-Case '40. payload 來源子目錄是接點：SHA 相同仍不得離開套件'
    Reset-GameDir
    $PkgJunctionSource = Join-Path $TempRoot 'pkg-junction-source'
    Copy-Item -LiteralPath $PkgSolo -Destination $PkgJunctionSource -Recurse -Force
    $outsidePayload = Join-Path $TempRoot 'outside-payload'
    Move-Item -LiteralPath (Join-Path $PkgJunctionSource 'payload') -Destination $outsidePayload
    New-Item -ItemType Junction -Path (Join-Path $PkgJunctionSource 'payload') -Target $outsidePayload | Out-Null
    try {
        $r = Invoke-Install @('solo') $PkgJunctionSource
        Check ($r.Code -eq 7) '來源接點以 exit 7 拒絕'
        Check (-not (Test-GameFile $P_SOLO)) '沒有複製套件外的來源檔'
    } finally { [System.IO.Directory]::Delete((Join-Path $PkgJunctionSource 'payload')) }

    Start-Case '41. 已知 class 路徑上的第三方內容不能由 journal 冒認'
    Reset-GameDir
    Write-TextFile (GameFile $P_FIX_WS) 'third-party-content'
    Set-PendingTransaction -Entries @(
        [ordered]@{ path = $P_FIX_WS; backup = $null; backupSha256 = $null; sha256 = (Get-TextSha $C_FIX_SHARED_WS) }
    )
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 7) '內容不是該交易的影像，拒絕復原'
    Check (Test-GameFileIs $P_FIX_WS 'third-party-content') '已知路徑也不會刪除不明內容'
    Check (Test-GameFile '.mdc-patches/journal.json') 'journal 保留'

    Start-Case '42. 完整發布後被外部改動：保留現況與備份'
    Reset-GameDir
    Set-PendingTransaction
    Write-TextFile (GameFile $P_CORE_RT) 'unknown-external-change'
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 7) '未知影像拒絕'
    Check (Test-GameFileIs $P_CORE_RT 'unknown-external-change') '未知現況不被覆蓋'
    Check (Test-GameFileIs ".mdc-patches/backup/$TX_ID/$P_CORE_RT" $C_CORE_RT_V1) '前影像備份保留'
    Write-TextFile (GameFile $P_CORE_RT) $C_CORE_RT_V1
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
    Check (Test-GameFileIs $P_CORE_RT $C_CORE_RT_V1) 'class 已正確復原'
    Check (Test-GameFileIs ".mdc-patches/backup/$TX_ID/$P_CORE_RT" $C_CORE_RT_V1) '未發布終態前備份仍在'
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '釋放 journal 後安全重入'
    Check (-not (Test-GameFile '.mdc-patches/journal.json')) '完成後清除交易'

    Start-Case '44. 修復已安裝 class 的 hardlink：不能寫穿外部檔案'
    Reset-GameDir
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '前置安裝成功'
    $sentinel = Join-Path $TempRoot 'hardlink-class-sentinel.txt'
    Write-TextFile $sentinel 'keep-external-bytes'
    Remove-Item -LiteralPath (GameFile $P_PROF_MAIN)
    New-Item -ItemType HardLink -Path (GameFile $P_PROF_MAIN) -Target $sentinel | Out-Null
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 0) '只替換遊戲目錄內的檔名'
    Check (Test-GameFileIs $P_PROF_MAIN $C_PROF_MAIN) 'class 修復成功'
    Check ([System.IO.File]::ReadAllText($sentinel) -eq 'keep-external-bytes') '外部同 inode 內容保持不變'

    Start-Case '45. 備份損毀：整批復原之前拒絕'
    Reset-GameDir
    Set-PendingTransaction
    Write-TextFile (GameFile ".mdc-patches/backup/$TX_ID/$P_CORE_RT") 'corrupt-backup'
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 10) '備份指紋不符'
    Check (Test-GameFileIs $P_CORE_RT $C_CORE_RT_V2) '沒有用損毀備份蓋掉現況'
    Check (Test-GameFile '.mdc-patches/journal.json') '保留交易供修復備份後重跑'

    Start-Case '46. 復原目標變成目錄：不得複製進目錄後冒稱成功'
    Reset-GameDir
    Set-PendingTransaction
    Remove-Item -LiteralPath (GameFile $P_CORE_RT)
    New-Item -ItemType Directory -Path (GameFile $P_CORE_RT) | Out-Null
    $r = Invoke-Install @('profiler') $PkgV1
    Check ($r.Code -eq 7) '目標類型不同，拒絕'
    Check (@(Get-ChildItem -LiteralPath (GameFile $P_CORE_RT)).Count -eq 0) '沒有把 backup 複製到錯誤子路徑'
    Check (Test-GameFileIs ".mdc-patches/backup/$TX_ID/$P_CORE_RT" $C_CORE_RT_V1) '唯一備份保留'

    Start-Case '47. 全新安裝中斷且原包已不在：由交易影像復原'
    Reset-GameDir
    Write-TextFile (GameFile $P_CORE_RT) $C_CORE_RT_V1
    Write-TextFile (GameFile $P_CORE_PI) $C_CORE_PI_V1
    $journal = [ordered]@{
        schemaVersion = 1; txId = $TX_ID; committed = $false
        entries = @(
            [ordered]@{ path = $P_CORE_RT; backup = $null; backupSha256 = $null; sha256 = (Get-TextSha $C_CORE_RT_V1) }
            [ordered]@{ path = $P_CORE_PI; backup = $null; backupSha256 = $null; sha256 = (Get-TextSha $C_CORE_PI_V1) }
        )
        createdDirs = @('zombie', 'zombie/mdc')
    }
    Write-TextFile (GameFile '.mdc-patches/journal.json') (ConvertTo-Json -InputObject $journal -Depth 12)
    $r = Invoke-Installer @('-NonInteractive', '-Action', 'uninstall', '-GameDir', $GameDir, '-All')
    Check ($r.Code -eq 0) '不依賴 manifest 或尚未提交的 state'
    Check (-not (Test-GameFile $P_CORE_RT) -and -not (Test-GameFile $P_CORE_PI)) '只有本交易的完整後影像被移除'
    Check (-not (Test-GameFile '.mdc-patches/journal.json')) '交易已安全清除'
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
