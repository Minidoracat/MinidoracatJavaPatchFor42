#Requires -Version 5.1
<#
    Manage-Patches.ps1 — Minidoracat client patch 模組化安裝／卸載器（Windows PowerShell 5.1）

    設計要點（契約：deploy-client 安裝包契約）
      * 讀 manifest.json（Main 產生，schemaVersion=1）決定有哪些模組與檔案。
      * 安裝狀態寫在 <遊戲根>\.mdc-patches\state.json，這是 runtime 與 installer 的同一份真相。
      * install 是「加裝」：保留已安裝的其它模組；同 exclusiveGroup 的新變體才會替換舊變體。
      * uninstall 只移除所選；仍被保留模組依賴的模組（例如 core）自動保留。
      * 所有影像經暫存檔驗 SHA 後原子替換；交易先備份→journal→動檔→state→已完成終態。
        復原只接受交易前／後影像；未知現況保留。終態落地前不刪備份，清理中斷可重入。
      * status／開選單一律唯讀，只會提醒有未完成交易；復原只發生在 install／uninstall，
        而且順序固定：拿遊戲目錄鎖 → 確認遊戲沒在跑 → 復原 → 才重新讀 state 規劃。
      * 僅操作遊戲根內 loose .class 與固定管理資料；jar／exe／存檔／遊戲設定不改。
        SHA 用於辨識損毀或過期內容，不是簽章；同權限者同時偽造 metadata 與檔案不在防護範圍。
        未記錄的外來 class 拒裝；既有模組可重新安裝修復，卸載拒刪內容已變動的檔案。
        沒有 state.json 時，整組指紋吻合的舊版包可直接卸載；認不出的殘檔才拒絕並指回舊版 uninstaller。
      * 持鎖期間遊戲 exe 不能啟動（系統顯示檔案使用中）；外部 Java 啟動仍由行程檢查擋下。

    不需要 Python／JDK／任何第三方 MOD。
#>
[CmdletBinding()]
param(
    [ValidateSet('menu', 'status', 'install', 'uninstall')]
    [string]$Action = 'menu',
    [string]$GameDir,
    [string]$PackageDir,
    [string[]]$Modules,
    [switch]$All,
    [switch]$NonInteractive,
    [ValidateSet('auto', 'zh', 'en')]
    [string]$Lang = 'auto'
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------- 語言
# auto：Windows 顯示語言是中文就用中文，其它一律英文。選單可用 [L] 切換。
if ($Lang -eq 'auto') {
    $script:Lang = if ([System.Globalization.CultureInfo]::CurrentUICulture.Name -like 'zh*') { 'zh' } else { 'en' }
}

function L([string]$zh, [string]$en) {
    if ($script:Lang -eq 'en') { return $en }
    return $zh
}

# ---------------------------------------------------------------- 結束代碼
# 每個失敗都有專屬非 0 代碼，批次檔／自動化可以據此分辨原因。
$EXIT_OK = 0        # 成功
$EXIT_INTERNAL = 1  # 非預期錯誤
$EXIT_USAGE = 2     # 參數錯誤
$EXIT_GAMEDIR = 3   # 找不到／不合法的遊戲目錄
$EXIT_RUNNING = 4   # 遊戲或其 JVM 使用中（含行程檢查失敗＝fail-closed）
$EXIT_JAR = 5       # 遊戲版本（projectzomboid.jar SHA256）不符
$EXIT_PACKAGE = 6   # 安裝包本身有問題（manifest／payload 缺檔或指紋不符）
$EXIT_CONFLICT = 7  # 外來 loose 檔衝突／無法辨識的舊版 patch／路徑不安全
$EXIT_TAMPERED = 8  # 自家已安裝檔案被變造，拒絕刪除
$EXIT_ROLLEDBACK = 9      # 交易失敗，已回復到動作前狀態
$EXIT_ROLLBACKFAILED = 10 # 交易失敗且回復也失敗，需要人工處理

$script:FailCode = $null

$STATE_DIRNAME = '.mdc-patches'
$STATE_REL = '.mdc-patches/state.json'
$JOURNAL_REL = '.mdc-patches/journal.json'
$BACKUP_REL = '.mdc-patches/backup'

# ---------------------------------------------------------------- 輸出
function Write-Head([string]$m) { Write-Host ''; Write-Host $m -ForegroundColor Cyan }
function Write-Info([string]$m) { Write-Host $m }
function Write-Good([string]$m) { Write-Host $m -ForegroundColor Green }
function Write-Warn2([string]$m) { Write-Host "$(L '[警告]' '[Warning]') $m" -ForegroundColor Yellow }
function Write-Bad([string]$m) { Write-Host "$(L '[錯誤]' '[Error]') $m" -ForegroundColor Red }

function Fail([int]$code, [string]$message) {
    $script:FailCode = $code
    throw $message
}

# ---------------------------------------------------------------- 小工具
function ConvertTo-Array($value) {
    if ($null -eq $value) { return @() }
    return @($value)
}

function Get-Prop($obj, [string]$name, $default = $null) {
    if ($null -eq $obj) { return $default }
    # journal 在記憶體裡是 [ordered]@{}，從磁碟讀回來是 PSCustomObject——兩種都要能取值
    if ($obj -is [System.Collections.IDictionary]) {
        if (-not $obj.Contains($name)) { return $default }
        if ($null -eq $obj[$name]) { return $default }
        return $obj[$name]
    }
    $p = $obj.PSObject.Properties[$name]
    if ($null -eq $p -or $null -eq $p.Value) { return $default }
    return $p.Value
}

function Get-Sha256([string]$path) {
    try {
        return (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    } catch {
        # 讀不到就無法判斷該不該碰它——當成「目錄狀態不允許動作」處理，不要往下猜
        Fail $EXIT_CONFLICT (L "無法讀取檔案（可能被其它程式佔用或權限不足）：$path`n  $($_.Exception.Message)" "Cannot read file (it may be in use by another program, or access is denied): $path`n  $($_.Exception.Message)")
    }
}

function ConvertTo-JsonBytes($object) {
    return ,(New-Object System.Text.UTF8Encoding($false)).GetBytes((ConvertTo-Json -InputObject $object -Depth 12))
}

function Get-BytesSha256([byte[]]$bytes) {
    $hash = [System.Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($hash.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $hash.Dispose() }
}

# 發布完整影像，不原地覆寫 leaf；hardlink 的其他名稱仍保留原內容。
function Publish-File([string]$path, [byte[]]$bytes, [string]$SourcePath, [string]$ExpectedSha) {
    if (Test-Path -LiteralPath $path -PathType Container) { throw (L "目標不是檔案：$path" "Target is not a file: $path") }
    $dir = Split-Path -Parent $path
    if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    $tmp = Join-Path $dir ([Guid]::NewGuid().ToString('N') + '.tmp')
    $created = $false
    try {
        $stream = [System.IO.File]::Open($tmp, [System.IO.FileMode]::CreateNew,
            [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        $created = $true
        try {
            if ($SourcePath) {
                $inputStream = [System.IO.File]::OpenRead($SourcePath)
                try { $inputStream.CopyTo($stream) } finally { $inputStream.Dispose() }
            } else {
                $stream.Write($bytes, 0, $bytes.Length)
            }
            $stream.Flush($true)
        } finally { $stream.Dispose() }
        if ($ExpectedSha -and (Get-Sha256 $tmp) -ne $ExpectedSha) { throw (L "發布前指紋不符：$path" "Checksum mismatch before writing: $path") }
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            [System.IO.File]::Replace($tmp, $path, [NullString]::Value)
        } else {
            [System.IO.File]::Move($tmp, $path)
        }
    } finally {
        if ($created -and [System.IO.File]::Exists($tmp)) {
            try { [System.IO.File]::Delete($tmp) }
            catch { Write-Warn2 (L "暫存檔未能清除（$tmp）：$($_.Exception.Message)" "Could not remove temporary file ($tmp): $($_.Exception.Message)") }
        }
    }
}

function Write-JsonFile([string]$path, $object) {
    Publish-File -path $path -bytes (ConvertTo-JsonBytes $object)
}

function Read-JsonFile([string]$path) {
    $raw = [System.IO.File]::ReadAllText($path)
    return ($raw | ConvertFrom-Json)
}

# ---------------------------------------------------------------- 路徑安全
# manifest／state／journal 內的 path 一律是「遊戲根相對、正斜線」；檔案再多一條 .class 限制。
function Assert-SafeRelBase([string]$rel, [string]$origin) {
    if ([string]::IsNullOrWhiteSpace($rel)) {
        Fail $EXIT_CONFLICT (L "$origin 有空白路徑。" "$origin contains an empty path.")
    }
    if ($rel -match '[\\]') {
        Fail $EXIT_CONFLICT (L "$origin 的路徑必須用正斜線：$rel" "$origin path must use forward slashes: $rel")
    }
    if ($rel -match '^[/]' -or $rel -match '^[A-Za-z]:') {
        Fail $EXIT_CONFLICT (L "$origin 的路徑不可為絕對路徑：$rel" "$origin path must not be absolute: $rel")
    }
    if ($rel -match '(^|/)\.\.(/|$)' -or $rel -match '(^|/)\.(/|$)') {
        Fail $EXIT_CONFLICT (L "$origin 的路徑含相對跳脫，拒絕處理：$rel" "$origin path escapes its folder; refused: $rel")
    }
    if ($rel -match '//' -or $rel.EndsWith('/')) {
        Fail $EXIT_CONFLICT (L "$origin 的路徑含空白區段：$rel" "$origin path has an empty segment: $rel")
    }
    # 區段結尾的點與空白會被 Windows 吃掉（a./b 等於 a/b），等於同一個檔案的另一種寫法；
    # 所有權比對是逐字串比的，接受別名就等於放行沒被登記過的檔案。
    if ($rel -match '[ .](/|$)') {
        Fail $EXIT_CONFLICT (L "$origin 的路徑區段結尾不可為空白或點：$rel" "$origin path segments must not end with a space or dot: $rel")
    }
    if ($rel -match '[\x00-\x1f:*?"<>|]') {
        Fail $EXIT_CONFLICT (L "$origin 的路徑含非法字元：$rel" "$origin path contains invalid characters: $rel")
    }
    foreach ($segment in ($rel -split '/')) {
        if ($segment -match '^(CON|PRN|AUX|NUL|CONIN\$|CONOUT\$|COM[1-9¹²³]|LPT[1-9¹²³])(\.|$)') {
            Fail $EXIT_CONFLICT (L "$origin 的路徑含 Windows 保留裝置名稱：$rel" "$origin path contains a reserved Windows device name: $rel")
        }
    }
}

function Assert-SafeRelPath([string]$rel, [string]$origin) {
    Assert-SafeRelBase $rel $origin
    if ($rel -notmatch '\.class$') {
        # 只允許 loose class：從根本上擋掉改 jar／啟動 JSON／其它 agent 檔的可能
        Fail $EXIT_CONFLICT (L "$origin 只允許 .class 檔，拒絕處理：$rel" "$origin only allows .class files; refused: $rel")
    }
}

function Get-FullTargetPath([string]$gameRoot, [string]$rel) {
    $full = [System.IO.Path]::GetFullPath((Join-Path $gameRoot ($rel -replace '/', '\')))
    $rootFull = [System.IO.Path]::GetFullPath($gameRoot)
    if (-not $rootFull.EndsWith('\')) { $rootFull += '\' }
    if (-not $full.StartsWith($rootFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        Fail $EXIT_CONFLICT (L "路徑解析後跑出遊戲目錄，拒絕處理：$rel" "Path resolves outside the game folder; refused: $rel")
    }
    return $full
}

function Assert-NoReparsePoint([string]$gameRoot, [string]$rel) {
    # 目錄接點／符號連結可以把寫入導到遊戲目錄外面，逐層擋掉。
    $cur = [System.IO.Path]::GetFullPath($gameRoot)
    foreach ($seg in ($rel -split '/')) {
        $cur = Join-Path $cur $seg
        if (Test-Path -LiteralPath $cur) {
            $item = Get-Item -LiteralPath $cur -Force
            if ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) {
                Fail $EXIT_CONFLICT (L "路徑上有符號連結／接點（$cur），拒絕處理：$rel" "Path contains a symbolic link or junction ($cur); refused: $rel")
            }
        }
    }
}

function Remove-EmptyAncestor([string]$gameRoot, [string]$rel) {
    $rootFull = [System.IO.Path]::GetFullPath($gameRoot).TrimEnd('\')
    $dir = Split-Path -Parent (Get-FullTargetPath $gameRoot $rel)
    while ($dir -and $dir.TrimEnd('\').Length -gt $rootFull.Length -and (Test-Path -LiteralPath $dir)) {
        if (@(Get-ChildItem -LiteralPath $dir -Force).Count -ne 0) { break }
        Remove-Item -LiteralPath $dir -Force -ErrorAction SilentlyContinue
        if (Test-Path -LiteralPath $dir) { break }
        $dir = Split-Path -Parent $dir
    }
}

# ---------------------------------------------------------------- 遊戲目錄
function Find-SteamGameDir {
    try {
        $steam = (Get-ItemProperty 'HKCU:\Software\Valve\Steam' -ErrorAction SilentlyContinue).SteamPath
    } catch { $steam = $null }
    if (-not $steam) { return $null }
    $roots = @($steam)
    $vdf = Join-Path $steam 'steamapps\libraryfolders.vdf'
    if (Test-Path -LiteralPath $vdf) {
        $matches2 = (Select-String -Path $vdf -Pattern '"path"\s+"([^"]+)"' -AllMatches).Matches
        foreach ($m in $matches2) { $roots += $m.Groups[1].Value.Replace('\\', '\') }
    }
    foreach ($r in $roots) {
        $g = Join-Path $r 'steamapps\common\ProjectZomboid'
        if (Test-Path -LiteralPath (Join-Path $g 'ProjectZomboid64.exe')) {
            return (Get-Item -LiteralPath $g).FullName
        }
    }
    return $null
}

function Test-GameDir([string]$dir) {
    if ([string]::IsNullOrWhiteSpace($dir)) { return $false }
    if (-not (Test-Path -LiteralPath $dir -PathType Container)) { return $false }
    return (Test-Path -LiteralPath (Join-Path $dir 'ProjectZomboid64.exe'))
}

function Resolve-GameDir([string]$explicit, [string]$scriptDir, [bool]$interactive) {
    if ($explicit) {
        if (Test-GameDir $explicit) { return (Get-Item -LiteralPath $explicit).FullName }
        Fail $EXIT_GAMEDIR (L "指定的 -GameDir 不是 Project Zomboid 遊戲目錄（找不到 ProjectZomboid64.exe）：$explicit" "The -GameDir folder is not a Project Zomboid game folder (ProjectZomboid64.exe not found): $explicit")
    }
    $candidates = @()
    if ($env:PZ_GAMEDIR) { $candidates += $env:PZ_GAMEDIR }
    if ($scriptDir) { $candidates += $scriptDir; $candidates += (Split-Path -Parent $scriptDir) }
    foreach ($c in $candidates) {
        if (Test-GameDir $c) { return (Get-Item -LiteralPath $c).FullName }
    }
    Write-Info (L '正在透過 Steam 尋找 Project Zomboid...' 'Looking for Project Zomboid through Steam...')
    $steamDir = Find-SteamGameDir
    if ($steamDir) { return $steamDir }
    if (-not $interactive) {
        Fail $EXIT_GAMEDIR (L '找不到 Project Zomboid 遊戲目錄。請用 -GameDir 指定，或設定環境變數 PZ_GAMEDIR。' 'Project Zomboid game folder not found. Pass it with -GameDir, or set the PZ_GAMEDIR environment variable.')
    }
    Write-Warn2 (L '自動偵測失敗。遊戲目錄可從 Steam → Project Zomboid → 管理 → 瀏覽本機檔案 取得。' 'Could not find the game automatically. In Steam, right-click Project Zomboid > Manage > Browse local files to find the game folder.')
    while ($true) {
        $ans = Read-Host (L '請貼上遊戲目錄完整路徑（直接按 Enter 取消）' 'Paste the full path of the game folder (press Enter to cancel)')
        if ([string]::IsNullOrWhiteSpace($ans)) {
            Fail $EXIT_GAMEDIR (L '使用者取消：沒有提供遊戲目錄。' 'Cancelled: no game folder was given.')
        }
        $ans = $ans.Trim('"').Trim()
        if (Test-GameDir $ans) { return (Get-Item -LiteralPath $ans).FullName }
        Write-Bad (L "「$ans」裡沒有 ProjectZomboid64.exe，請再試一次。" "ProjectZomboid64.exe was not found in `"$ans`". Please try again.")
    }
}

# ---------------------------------------------------------------- 行程檢查
function Assert-GameNotRunning([string]$gameRoot) {
    $rootFull = [System.IO.Path]::GetFullPath($gameRoot).TrimEnd('\')
    $rootPrefix = $rootFull + '\'
    try {
        $procs = @(Get-Process -ErrorAction Stop)
    } catch {
        # 列舉失敗就 fail-closed：寧可不裝，也不要在遊戲跑的時候動 class 檔
        Fail $EXIT_RUNNING (L "無法列舉系統行程，無法確認遊戲已關閉（fail-closed）：$($_.Exception.Message)" "Cannot list running processes, so cannot confirm the game is closed (fail-closed): $($_.Exception.Message)")
    }
    $blockNames = @('ProjectZomboid64', 'ProjectZomboid32', 'ProjectZomboid', 'PZLauncher')
    $hits = @()
    $javaProcs = @()
    foreach ($p in $procs) {
        if ($blockNames -contains $p.ProcessName) { $hits += "$($p.ProcessName) (PID $($p.Id))"; continue }
        if ($p.ProcessName -eq 'java' -or $p.ProcessName -eq 'javaw') { $javaProcs += $p }
    }

    # 遊戲也可能是被遊戲目錄外的 JDK（Temurin／scoop／IDE／專用伺服器腳本）啟動的，
    # 這時 Process.Path 不在遊戲根，只能看命令列。讀不到資訊一律 fail-closed，
    # 不可以把「查不到」當成「沒在跑」。
    if ($javaProcs.Count -gt 0) {
        $info = @{}
        try {
            foreach ($w in @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction Stop)) {
                $info[[int]$w.ProcessId] = @{ Cmd = [string]$w.CommandLine; Exe = [string]$w.ExecutablePath }
            }
        } catch {
            Fail $EXIT_RUNNING ((L "有 java／javaw 行程在跑，但無法查詢它們的命令列以判斷是不是 Project Zomboid（fail-closed）：" "A java/javaw process is running, but its command line cannot be read to tell whether it is Project Zomboid (fail-closed): ") +
                $_.Exception.Message)
        }
        foreach ($p in $javaProcs) {
            $meta = $info[[int]$p.Id]
            if ($null -eq $meta -or ([string]::IsNullOrWhiteSpace($meta.Cmd) -and [string]::IsNullOrWhiteSpace($meta.Exe))) {
                Fail $EXIT_RUNNING (L ("讀不到 $($p.ProcessName) (PID $($p.Id)) 的執行檔／命令列，無法確認它不是 Project Zomboid" +
                    "（fail-closed）。請關閉所有 java 行程，或用系統管理員身分再執行一次。") ("Cannot read the program path or command line of $($p.ProcessName) (PID $($p.Id)), so cannot confirm it is not Project Zomboid" +
                    " (fail-closed). Close all java processes, or run this again as administrator."))
            }
            $isPz = $false
            if ($meta.Exe -and $meta.Exe.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) { $isPz = $true }
            if (-not $isPz -and $meta.Cmd) {
                if ($meta.Cmd.IndexOf($rootFull, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) { $isPz = $true }
                elseif ($meta.Cmd -match 'zombie\.gameStates\.MainScreenState' -or
                        $meta.Cmd -match 'zombie\.network\.GameServer' -or
                        $meta.Cmd -match 'zombie\.core\.raknet\.RakNetPeerInterface') { $isPz = $true }
            }
            if ($isPz) { $hits += "$($p.ProcessName) (PID $($p.Id))" }
        }
    }
    if ($hits.Count -gt 0) {
        Fail $EXIT_RUNNING ((L "Project Zomboid 或它的 JVM 還在執行，請先完全關閉遊戲：" "Project Zomboid or its Java process is still running. Close the game completely first: ") + ($hits -join ', '))
    }
}

# ---------------------------------------------------------------- 同遊戲目錄互斥
# 兩個管理器同時開著的話，會互相把對方正在進行的 journal 當成「中斷的交易」去復原。
# 鎖定既有必要 exe 的檔案物件：跨 session／目錄別名互斥，持鎖期間也不能啟動它。
function Enter-GameRootLock([string]$gameRoot) {
    try {
        return [System.IO.File]::Open((Join-Path $gameRoot 'ProjectZomboid64.exe'),
            [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::None)
    } catch {
        Fail $EXIT_RUNNING (L "遊戲程式或另一個管理器正在使用此目錄，或無法取得獨占鎖：$gameRoot`n$($_.Exception.Message)" "The game or another copy of this manager is using this folder, or an exclusive lock could not be taken: $gameRoot`n$($_.Exception.Message)")
    }
}

function Exit-GameRootLock($lock) {
    if ($lock) { $lock.Dispose() }
}

# ---------------------------------------------------------------- 安裝包
function Resolve-PackageDir([string]$explicit, [string]$scriptDir) {
    $candidates = @()
    if ($explicit) { $candidates += $explicit }
    else {
        $candidates += $scriptDir
        $candidates += (Join-Path $scriptDir 'package')
        $candidates += (Split-Path -Parent $scriptDir)
    }
    foreach ($c in $candidates) {
        if ($c -and (Test-Path -LiteralPath (Join-Path $c 'manifest.json'))) {
            return (Get-Item -LiteralPath $c).FullName
        }
    }
    if ($explicit) {
        Fail $EXIT_PACKAGE (L "指定的 -PackageDir 裡沒有 manifest.json：$explicit" "No manifest.json in the -PackageDir folder: $explicit")
    }
    return $null
}

function Read-Manifest([string]$packageDir) {
    $path = Join-Path $packageDir 'manifest.json'
    try { $m = Read-JsonFile $path } catch { Fail $EXIT_PACKAGE (L "manifest.json 無法解析：$($_.Exception.Message)" "manifest.json cannot be read: $($_.Exception.Message)") }
    if ([int](Get-Prop $m 'schemaVersion' 0) -ne 1) {
        Fail $EXIT_PACKAGE (L "manifest.json 的 schemaVersion 不是 1（實際：$(Get-Prop $m 'schemaVersion' '(缺)')）——請換用對應版本的安裝器。" "manifest.json schemaVersion is not 1 (found: $(Get-Prop $m 'schemaVersion' '(missing)')). Use the matching installer version.")
    }
    if (-not (Get-Prop $m 'jarSha256')) { Fail $EXIT_PACKAGE (L 'manifest.json 缺 jarSha256。' 'manifest.json is missing jarSha256.') }
    $mods = ConvertTo-Array (Get-Prop $m 'modules')
    if ($mods.Count -eq 0) { Fail $EXIT_PACKAGE (L 'manifest.json 沒有任何 module。' 'manifest.json has no modules.') }
    $seenId = @{}
    $ownerOf = @{}
    foreach ($mod in $mods) {
        $id = Get-Prop $mod 'id'
        if (-not $id) { Fail $EXIT_PACKAGE (L 'manifest.json 有 module 缺 id。' 'manifest.json has a module without an id.') }
        if ($seenId.ContainsKey($id)) { Fail $EXIT_PACKAGE (L "manifest.json 有重複的 module id：$id" "manifest.json has a duplicate module id: $id") }
        $seenId[$id] = $true
        $group = Get-Prop $mod 'exclusiveGroup'
        foreach ($f in (ConvertTo-Array (Get-Prop $mod 'files'))) {
            $p = Get-Prop $f 'path'
            Assert-SafeRelPath $p (L "manifest.json 的 module「$id」" "manifest.json module '$id'")
            if (-not (Get-Prop $f 'sha256')) { Fail $EXIT_PACKAGE (L "manifest.json 的 $id / $p 缺 sha256。" "manifest.json $id / $p is missing sha256.") }
            $srcRel = [string](Get-Prop $f 'source')
            if (-not $srcRel) { Fail $EXIT_PACKAGE (L "manifest.json 的 $id / $p 缺 source。" "manifest.json $id / $p is missing source.") }
            # source 是 Copy-Item 的來源路徑，跟 path 一樣不准跳出安裝包資料夾
            Assert-SafeRelBase $srcRel (L "manifest.json 的 module「$id」的 source" "manifest.json module '$id' source")
            # 同一 path 只能由一個模組擁有；同 exclusiveGroup 的變體不會同時安裝，允許共用 path
            if ($ownerOf.ContainsKey($p)) {
                $prev = $ownerOf[$p]
                if (-not $group -or $prev.Group -ne $group) {
                    Fail $EXIT_PACKAGE (L "manifest.json 的檔案 $p 同時被 $($prev.Id) 與 $id 擁有（且不同 exclusiveGroup）。" "manifest.json file $p is owned by both $($prev.Id) and $id (in different exclusiveGroups).")
                }
            } else {
                $ownerOf[$p] = @{ Id = $id; Group = $group }
            }
        }
    }
    foreach ($mod in $mods) {
        foreach ($req in (ConvertTo-Array (Get-Prop $mod 'requires'))) {
            if (-not $seenId.ContainsKey($req)) {
                Fail $EXIT_PACKAGE (L "manifest.json 的 module「$(Get-Prop $mod 'id')」依賴不存在的 $req。" "manifest.json module '$(Get-Prop $mod 'id')' requires missing module $req.")
            }
        }
    }
    return $m
}

function Get-Module($manifest, [string]$id) {
    foreach ($m in (ConvertTo-Array (Get-Prop $manifest 'modules'))) {
        if ((Get-Prop $m 'id') -eq $id) { return $m }
    }
    return $null
}

# 英文模式的模組顯示名：nameEn 優先（state.json 不存 nameEn，可由 $manifest 補），其次是已知 id 的內建英文名，最後退回 name。
$script:ModuleNamesEn = @{
    'core'                  = 'Core components'
    'profiler'              = 'DevProfiler (for mod developers)'
    'client-fixes-standard' = 'Client fixes - standard (32 GB RAM or more)'
    'client-fixes-lowmem'   = 'Client fixes - low memory (less than 32 GB RAM)'
}

function Get-ModuleName($mod, [string]$id, $manifest = $null) {
    if ($script:Lang -eq 'en') {
        $en = Get-Prop $mod 'nameEn'
        if (-not $en) { $en = Get-Prop (Get-Module $manifest $id) 'nameEn' }
        if ($en) { return $en }
        if ($script:ModuleNamesEn.ContainsKey($id)) { return $script:ModuleNamesEn[$id] }
    }
    return (Get-Prop $mod 'name' $id)
}

function Get-ModuleLabel($manifest, [string]$id) {
    $m = Get-Module $manifest $id
    if ($m) {
        $n = Get-ModuleName $m $id
        return "$n ($id)"
    }
    return $id
}

function Assert-PayloadIntact($manifest, [string]$packageDir, [string[]]$moduleIds) {
    foreach ($id in $moduleIds) {
        $mod = Get-Module $manifest $id
        foreach ($f in (ConvertTo-Array (Get-Prop $mod 'files'))) {
            $sourceRel = Get-Prop $f 'source'
            Assert-NoReparsePoint $packageDir $sourceRel
            $src = Get-FullTargetPath $packageDir $sourceRel
            if (-not (Test-Path -LiteralPath $src -PathType Leaf)) {
                Fail $EXIT_PACKAGE (L "安裝包缺檔：$(Get-Prop $f 'source')（模組 $id）——請重新完整解壓縮 zip。" "Package file missing: $(Get-Prop $f 'source') (module $id). Extract the whole zip again.")
            }
            $actual = Get-Sha256 $src
            if ($actual -ne (Get-Prop $f 'sha256').ToLowerInvariant()) {
                Fail $EXIT_PACKAGE (L "安裝包檔案損毀：$(Get-Prop $f 'source')（模組 $id）——請重新完整解壓縮 zip。" "Package file is damaged: $(Get-Prop $f 'source') (module $id). Extract the whole zip again.")
            }
        }
    }
}

# ---------------------------------------------------------------- 安裝狀態
function Read-State([string]$gameRoot) {
    $path = Get-FullTargetPath $gameRoot $STATE_REL
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
    try { $s = Read-JsonFile $path } catch {
        Fail $EXIT_INTERNAL (L "$STATE_REL 無法解析（$($_.Exception.Message)）——請手動檢查 $path。" "$STATE_REL cannot be read ($($_.Exception.Message)). Please check $path manually.")
    }
    if ([int](Get-Prop $s 'schemaVersion' 0) -ne 1) {
        Fail $EXIT_INTERNAL (L "$STATE_REL 的 schemaVersion 不是 1，這個安裝器不認得，請用對應版本處理。" "$STATE_REL schemaVersion is not 1, which this installer does not understand. Use the matching installer version.")
    }
    return $s
}

function Get-StateModuleIds($state) {
    if (-not $state) { return @() }
    return @(ConvertTo-Array (Get-Prop $state 'modules') | ForEach-Object { Get-Prop $_ 'id' })
}

function Get-StateOwnedFiles($state) {
    # path -> @{ Sha; ModuleId }
    $map = @{}
    foreach ($m in (ConvertTo-Array (Get-Prop $state 'modules'))) {
        foreach ($f in (ConvertTo-Array (Get-Prop $m 'files'))) {
            $map[(Get-Prop $f 'path')] = @{ Sha = (Get-Prop $f 'sha256').ToLowerInvariant(); ModuleId = (Get-Prop $m 'id') }
        }
    }
    return $map
}

function New-StateObject([string]$jarSha, [string]$packageVersion, $moduleEntries) {
    return [ordered]@{
        schemaVersion  = 1
        jarSha256      = $jarSha
        packageVersion = $packageVersion
        modules        = @($moduleEntries)
    }
}

# ---------------------------------------------------------------- 舊版包辨識
# legacyPackages 必須「整組」精確吻合才算辨識成功：standard 與 lowmem 有 6/8 個檔案指紋相同，
# 只看單檔會誤判成另一個變體。辨識只看檔案指紋、不看目前的 jar：最需要清掉舊版包的時候，
# 正是遊戲已經更新、舊 class 留在新 jar 旁邊的時候。
function Find-LegacyPackages($manifest, [string]$gameRoot) {
    $identified = @()
    $touchedPaths = @{}
    $known = @{}
    foreach ($lp in (ConvertTo-Array (Get-Prop $manifest 'legacyPackages'))) {
        $files = ConvertTo-Array (Get-Prop $lp 'files')
        $allMatch = $files.Count -gt 0
        $anyPresent = $false
        foreach ($f in $files) {
            $p = Get-Prop $f 'path'
            Assert-SafeRelPath $p (L "manifest.json 的 legacyPackage「$(Get-Prop $lp 'id')」" "manifest.json legacyPackage '$(Get-Prop $lp 'id')'")
            $known[$p] = $true
            $full = Get-FullTargetPath $gameRoot $p
            if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { $allMatch = $false; continue }
            $anyPresent = $true
            if ((Get-Sha256 $full) -ne (Get-Prop $f 'sha256').ToLowerInvariant()) { $allMatch = $false }
        }
        if ($allMatch) {
            $identified += $lp
            foreach ($f in $files) { $touchedPaths[(Get-Prop $f 'path')] = $true }
        } elseif ($anyPresent) {
            # 記錄下來，衝突處理時用得到（部分吻合＝不明狀態，一律不覆蓋）
        }
    }
    return @{
        Packages     = $identified
        AdoptedPaths = $touchedPaths
        KnownPaths   = $known
    }
}

# ---------------------------------------------------------------- 交易
function Get-JournalPath([string]$gameRoot) { return (Get-FullTargetPath $gameRoot $JOURNAL_REL) }

# SHA 是影像辨識，不是簽章授權。同權限者連同檔案、metadata 一起改寫不在威脅模型內。
# 路徑仍限 loose class／固定 state；無法辨認的現況保留，不依路徑名稱猜所有權。
function Assert-JournalSafe([string]$gameRoot, $journal) {
    if ([int](Get-Prop $journal 'schemaVersion' 0) -ne 1) {
        Fail $EXIT_CONFLICT (L "$JOURNAL_REL 的 schemaVersion 不是 1，這個安裝器不認得，請手動檢查後再試。" "$JOURNAL_REL schemaVersion is not 1, which this installer does not understand. Check it manually, then try again.")
    }
    $txId = [string](Get-Prop $journal 'txId' '')
    if ($txId -notmatch '^[0-9a-f]{12}$') {
        Fail $EXIT_CONFLICT (L "$JOURNAL_REL 的 txId 格式不合法（必須是 12 位小寫十六進位），拒絕依它動檔案。" "$JOURNAL_REL has an invalid txId (must be 12 lowercase hex digits); no files will be changed based on it.")
    }
    Assert-NoReparsePoint $gameRoot "$BACKUP_REL/$txId"
    $entries = @(ConvertTo-Array (Get-Prop $journal 'entries'))
    if ($entries.Count -eq 0) {
        Fail $EXIT_CONFLICT (L "$JOURNAL_REL 沒有任何 entries，無法判斷該復原什麼，拒絕動檔案。" "$JOURNAL_REL has no entries, so there is no way to tell what to restore; no files will be changed.")
    }
    $committed = Get-Prop $journal 'committed'
    if ($committed -isnot [bool]) {
        Fail $EXIT_CONFLICT (L "$JOURNAL_REL 的 committed 必須是布林值，拒絕猜測交易狀態。" "$JOURNAL_REL committed must be true or false; refusing to guess the transaction state.")
    }
    $entryDirs = @{}
    $seen = @{}
    foreach ($e in $entries) {
        $rel = [string](Get-Prop $e 'path' '')
        # 只准碰自家 .class 與那一個固定的 state.json
        if ($rel -ne $STATE_REL) { Assert-SafeRelPath $rel (L "$JOURNAL_REL 的 entries" "$JOURNAL_REL entries") }
        Assert-NoReparsePoint $gameRoot $rel
        if ($seen.ContainsKey($rel)) { Fail $EXIT_CONFLICT (L "$JOURNAL_REL 的目標重複：$rel" "$JOURNAL_REL lists the same target twice: $rel") }
        $seen[$rel] = $true
        foreach ($field in @('backupSha256', 'sha256')) {
            $present = if ($e -is [System.Collections.IDictionary]) { $e.Contains($field) } else { $null -ne $e.PSObject.Properties[$field] }
            if (-not $present) {
                Fail $EXIT_CONFLICT (L "$JOURNAL_REL 缺少 $field，無法辨識交易影像：$rel" "$JOURNAL_REL is missing $field, so the transaction files cannot be identified: $rel")
            }
            $sha = Get-Prop $e $field
            if ($null -ne $sha -and [string]$sha -cnotmatch '^[0-9a-f]{64}$') {
                Fail $EXIT_CONFLICT (L "$JOURNAL_REL 的 $field 不合法：$rel" "$JOURNAL_REL has an invalid $field`: $rel")
            }
        }
        $parent = $rel
        while ($parent.Contains('/')) {
            $parent = $parent.Substring(0, $parent.LastIndexOf('/'))
            $entryDirs[$parent] = $true
        }
        $backup = Get-Prop $e 'backup'
        if (($null -eq $backup) -ne ($null -eq (Get-Prop $e 'backupSha256'))) {
            Fail $EXIT_CONFLICT (L "$JOURNAL_REL 的備份與指紋不一致：$rel" "$JOURNAL_REL backup and checksum do not match: $rel")
        }
        if ($null -ne $backup) {
            # 備份一律存成 backup/<txId>/<原路徑>；不一致就是被改過的 journal
            if ([string]$backup -ne $rel) {
                Fail $EXIT_CONFLICT (L "$JOURNAL_REL 的備份路徑與目標路徑不符（$backup 對 $rel），拒絕依它動檔案。" "$JOURNAL_REL backup path does not match its target ($backup vs $rel); no files will be changed based on it.")
            }
            Assert-NoReparsePoint $gameRoot "$BACKUP_REL/$txId/$rel"
        }
        if (-not $committed) { $null = Get-EntryImage $gameRoot $e }
    }
    foreach ($d in (ConvertTo-Array (Get-Prop $journal 'createdDirs'))) {
        Assert-SafeRelBase ([string]$d) (L "$JOURNAL_REL 的 createdDirs" "$JOURNAL_REL createdDirs")
        Assert-NoReparsePoint $gameRoot ([string]$d)
        if (-not $entryDirs.ContainsKey([string]$d)) {
            Fail $EXIT_CONFLICT (L "$JOURNAL_REL 的 createdDirs 包含與交易檔案無關的目錄 $d，拒絕刪除。" "$JOURNAL_REL createdDirs lists folder $d, which is unrelated to the transaction files; it will not be deleted.")
        }
    }
}

function Get-EntryImage([string]$gameRoot, $entry) {
    $rel = Get-Prop $entry 'path'
    Assert-NoReparsePoint $gameRoot $rel
    $target = Get-FullTargetPath $gameRoot $rel
    if (Test-Path -LiteralPath $target -PathType Container) {
        Fail $EXIT_CONFLICT (L "復原目標變成目錄，已保留交易與備份：$rel" "The file to restore is now a folder; transaction record and backup were kept: $rel")
    }
    if (-not (Test-Path -LiteralPath $target -PathType Leaf)) { return 'absent' }
    $sha = Get-Sha256 $target
    if ($sha -eq (Get-Prop $entry 'backupSha256')) { return 'pre' }
    if ($sha -eq (Get-Prop $entry 'sha256')) { return 'post' }
    Fail $EXIT_CONFLICT (L "檔案既非交易前影像也非交易後影像，不會覆寫或刪除：$rel。請保留 journal 與備份，人工確認。" "File matches neither the before nor the after version, so it will not be overwritten or deleted: $rel. Keep the journal and backup and check it manually.")
}

function Restore-Transaction([string]$gameRoot, $journal) {
    $problems = @()
    $entries = @(ConvertTo-Array (Get-Prop $journal 'entries'))
    $backupRoot = Get-FullTargetPath $gameRoot "$BACKUP_REL/$(Get-Prop $journal 'txId')"
    # 整批備份與現況先驗完，任何一筆不可信都不開始復原。
    foreach ($entry in $entries) {
        $rel = Get-Prop $entry 'path'
        try {
            $null = Get-EntryImage $gameRoot $entry
            if (Get-Prop $entry 'backup') {
                Assert-NoReparsePoint $backupRoot $rel
                $backup = Get-FullTargetPath $backupRoot $rel
                if (-not (Test-Path -LiteralPath $backup -PathType Leaf) -or
                        (Get-Sha256 $backup) -ne (Get-Prop $entry 'backupSha256')) {
                    throw (L "備份檔遺失或指紋不符：$rel" "Backup file is missing or its checksum does not match: $rel")
                }
            }
        } catch { $problems += $_.Exception.Message }
    }
    if ($problems.Count -gt 0) { return @($problems) }
    for ($i = $entries.Count - 1; $i -ge 0; $i--) {
        $entry = $entries[$i]
        $rel = Get-Prop $entry 'path'
        try {
            $image = Get-EntryImage $gameRoot $entry
            if ($image -eq 'pre') { continue }
            $target = Get-FullTargetPath $gameRoot $rel
            if (Get-Prop $entry 'backup') {
                Publish-File -path $target -SourcePath (Get-FullTargetPath $backupRoot $rel) `
                    -ExpectedSha (Get-Prop $entry 'backupSha256')
                if ((Get-Sha256 $target) -ne (Get-Prop $entry 'backupSha256')) { throw (L "復原後指紋不符：$rel" "Checksum mismatch after restore: $rel") }
            } elseif ($image -eq 'post') {
                Remove-Item -LiteralPath $target -Force
            }
        } catch { $problems += (L "還原 $rel 失敗：$($_.Exception.Message)" "Failed to restore $rel`: $($_.Exception.Message)") }
    }
    $dirs = @(ConvertTo-Array (Get-Prop $journal 'createdDirs'))
    for ($i = $dirs.Count - 1; $i -ge 0; $i--) {
        try {
            $d = Get-FullTargetPath $gameRoot ([string]$dirs[$i])
            if (-not (Test-Path -LiteralPath $d)) { continue }
            if (@(Get-ChildItem -LiteralPath $d -Force).Count -eq 0) { Remove-Item -LiteralPath $d -Force }
        } catch { $problems += (L "移除交易新建的目錄失敗：$($_.Exception.Message)" "Failed to remove a folder created by the transaction: $($_.Exception.Message)") }
    }
    if ($problems.Count -eq 0) {
        try {
            $journal.committed = $true
            Write-JsonFile (Get-JournalPath $gameRoot) $journal
        } catch { $problems += (L "無法發布已復原終態：$($_.Exception.Message)" "Could not record that the restore finished: $($_.Exception.Message)") }
        if ($problems.Count -eq 0) { $problems += Clear-TransactionArtifacts $gameRoot $journal }
    }
    return @($problems)
}

# 只有 committed=true 的終態能進此處；清理中斷後重跑不再依賴備份。
function Clear-TransactionArtifacts([string]$gameRoot, $journal) {
    $problems = @()
    $backupParent = Join-Path $gameRoot ($BACKUP_REL -replace '/', '\')
    $backupRoot = Join-Path $backupParent (Get-Prop $journal 'txId')
    if (Test-Path -LiteralPath $backupRoot) {
        try {
            Remove-Item -LiteralPath $backupRoot -Recurse -Force -ErrorAction Stop
        } catch {
            $problems += (L "無法刪除交易備份目錄（$backupRoot）：$($_.Exception.Message)" "Could not delete the transaction backup folder ($backupRoot): $($_.Exception.Message)")
        }
    }
    if ($problems.Count -gt 0) { return @($problems) }
    if ((Test-Path -LiteralPath $backupParent) -and @(Get-ChildItem -LiteralPath $backupParent -Force).Count -eq 0) {
        Remove-Item -LiteralPath $backupParent -Force -ErrorAction SilentlyContinue
    }
    $jp = Get-JournalPath $gameRoot
    if (Test-Path -LiteralPath $jp) {
        try {
            Remove-Item -LiteralPath $jp -Force -ErrorAction Stop
        } catch {
            $problems += (L "無法刪除交易紀錄（$jp）：$($_.Exception.Message)" "Could not delete the transaction record ($jp): $($_.Exception.Message)")
        }
    }
    return @($problems)
}

function Test-PendingTransaction([string]$gameRoot) {
    return (Test-Path -LiteralPath (Get-JournalPath $gameRoot) -PathType Leaf)
}

# 只有 install／uninstall 這種本來就要動檔案的路徑才可以呼叫，而且必須先確認遊戲沒在跑。
function Resume-PendingTransaction([string]$gameRoot) {
    # 狀態目錄本身也可能被換成接點，把 state／journal／備份整組導到遊戲目錄外面。
    # 這裡是動檔案前的第一站，先擋掉再讀任何東西。
    Assert-NoReparsePoint $gameRoot $STATE_REL
    Assert-NoReparsePoint $gameRoot $BACKUP_REL
    Assert-NoReparsePoint $gameRoot $JOURNAL_REL
    $jp = Get-JournalPath $gameRoot
    if (-not (Test-Path -LiteralPath $jp -PathType Leaf)) {
        # 缺少 journal 不能證明備份已無用途；保留未知交易資料，不為清理殘留而冒誤刪風險。
        $orphan = Get-FullTargetPath $gameRoot $BACKUP_REL
        if (Test-Path -LiteralPath $orphan) {
            Write-Warn2 (L "發現沒有 journal 的備份，已保留供人工確認：$orphan" "Found a backup without a journal; kept for manual review: $orphan")
        }
        return
    }
    try { $journal = Read-JsonFile $jp } catch {
        Fail $EXIT_ROLLBACKFAILED (L "偵測到未完成的交易紀錄但無法解析（$jp）。請手動檢查 $gameRoot\$STATE_DIRNAME 後再試。" "Found an unfinished transaction record that cannot be read ($jp). Check $gameRoot\$STATE_DIRNAME manually, then try again.")
    }
    Assert-JournalSafe $gameRoot $journal
    if ([bool](Get-Prop $journal 'committed' $false)) {
        $problems = Clear-TransactionArtifacts $gameRoot $journal
        if ($problems.Count -gt 0) {
            Write-Bad ($problems -join [Environment]::NewLine)
            Fail $EXIT_ROLLBACKFAILED (L "上一次的交易已完成，但清不掉它的暫存檔，請依上述訊息手動處理後再試。" "The previous operation finished, but its temporary files could not be removed. Fix the problem above, then try again.")
        }
        Write-Info (L '已清除上一次已完成交易的暫存備份。' 'Removed the temporary backup of the previous finished operation.')
        return
    }
    Write-Warn2 (L '偵測到上一次未完成的安裝／卸載（可能被中斷或當機），正在復原...' 'The previous install/uninstall did not finish (it may have been interrupted or crashed). Restoring...')
    $problems = Restore-Transaction $gameRoot $journal
    if ($problems.Count -gt 0) {
        Write-Bad ((L '復原失敗：' 'Restore failed:') + [Environment]::NewLine + ($problems -join [Environment]::NewLine))
        Fail $EXIT_ROLLBACKFAILED (L ("無法完成上一次交易的復原或清理。尚需的紀錄與備份保留在" +
            " $gameRoot\$STATE_DIRNAME；依上述訊息排除問題後再執行。") ("Could not finish restoring or cleaning up the previous operation. The records and backups are kept in" +
            " $gameRoot\$STATE_DIRNAME. Fix the problems above, then run this again."))
    }
    Write-Good (L '已復原到上一次動作前的狀態。' 'Restored to the state before the previous operation.')
}

# 互動流程得先把已安裝模組列出來給使用者選，所以在列清單之前就得先復原，
# 否則選到的是復原前的舊清單。順序照舊：鎖 → 行程檢查 → 復原。
function Invoke-Recover([string]$gameRoot) {
    if (-not (Test-PendingTransaction $gameRoot)) { return }
    $lock = Enter-GameRootLock $gameRoot
    try {
        Assert-GameNotRunning $gameRoot
        Resume-PendingTransaction $gameRoot
    } finally {
        Exit-GameRootLock $lock
    }
}

<#
  執行一筆交易。
    Writes  : @( @{ Path=<rel>; Source=<payload 完整路徑>; Sha=<期望 sha> } )
    Deletes : @( <rel> )
    NewState: state.json 物件；$null 代表刪除 state.json（全部卸載）
  順序：備份 → journal(pending) → 刪 → 寫 → state.json → journal(committed) → 清備份
#>
function Invoke-PatchTransaction {
    param(
        [string]$GameRoot,
        [array]$Writes,
        [string[]]$Deletes,
        $NewState
    )
    Assert-GameNotRunning $GameRoot

    $txId = [Guid]::NewGuid().ToString('N').Substring(0, 12)
    $backupRoot = Join-Path (Join-Path $GameRoot ($BACKUP_REL -replace '/', '\')) $txId

    $touched = New-Object System.Collections.Generic.List[string]
    foreach ($w in $Writes) { [void]$touched.Add($w.Path) }
    foreach ($d in $Deletes) { [void]$touched.Add($d) }
    [void]$touched.Add($STATE_REL)

    # 需要新建的目錄（淺到深），journal 先記好，回復時才刪得掉
    $needDirs = New-Object System.Collections.Generic.List[string]
    foreach ($w in $Writes) {
        $segs = @($w.Path -split '/')
        $acc = @()
        for ($i = 0; $i -lt $segs.Count - 1; $i++) {
            $acc += $segs[$i]
            $relDir = ($acc -join '/')
            $fullDir = [System.IO.Path]::GetFullPath((Join-Path $GameRoot ($relDir -replace '/', '\')))
            if (-not (Test-Path -LiteralPath $fullDir) -and -not $needDirs.Contains($relDir)) {
                [void]$needDirs.Add($relDir)
            }
        }
    }
    $stateDirFull = Join-Path $GameRoot $STATE_DIRNAME
    if ($NewState -and -not (Test-Path -LiteralPath $stateDirFull) -and -not $needDirs.Contains($STATE_DIRNAME)) {
        [void]$needDirs.Add($STATE_DIRNAME)
    }
    $sortedDirs = @($needDirs | Sort-Object { ($_ -split '/').Count }, { $_ })
    $after = @{}
    foreach ($write in $Writes) { $after[$write.Path] = $write.Sha }
    $stateBytes = $null
    if ($NewState) {
        $stateBytes = ConvertTo-JsonBytes $NewState
        $after[$STATE_REL] = Get-BytesSha256 $stateBytes
    }

    # --- 備份階段（失敗只需清掉備份目錄，遊戲目錄還沒被動過）
    New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
    $entries = @()
    try {
        foreach ($rel in $touched) {
            $full = Get-FullTargetPath $GameRoot $rel
            Assert-NoReparsePoint $GameRoot $rel
            if (Test-Path -LiteralPath $full -PathType Container) { throw (L "目標變成目錄：$rel" "Target is now a folder: $rel") }
            if (Test-Path -LiteralPath $full -PathType Leaf) {
                $bak = Join-Path $backupRoot ($rel -replace '/', '\')
                $bakDir = Split-Path -Parent $bak
                if (-not (Test-Path -LiteralPath $bakDir)) { New-Item -ItemType Directory -Path $bakDir -Force | Out-Null }
                $before = Get-Sha256 $full
                Publish-File -path $bak -SourcePath $full -ExpectedSha $before
                $entries += [ordered]@{ path = $rel; backup = $rel; backupSha256 = $before; sha256 = $after[$rel] }
            } else {
                $entries += [ordered]@{ path = $rel; backup = $null; backupSha256 = $null; sha256 = $after[$rel] }
            }
        }
    } catch {
        Remove-Item -LiteralPath $backupRoot -Recurse -Force -ErrorAction SilentlyContinue
        Fail $EXIT_INTERNAL (L "建立備份失敗，沒有變更任何檔案：$($_.Exception.Message)" "Could not create a backup; no files were changed: $($_.Exception.Message)")
    }

    $journal = [ordered]@{
        schemaVersion = 1
        txId          = $txId
        startedUtc    = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
        committed     = $false
        entries       = @($entries)
        createdDirs   = @($sortedDirs)
    }
    $journalPath = Get-JournalPath $GameRoot
    Write-JsonFile $journalPath $journal

    # --- 變更階段
    try {
        foreach ($rel in $Deletes) {
            $full = Get-FullTargetPath $GameRoot $rel
            if (Test-Path -LiteralPath $full -PathType Leaf) { Remove-Item -LiteralPath $full -Force }
        }
        foreach ($relDir in $sortedDirs) {
            $fullDir = [System.IO.Path]::GetFullPath((Join-Path $GameRoot ($relDir -replace '/', '\')))
            if (-not (Test-Path -LiteralPath $fullDir)) { New-Item -ItemType Directory -Path $fullDir -Force | Out-Null }
        }
        foreach ($w in $Writes) {
            $full = Get-FullTargetPath $GameRoot $w.Path
            Publish-File -path $full -SourcePath $w.Source -ExpectedSha $w.Sha
        }
        # metadata 最後提交：在這之前中斷都算未安裝
        $statePath = Get-FullTargetPath $GameRoot $STATE_REL
        if ($NewState) {
            Publish-File -path $statePath -bytes $stateBytes -ExpectedSha $after[$STATE_REL]
        } elseif (Test-Path -LiteralPath $statePath -PathType Leaf) {
            Remove-Item -LiteralPath $statePath -Force
        }
        $journal.committed = $true
        Write-JsonFile $journalPath $journal
    } catch {
        $why = $_.Exception.Message
        Write-Bad (L "變更失敗，正在回復到動作前的狀態：$why" "Changes failed; restoring the previous state: $why")
        $problems = Restore-Transaction $GameRoot $journal
        if ($problems.Count -gt 0) {
            Write-Bad ($problems -join [Environment]::NewLine)
            Fail $EXIT_ROLLBACKFAILED (L ("變更失敗，且復原或清理尚未完成。所需紀錄與備份保留在" +
                " $GameRoot\$STATE_DIRNAME；依上述訊息排除問題後再執行。原因：$why") ("Changes failed, and restoring or cleanup did not finish. The records and backups are kept in" +
                " $GameRoot\$STATE_DIRNAME. Fix the problems above, then run this again. Reason: $why"))
        }
        Write-Good (L '已回復到動作前的狀態，沒有留下半套安裝。' 'Restored the previous state; nothing was left half-installed.')
        Fail $EXIT_ROLLEDBACK (L "安裝／卸載失敗（已完整回復）：$why" "Install/uninstall failed (fully restored): $why")
    }

    foreach ($c in (Clear-TransactionArtifacts $GameRoot $journal)) { Write-Warn2 $c }

    foreach ($rel in $Deletes) { Remove-EmptyAncestor $GameRoot $rel }
    if (-not $NewState) {
        $sd = Join-Path $GameRoot $STATE_DIRNAME
        if ((Test-Path -LiteralPath $sd) -and @(Get-ChildItem -LiteralPath $sd -Force).Count -eq 0) {
            Remove-Item -LiteralPath $sd -Force -ErrorAction SilentlyContinue
        }
    }
}

# ---------------------------------------------------------------- 規劃：install
function Resolve-InstallModules($manifest, [string[]]$installed, [string[]]$requested) {
    $all = ConvertTo-Array (Get-Prop $manifest 'modules')
    foreach ($r in $requested) {
        if (-not (Get-Module $manifest $r)) {
            Fail $EXIT_USAGE ((L "未知的模組 id：$r（可用：" "Unknown module id: $r (available: ") + (($all | ForEach-Object { Get-Prop $_ 'id' }) -join ', ') + (L '）' ')'))
        }
    }
    $requested = @($requested | ForEach-Object { Get-Prop (Get-Module $manifest $_) 'id' } | Select-Object -Unique)
    # 加裝：已安裝的保留
    $target = New-Object System.Collections.Generic.List[string]
    foreach ($i in $installed) { if (-not $target.Contains($i)) { [void]$target.Add($i) } }
    foreach ($r in $requested) { if (-not $target.Contains($r)) { [void]$target.Add($r) } }

    # 互斥組：明示選了同組的新變體，就替換掉舊變體
    $groups = @{}
    foreach ($m in $all) {
        $g = Get-Prop $m 'exclusiveGroup'
        if ($g) {
            if (-not $groups.ContainsKey($g)) { $groups[$g] = @() }
            $groups[$g] += (Get-Prop $m 'id')
        }
    }
    $replaced = @()
    foreach ($g in $groups.Keys) {
        $inGroup = @($groups[$g])
        $req = @($requested | Where-Object { $inGroup -contains $_ })
        if ($req.Count -gt 1) {
            Fail $EXIT_USAGE (L "互斥組「$g」一次只能選一個變體，但收到：$($req -join ', ')" "Only one variant of group '$g' can be chosen at a time, but got: $($req -join ', ')")
        }
        if ($req.Count -eq 1) {
            foreach ($other in $inGroup) {
                if ($other -ne $req[0] -and $target.Contains($other)) {
                    [void]$target.Remove($other)
                    $replaced += $other
                }
            }
        }
    }

    # 依賴閉包
    $changed = $true
    while ($changed) {
        $changed = $false
        foreach ($id in @($target)) {
            foreach ($req in (ConvertTo-Array (Get-Prop (Get-Module $manifest $id) 'requires'))) {
                $req = Get-Prop (Get-Module $manifest $req) 'id'
                if (-not $target.Contains($req)) { [void]$target.Add($req); $changed = $true }
            }
        }
    }
    # 依 manifest 順序輸出，確保 core 之類的基礎模組排前面
    $ordered = @($all | ForEach-Object { Get-Prop $_ 'id' } | Where-Object { $target.Contains($_) })
    return @{ Modules = $ordered; Replaced = $replaced }
}

function Get-InstallPlan($manifest, [string]$packageDir, [string]$gameRoot, [string[]]$targetIds, $state, $legacy) {
    $owned = Get-StateOwnedFiles $state
    $final = [ordered]@{}
    foreach ($id in $targetIds) {
        $mod = Get-Module $manifest $id
        foreach ($f in (ConvertTo-Array (Get-Prop $mod 'files'))) {
            $p = Get-Prop $f 'path'
            if ($final.Contains($p)) {
                Fail $EXIT_PACKAGE (L "選定的模組中有兩個都擁有 $p（$($final[$p].ModuleId) 與 $id），無法安裝。" "Two chosen modules both own $p ($($final[$p].ModuleId) and $id); cannot install.")
            }
            $final[$p] = @{
                Sha      = (Get-Prop $f 'sha256').ToLowerInvariant()
                Source   = (Get-FullTargetPath $packageDir (Get-Prop $f 'source'))
                ModuleId = $id
            }
        }
    }

    $writes = @()
    $conflicts = @()
    $repairs = @()
    foreach ($p in $final.Keys) {
        Assert-SafeRelPath $p 'manifest.json'
        Assert-NoReparsePoint $gameRoot $p
        $full = Get-FullTargetPath $gameRoot $p
        if (Test-Path -LiteralPath $full -PathType Container) {
            $conflicts += (L "$p 位置上是一個資料夾，無法安裝。" "$p is a folder, so it cannot be installed.")
            continue
        }
        if (Test-Path -LiteralPath $full -PathType Leaf) {
            $cur = Get-Sha256 $full
            if ($cur -eq $final[$p].Sha) { continue }  # 內容已經正確，不需要寫
            if ($owned.ContainsKey($p)) {
                if ($owned[$p].Sha -ne $cur) { $repairs += $p }
            } elseif ($legacy.AdoptedPaths.ContainsKey($p)) {
                # 已精確辨識的舊版包，允許接管
            } else {
                $conflicts += (L "$p 已存在且不是本安裝器管理的檔案（可能是其它 patch 或手動放置）。" "$p already exists and is not managed by this installer (it may be from another patch or placed by hand).")
                continue
            }
        }
        $writes += @{ Path = $p; Source = $final[$p].Source; Sha = $final[$p].Sha }
    }

    # 需要清掉的：不再屬於選定模組的自家檔案 ＋ 被接管的舊版包殘檔
    $deleteCandidates = New-Object System.Collections.Generic.List[string]
    foreach ($p in $owned.Keys) { if (-not $final.Contains($p) -and -not $deleteCandidates.Contains($p)) { [void]$deleteCandidates.Add($p) } }
    foreach ($p in $legacy.AdoptedPaths.Keys) { if (-not $final.Contains($p) -and -not $deleteCandidates.Contains($p)) { [void]$deleteCandidates.Add($p) } }

    $deletes = @()
    $tampered = @()
    foreach ($p in $deleteCandidates) {
        Assert-SafeRelPath $p 'state.json'
        Assert-NoReparsePoint $gameRoot $p
        $full = Get-FullTargetPath $gameRoot $p
        if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { continue }
        $cur = Get-Sha256 $full
        $expected = $null
        if ($owned.ContainsKey($p)) { $expected = $owned[$p].Sha }
        if ($legacy.AdoptedPaths.ContainsKey($p)) { $expected = $cur }  # legacy 已逐檔驗過指紋
        if ($expected -and $cur -ne $expected) { $tampered += $p; continue }
        $deletes += $p
    }

    # 未被完整辨識的舊版 patch：只要它的已知路徑上有陌生內容就擋下
    foreach ($p in $legacy.KnownPaths.Keys) {
        if ($legacy.AdoptedPaths.ContainsKey($p) -or $owned.ContainsKey($p) -or $final.Contains($p)) { continue }
        $full = Get-FullTargetPath $gameRoot $p
        if (Test-Path -LiteralPath $full -PathType Leaf) {
            $conflicts += (L "$p 是舊版 patch 的檔案但整組指紋對不上（版本不明），不敢動它。" "$p belongs to an old patch version, but the full set does not match any known version; it will not be touched.")
        }
    }

    return @{
        Final     = $final
        Writes    = $writes
        Deletes   = $deletes
        Conflicts = $conflicts
        Tampered  = $tampered
        Repairs   = $repairs
    }
}

function New-StateModuleEntries($manifest, [string[]]$targetIds) {
    $entries = @()
    foreach ($id in $targetIds) {
        $mod = Get-Module $manifest $id
        $files = @()
        foreach ($f in (ConvertTo-Array (Get-Prop $mod 'files'))) {
            $files += [ordered]@{ path = (Get-Prop $f 'path'); sha256 = (Get-Prop $f 'sha256').ToLowerInvariant() }
        }
        $entries += [ordered]@{
            id      = $id
            name    = (Get-Prop $mod 'name' $id)
            version = (Get-Prop $mod 'version' '')
            files   = @($files)
        }
    }
    return $entries
}

# ---------------------------------------------------------------- 動作：install
# -All 的互斥組預設：該組已經裝了某個變體就沿用它。install 的契約是「加裝」，
# 只有使用者明示選了新變體才替換——否則 lowmem 使用者跑一次 -All 就被換成 standard。
function Get-AllInstallTargets($manifest, [string[]]$installed) {
    $mods = ConvertTo-Array (Get-Prop $manifest 'modules')
    $chosen = [ordered]@{}
    foreach ($m in $mods) {
        $g = Get-Prop $m 'exclusiveGroup'
        if ($g -and ($installed -contains (Get-Prop $m 'id')) -and -not $chosen.Contains($g)) {
            $chosen[$g] = (Get-Prop $m 'id')
        }
    }
    $targets = @()
    foreach ($m in $mods) {
        $id = Get-Prop $m 'id'
        $g = Get-Prop $m 'exclusiveGroup'
        if (-not $g) { $targets += $id; continue }
        # 這組還沒裝過任何變體，才取 manifest 裡的第一個當預設
        if (-not $chosen.Contains($g)) { $chosen[$g] = $id }
    }
    foreach ($g in $chosen.Keys) { $targets += $chosen[$g] }
    return @($targets)
}

function Invoke-Install {
    param($Manifest, [string]$PackageDir, [string]$GameRoot, [string[]]$Requested, [bool]$RequestAll, [bool]$Interactive)

    if (-not $RequestAll -and @($Requested).Count -eq 0) {
        Fail $EXIT_USAGE (L '沒有指定要安裝的模組。請用 -Modules id[,id...] 或 -All。' 'No modules to install were given. Use -Modules id[,id...] or -All.')
    }

    # 順序本身就是契約：先拿目錄鎖 → 確認遊戲沒在跑 → 復原未完成交易 → 最後才讀 state 規劃。
    # 顛倒過來會拿到復原前的舊 state，或在遊戲執行中覆蓋正在用的 class。
    $lock = Enter-GameRootLock $GameRoot
    try {
        Assert-GameNotRunning $GameRoot
        Resume-PendingTransaction $GameRoot

        $jarPath = Join-Path $GameRoot 'projectzomboid.jar'
        if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
            Fail $EXIT_GAMEDIR (L "遊戲目錄裡找不到 projectzomboid.jar：$GameRoot" "projectzomboid.jar was not found in the game folder: $GameRoot")
        }
        $jarSha = Get-Sha256 $jarPath
        $wantJar = (Get-Prop $Manifest 'jarSha256').ToLowerInvariant()
        if ($jarSha -ne $wantJar) {
            Fail $EXIT_JAR ((L "遊戲版本不符——本安裝包只適用 $(Get-Prop $Manifest 'gameVersion' '(未標示)')。" "Game version mismatch. This package only works with $(Get-Prop $Manifest 'gameVersion' '(not specified)').") +
                [Environment]::NewLine + (L "  期望 jar SHA256：$wantJar" "  Expected jar SHA256: $wantJar") +
                [Environment]::NewLine + (L "  實際 jar SHA256：$jarSha" "  Actual jar SHA256:   $jarSha"))
        }

        # 復原完才讀，state 與 legacy 都必須是「現在」的磁碟狀態
        $state = Read-State $GameRoot
        $legacy = Find-LegacyPackages $Manifest $GameRoot

        # 已安裝集合 ＝ state 紀錄 ＋ 整組精確辨識出的舊版包對應的模組。
        # 少了後者，只加裝 profiler 會把舊版的客戶端修復當成「不再需要」而刪掉。
        $installed = @(Get-StateModuleIds $state)
        foreach ($lp in $legacy.Packages) {
            foreach ($lm in (ConvertTo-Array (Get-Prop $lp 'modules'))) {
                if ((Get-Module $Manifest $lm) -and $installed -notcontains $lm) { $installed += $lm }
            }
        }

        if ($RequestAll) { $Requested = Get-AllInstallTargets $Manifest $installed }
        if (@($Requested).Count -eq 0) {
            Fail $EXIT_USAGE (L '沒有指定要安裝的模組。請用 -Modules id[,id...] 或 -All。' 'No modules to install were given. Use -Modules id[,id...] or -All.')
        }

        if ($state -and (Get-Prop $state 'jarSha256')) {
            if ((Get-Prop $state 'jarSha256').ToLowerInvariant() -ne $jarSha) {
                Write-Warn2 (L '偵測到既有安裝是給不同遊戲版本用的，將整組更新為目前版本。' 'The existing install was made for a different game version; everything will be updated for the current version.')
            }
        }

        $resolved = Resolve-InstallModules $Manifest $installed $Requested
        $targetIds = @($resolved.Modules)
        Assert-PayloadIntact $Manifest $PackageDir $targetIds

        $plan = Get-InstallPlan $Manifest $PackageDir $GameRoot $targetIds $state $legacy

        $sep = L '、' ', '
        Write-Head (L '安裝計畫' 'Install plan')
        Write-Info (L "  遊戲目錄：$GameRoot" "  Game folder: $GameRoot")
        Write-Info (L "  安裝包版本：$(Get-Prop $Manifest 'packageVersion' '(未標示)')（遊戲 $(Get-Prop $Manifest 'gameVersion' '?')）" "  Package version: $(Get-Prop $Manifest 'packageVersion' '(not specified)') (game $(Get-Prop $Manifest 'gameVersion' '?'))")
        Write-Info ((L '  最終模組：' '  Modules after install: ') + (($targetIds | ForEach-Object { Get-ModuleLabel $Manifest $_ }) -join $sep))
        if ($resolved.Replaced.Count -gt 0) {
            Write-Info ((L '  取代變體：' '  Replacing: ') + (($resolved.Replaced | ForEach-Object { Get-ModuleLabel $Manifest $_ }) -join $sep))
        }
        if ($legacy.Packages.Count -gt 0) {
            Write-Info ((L '  接管舊版包：' '  Taking over old package: ') + (($legacy.Packages | ForEach-Object { Get-Prop $_ 'id' }) -join $sep))
        }
        Write-Info (L "  寫入 $($plan.Writes.Count) 個檔案、移除 $($plan.Deletes.Count) 個檔案" "  Writing $($plan.Writes.Count) file(s), removing $($plan.Deletes.Count) file(s)")
        foreach ($r in $plan.Repairs) { Write-Warn2 (L "自家檔案 $r 內容與紀錄不符，將重新寫入修復。" "Installed file $r does not match the record; it will be rewritten to repair it.") }

        if ($plan.Conflicts.Count -gt 0) {
            Write-Head (L '偵測到衝突，已中止（沒有任何檔案被更動）' 'Conflict found; stopped (no files were changed)')
            foreach ($c in $plan.Conflicts) { Write-Bad $c }
            Fail $EXIT_CONFLICT (L '遊戲目錄裡有非本安裝器管理的 loose class 檔。若那是舊版 Minidoracat patch，請先用舊版的 uninstall.bat 移除；其它來源的檔案請自行確認後移除。' 'The game folder has loose class files not managed by this installer. If they are from an old Minidoracat patch, remove them first with that version''s uninstall.bat; files from other sources must be checked and removed by you.')
        }
        if ($plan.Tampered.Count -gt 0) {
            Write-Head (L '自家檔案被變造，已中止（沒有任何檔案被更動）' 'Installed files were modified; stopped (no files were changed)')
            foreach ($t in $plan.Tampered) { Write-Bad (L "$t 內容與安裝紀錄不符，不敢自動刪除。" "$t does not match the install record, so it will not be deleted automatically.") }
            Fail $EXIT_TAMPERED (L '請手動確認上述檔案後再重試。' 'Check the files above manually, then try again.')
        }

        # 「不需要變更」必須連 state.json 都已經是最終內容才算數。只比對 class 檔的話，
        # 接管內容剛好相同的舊版包、或紀錄裡的指紋／版本過期，都會被當成完工收工，
        # 留下沒有安裝紀錄的遊戲目錄——之後卸載會把它當成無主舊版 patch 而拒絕處理。
        $newState = New-StateObject $jarSha (Get-Prop $Manifest 'packageVersion' '') (New-StateModuleEntries $Manifest $targetIds)
        $statePath = Get-FullTargetPath $GameRoot $STATE_REL
        $curStateJson = ''
        if (Test-Path -LiteralPath $statePath -PathType Leaf) { $curStateJson = [System.IO.File]::ReadAllText($statePath) }
        if ($plan.Writes.Count -eq 0 -and $plan.Deletes.Count -eq 0 -and
            (ConvertTo-Json -InputObject $newState -Depth 12) -eq $curStateJson) {
            Write-Good (L '目前狀態已經是目標狀態，不需要任何變更。' 'Already up to date; nothing to change.')
            return
        }

        if ($Interactive) {
            $ans = Read-Host (L '確定要安裝嗎？輸入 Y 按 Enter 開始（輸入 N 取消）' 'Install now? Type Y and press Enter to start (N to cancel)')
            if ($ans -notmatch '^[Yy]') { Write-Info (L '已取消。' 'Cancelled.'); return }
        }

        Invoke-PatchTransaction -GameRoot $GameRoot -Writes $plan.Writes -Deletes $plan.Deletes -NewState $newState
        Write-Good (L "完成：已安裝 $($targetIds.Count) 個模組。直接啟動遊戲即可。" "Done: $($targetIds.Count) module(s) installed. You can start the game now.")
    } finally {
        Exit-GameRootLock $lock
    }
}

# ---------------------------------------------------------------- 動作：uninstall
# 沒有 state.json 不代表目錄是乾淨的：舊版（v3.x 及更早）patch 就是一堆沒有紀錄的 loose class。
# 整組指紋吻合的舊版包交給呼叫端移除；認不出來的殘檔必須明確拒絕並指回舊版 uninstaller，
# 不能回一句「沒東西要卸載」讓使用者以為卸乾淨了。
function Assert-NoUnknownLegacy($manifest, [string]$gameRoot) {
    $hits = @()
    $legacy = @{ Packages = @(); AdoptedPaths = @{}; KnownPaths = @{} }
    if ($manifest) {
        $legacy = Find-LegacyPackages $manifest $gameRoot
        foreach ($p in $legacy.KnownPaths.Keys) {
            if ($legacy.AdoptedPaths.ContainsKey($p)) { continue }
            if (Test-Path -LiteralPath (Get-FullTargetPath $gameRoot $p) -PathType Leaf) { $hits += $p }
        }
    }
    # 沒有 manifest（例如不是從安裝包資料夾執行）時，至少看自家 namespace
    $mdcDir = Join-Path ([System.IO.Path]::GetFullPath($gameRoot)) 'zombie\mdc'
    if (Test-Path -LiteralPath $mdcDir -PathType Container) {
        $stray = @(Get-ChildItem -LiteralPath $mdcDir -Filter '*.class' -Force -ErrorAction SilentlyContinue |
            Where-Object { -not $legacy.AdoptedPaths.ContainsKey("zombie/mdc/$($_.Name)") })
        if ($stray.Count -gt 0) {
            $hits += (L 'zombie/mdc/ 底下還有 .class（自家 namespace，但沒有安裝紀錄）' 'zombie/mdc/ still has .class files (our own namespace, but no install record)')
        }
    }
    if ($hits.Count -eq 0) { return $legacy }
    Write-Head (L '偵測到不是這個安裝器裝上去的 Minidoracat 檔案' 'Found Minidoracat files that were not installed by this installer')
    foreach ($h in ($hits | Sort-Object -Unique)) { Write-Bad $h }
    Fail $EXIT_CONFLICT (L '這些檔案沒有 state.json 可對照，也對不上任何已知的舊版包（版本不明或內容被改過），本安裝器不會亂刪。請改用當初那個版本附的 uninstall.bat 移除，或自行確認後手動刪除。' 'These files have no state.json record and do not match any known old package (unknown version or modified content), so this installer will not delete them. Remove them with the uninstall.bat that came with that version, or check and delete them by hand.')
}

function Invoke-Uninstall {
    param($Manifest, [string]$GameRoot, [string[]]$Requested, [bool]$RemoveAll, [bool]$Interactive)

    # 與 install 同一套順序：鎖 → 行程檢查 → 復原 → 才讀 state
    $lock = Enter-GameRootLock $GameRoot
    try {
        Assert-GameNotRunning $GameRoot
        Resume-PendingTransaction $GameRoot

        $state = Read-State $GameRoot
        if (-not $state -or (Get-StateModuleIds $state).Count -eq 0) {
            $legacy = Assert-NoUnknownLegacy $Manifest $GameRoot
            if ($legacy.Packages.Count -eq 0) {
                Write-Info (L '目前沒有偵測到本安裝器管理的模組，沒有東西需要卸載。' 'No modules managed by this installer were found; nothing to remove.')
                return
            }
            # 舊版包沒有模組可挑，只能整組移除
            if (-not $RemoveAll -and -not $Interactive) {
                Fail $EXIT_USAGE (L '偵測到舊版包，只能整組移除：請用 -All。' 'An old package was found; it can only be removed as a whole. Use -All.')
            }
            $deletes = @($legacy.AdoptedPaths.Keys | Sort-Object)
            foreach ($p in $deletes) { Assert-NoReparsePoint $GameRoot $p }
            Write-Head (L '卸載計畫' 'Removal plan')
            Write-Info (L "  遊戲目錄：$GameRoot" "  Game folder: $GameRoot")
            Write-Info ((L '  移除舊版包：' '  Removing old package: ') + (($legacy.Packages | ForEach-Object { Get-Prop $_ 'id' }) -join (L '、' ', ')))
            Write-Info (L "  刪除 $($deletes.Count) 個檔案" "  Deleting $($deletes.Count) file(s)")
            if ($Interactive) {
                $ans = Read-Host (L '確定要移除嗎？輸入 Y 按 Enter 開始（輸入 N 取消）' 'Remove now? Type Y and press Enter to start (N to cancel)')
                if ($ans -notmatch '^[Yy]') { Write-Info (L '已取消。' 'Cancelled.'); return }
            }
            Invoke-PatchTransaction -GameRoot $GameRoot -Writes @() -Deletes $deletes -NewState $null
            Write-Good (L '完成：已移除舊版包，遊戲回到原版狀態。' 'Done: the old package was removed; the game is back to vanilla.')
            return
        }

        $installed = @(Get-StateModuleIds $state)
        if ($RemoveAll) {
            $Requested = @($installed)
        } elseif (@($Requested).Count -eq 0) {
            Fail $EXIT_USAGE (L '沒有指定要卸載的模組。請用 -Modules id[,id...] 或 -All。' 'No modules to remove were given. Use -Modules id[,id...] or -All.')
        } else {
            $unknown = @($Requested | Where-Object { $installed -notcontains $_ })
            if ($unknown.Count -eq @($Requested).Count) {
                Fail $EXIT_USAGE (L "指定的模組都沒有安裝：$($unknown -join ', ')（已安裝：$($installed -join ', ')）" "None of the given modules are installed: $($unknown -join ', ') (installed: $($installed -join ', '))")
            }
            foreach ($u in $unknown) { Write-Warn2 (L "模組 $u 未安裝，略過。" "Module $u is not installed; skipped.") }
        }

        $keep = New-Object System.Collections.Generic.List[string]
        foreach ($id in $installed) { if ($Requested -notcontains $id) { [void]$keep.Add($id) } }

        # 仍被保留模組需要的依賴一律留下（例如 core）
        $stateModules = @{}
        foreach ($m in (ConvertTo-Array (Get-Prop $state 'modules'))) { $stateModules[(Get-Prop $m 'id')] = $m }
        $retained = @()
        $changed = $true
        while ($changed) {
            $changed = $false
            foreach ($id in @($keep)) {
                foreach ($req in (ConvertTo-Array (Get-Prop $stateModules[$id] 'requires'))) {
                    if ($installed -contains $req -and -not $keep.Contains($req)) {
                        [void]$keep.Add($req); $retained += $req; $changed = $true
                    }
                }
            }
        }
        # state.json 不一定記 requires（契約只要求 id/name/version/files），
        # 對已知的固定 module id 補上依賴關係，避免拔掉 core 讓其它模組變成半殘。
        if ($keep.Count -gt 0 -and -not $keep.Contains('core') -and $installed -contains 'core') {
            $needsCore = @($keep | Where-Object { $_ -ne 'core' })
            if ($needsCore.Count -gt 0) {
                [void]$keep.Add('core'); $retained += 'core'
            }
        }
        foreach ($r in ($retained | Sort-Object -Unique)) {
            Write-Warn2 (L "模組 $r 仍被其它保留的模組需要，因此保留不移除。" "Module $r is still needed by other modules being kept, so it stays installed.")
        }

        $keepPaths = @{}
        foreach ($id in $keep) {
            foreach ($f in (ConvertTo-Array (Get-Prop $stateModules[$id] 'files'))) { $keepPaths[(Get-Prop $f 'path')] = $true }
        }

        $owned = Get-StateOwnedFiles $state
        $deletes = @()
        $tampered = @()
        $missing = @()
        foreach ($p in $owned.Keys) {
            if ($keepPaths.ContainsKey($p)) { continue }
            Assert-SafeRelPath $p 'state.json'
            Assert-NoReparsePoint $GameRoot $p
            $full = Get-FullTargetPath $GameRoot $p
            if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { $missing += $p; continue }
            if ((Get-Sha256 $full) -ne $owned[$p].Sha) { $tampered += $p; continue }
            $deletes += $p
        }

        $sep = L '、' ', '
        Write-Head (L '卸載計畫' 'Removal plan')
        Write-Info (L "  遊戲目錄：$GameRoot" "  Game folder: $GameRoot")
        $removing = @($installed | Where-Object { -not $keep.Contains($_) })
        Write-Info ((L '  移除模組：' '  Removing modules: ') + (($removing | ForEach-Object { "$($_)" }) -join $sep))
        if ($keep.Count -gt 0) { Write-Info ((L '  保留模組：' '  Keeping modules: ') + ($keep -join $sep)) }
        Write-Info (L "  刪除 $($deletes.Count) 個檔案" "  Deleting $($deletes.Count) file(s)")
        foreach ($m in $missing) { Write-Warn2 (L "$m 已經不在遊戲目錄（可能被手動刪除），略過。" "$m is no longer in the game folder (maybe deleted by hand); skipped.") }

        if ($tampered.Count -gt 0) {
            Write-Head (L '自家檔案被變造，已中止（沒有任何檔案被更動）' 'Installed files were modified; stopped (no files were changed)')
            foreach ($t in $tampered) { Write-Bad (L "$t 內容與安裝紀錄不符，不刪除。" "$t does not match the install record; not deleted.") }
            Fail $EXIT_TAMPERED (L '請確認上述檔案是否為你自行替換的版本；確認可刪除後再手動移除，或重新安裝一次讓紀錄同步。' 'Check whether the files above are versions you replaced yourself. Delete them by hand once you are sure, or install again to bring the record back in sync.')
        }

        if ($Interactive) {
            $ans = Read-Host (L '確定要移除嗎？輸入 Y 按 Enter 開始（輸入 N 取消）' 'Remove now? Type Y and press Enter to start (N to cancel)')
            if ($ans -notmatch '^[Yy]') { Write-Info (L '已取消。' 'Cancelled.'); return }
        }

        $newState = $null
        if ($keep.Count -gt 0) {
            $entries = @()
            foreach ($id in $installed) {
                if ($keep.Contains($id)) { $entries += $stateModules[$id] }
            }
            $newState = New-StateObject (Get-Prop $state 'jarSha256' '') (Get-Prop $state 'packageVersion' '') $entries
        }
        Invoke-PatchTransaction -GameRoot $GameRoot -Writes @() -Deletes $deletes -NewState $newState
        if ($keep.Count -gt 0) {
            Write-Good (L "完成：已移除 $($removing.Count) 個模組，保留 $($keep.Count) 個。" "Done: removed $($removing.Count) module(s), kept $($keep.Count).")
        } else {
            Write-Good (L '完成：已移除所有 Minidoracat 模組，遊戲回到原版狀態。' 'Done: all Minidoracat modules removed; the game is back to vanilla.')
        }
    } finally {
        Exit-GameRootLock $lock
    }
}

# ---------------------------------------------------------------- 動作：status
function Invoke-Status {
    param($Manifest, [string]$GameRoot)

    Write-Head (L 'Minidoracat client patch 狀態' 'Minidoracat client patch status')
    Write-Info (L "  遊戲目錄：$GameRoot" "  Game folder: $GameRoot")
    if (Test-PendingTransaction $GameRoot) {
        # status 是唯讀的：只報告，不復原（復原要動檔案，得先確認遊戲已關閉）
        Write-Warn2 (L '偵測到未完成的安裝／卸載交易。status 不會修改任何檔案；請關閉遊戲後執行安裝或卸載，屆時會自動先復原。' 'An unfinished install/uninstall was detected. Status does not change any files; close the game and run install or uninstall, which will restore it automatically first.')
    }

    $jarPath = Join-Path $GameRoot 'projectzomboid.jar'
    $jarSha = $null
    if (Test-Path -LiteralPath $jarPath -PathType Leaf) {
        $jarSha = Get-Sha256 $jarPath
        Write-Info (L "  遊戲 jar SHA256：$jarSha" "  Game jar SHA256: $jarSha")
    } else {
        Write-Warn2 (L '找不到 projectzomboid.jar。' 'projectzomboid.jar not found.')
    }

    $state = Read-State $GameRoot
    if (-not $state -or (Get-StateModuleIds $state).Count -eq 0) {
        Write-Info (L '  安裝狀態：未安裝任何模組' '  Install status: no modules installed')
    } else {
        Write-Info (L "  安裝包版本：$(Get-Prop $state 'packageVersion' '(未標示)')" "  Package version: $(Get-Prop $state 'packageVersion' '(not specified)')")
        if ($jarSha -and (Get-Prop $state 'jarSha256') -and (Get-Prop $state 'jarSha256').ToLowerInvariant() -ne $jarSha) {
            Write-Warn2 (L '既有安裝對應的遊戲版本與目前的 jar 不同（遊戲可能更新過），請重新安裝。' 'The existing install was made for a different game version (the game may have updated). Please install again.')
        }
        foreach ($m in (ConvertTo-Array (Get-Prop $state 'modules'))) {
            $bad = 0; $gone = 0; $total = 0
            foreach ($f in (ConvertTo-Array (Get-Prop $m 'files'))) {
                $total++
                $full = Get-FullTargetPath $GameRoot (Get-Prop $f 'path')
                if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { $gone++; continue }
                if ((Get-Sha256 $full) -ne (Get-Prop $f 'sha256').ToLowerInvariant()) { $bad++ }
            }
            $mark = if ($gone -eq 0 -and $bad -eq 0) { L '正常' 'OK' } else { L "異常（缺 $gone、變造 $bad）" "PROBLEM ($gone missing, $bad modified)" }
            $ver = Get-Prop $m 'version' ''
            Write-Info (L "    [已安裝] $(Get-Prop $m 'name' (Get-Prop $m 'id')) ($(Get-Prop $m 'id')) $ver — $total 個檔案，$mark" "    [Installed] $(Get-ModuleName $m (Get-Prop $m 'id') $Manifest) ($(Get-Prop $m 'id')) $ver - $total file(s), $mark")
        }
    }

    if ($Manifest) {
        $installed = @(Get-StateModuleIds $state)
        Write-Info (L "  安裝包可提供：$(Get-Prop $Manifest 'packageVersion' '(未標示)')（遊戲 $(Get-Prop $Manifest 'gameVersion' '?')）" "  This package offers: $(Get-Prop $Manifest 'packageVersion' '(not specified)') (game $(Get-Prop $Manifest 'gameVersion' '?'))")
        foreach ($m in (ConvertTo-Array (Get-Prop $Manifest 'modules'))) {
            $id = Get-Prop $m 'id'
            $tag = if ($installed -contains $id) { L '已安裝' 'Installed' } else { L '可安裝' 'Available' }
            $grp = Get-Prop $m 'exclusiveGroup'
            $grpTxt = if ($grp) { L "（互斥組 $grp）" " (group $grp, pick one)" } else { '' }
            Write-Info "    [$tag] $(Get-ModuleName $m $id) ($id) $(Get-Prop $m 'version' '')$grpTxt"
        }
        if ($jarSha) {
            if ($jarSha -ne (Get-Prop $Manifest 'jarSha256' '').ToLowerInvariant()) {
                Write-Warn2 (L '目前遊戲版本與這個安裝包不符，無法安裝（請取得對應版本的安裝包）。' 'The current game version does not match this package, so it cannot be installed (get the package for your game version).')
            }
            $legacy = Find-LegacyPackages $Manifest $GameRoot
            foreach ($lp in $legacy.Packages) {
                Write-Info (L "  偵測到舊版包（可自動接管）：$(Get-Prop $lp 'id') -> $((ConvertTo-Array (Get-Prop $lp 'modules')) -join '、')" "  Old package found (can be taken over automatically): $(Get-Prop $lp 'id') -> $((ConvertTo-Array (Get-Prop $lp 'modules')) -join ', ')")
            }
        }
    } else {
        Write-Info (L '  （這次執行沒有找到 manifest.json，只顯示已安裝狀態；安裝請從解壓後的安裝包資料夾執行。）' '  (No manifest.json found this time, so only the installed state is shown. To install, run this from the extracted package folder.)')
    }
}

# ---------------------------------------------------------------- 互動選單
function Read-Choice([string]$prompt, [string[]]$valid, [string]$default = '') {
    while ($true) {
        $ans = (Read-Host $prompt).Trim()
        if ($ans -eq '' -and $default) { return $default }
        if ($valid -contains $ans) { return $ans }
        Write-Bad (L "請輸入：$($valid -join ' / ')" "Please enter: $($valid -join ' / ')")
    }
}

function Select-InstallModules($Manifest) {
    $ids = @((ConvertTo-Array (Get-Prop $Manifest 'modules')) | ForEach-Object { Get-Prop $_ 'id' })
    $hasProfiler = $ids -contains 'profiler'
    $variants = @($ids | Where-Object { $_ -like 'client-fixes-*' })

    Write-Head (L '要安裝什麼？' 'What do you want to install?')
    $opts = @('0')
    if ($variants.Count -gt 0) { Write-Info (L '  [1] 修復隱形、自建房間看不到東西（一般玩家選這個）' '  [1] Client fixes: invisible entities, objects vanishing in player-built rooms (choose this)'); $opts += '1' }
    if ($hasProfiler) { Write-Info (L '  [2] 開發者工具 DevProfiler（一般玩家不用裝）' '  [2] DevProfiler developer tool (not needed for players)'); $opts += '2' }
    if ($hasProfiler -and $variants.Count -gt 0) { Write-Info (L '  [3] 兩個都裝（開發者用）' '  [3] Both (developers)'); $opts += '3' }
    Write-Info (L '  [0] 返回' '  [0] Back')
    $c = Read-Choice (L '請輸入數字後按 Enter' 'Type a number and press Enter') $opts
    if ($c -eq '0') { return @() }

    $picked = @()
    if ($c -eq '2' -or $c -eq '3') { $picked += 'profiler' }
    if ($c -eq '1' -or $c -eq '3') {
        $ramGb = 0
        try { $ramGb = [int][Math]::Round((Get-CimInstance Win32_ComputerSystem -ErrorAction Stop).TotalPhysicalMemory / 1GB) } catch { }
        Write-Head (L '要用哪個版本？' 'Which version?')
        $vopts = @()
        $recommend = ''
        $i = 0
        foreach ($v in $variants) {
            $i++
            $text = if ($v -eq 'client-fixes-standard') { L '標準版（電腦記憶體 32GB 以上）' 'Standard (32 GB RAM or more)' } elseif ($v -eq 'client-fixes-lowmem') { L '省記憶體版（電腦記憶體 32GB 以下）' 'Low-memory (less than 32 GB RAM)' } else { Get-ModuleLabel $Manifest $v }
            Write-Info "  [$i] $text"
            $vopts += "$i"
            if (($ramGb -ge 32 -and $v -eq 'client-fixes-standard') -or ($ramGb -gt 0 -and $ramGb -lt 32 -and $v -eq 'client-fixes-lowmem')) { $recommend = "$i" }
        }
        if ($recommend) {
            Write-Info (L "  你的電腦記憶體約 $ramGb GB，建議選 [$recommend]，直接按 Enter 就會選它。" "  Your PC has about $ramGb GB RAM; option [$recommend] is recommended - just press Enter.")
        } else {
            Write-Info (L '  不知道記憶體多大就選省記憶體版。' '  If unsure about your RAM, choose low-memory.')
        }
        $vc = Read-Choice (L '請輸入數字後按 Enter' 'Type a number and press Enter') $vopts $recommend
        $picked += $variants[[int]$vc - 1]
    }
    return $picked
}

function Select-UninstallModules([string[]]$installed) {
    Write-Head (L '要移除哪些修補？' 'Which patches do you want to remove?')
    Write-Info (L '  遊戲要更新前，輸入 A 按 Enter 全部移除即可。' '  Before a game update, just type A and press Enter to remove everything.')
    $names = @{ 'core' = (L '核心元件' 'Core components'); 'client-fixes-standard' = (L '客戶端修復（標準版）' 'Client fixes (standard)'); 'client-fixes-lowmem' = (L '客戶端修復（省記憶體版）' 'Client fixes (low-memory)'); 'profiler' = (L '開發者工具 DevProfiler' 'DevProfiler developer tool') }
    $i = 0
    foreach ($id in $installed) { $i++; $label = if ($names.ContainsKey($id)) { $names[$id] } else { $id }; Write-Info "  [$i] $label" }
    Write-Info (L '  [A] 全部移除（遊戲更新前選這個）' '  [A] Remove everything (do this before a game update)')
    Write-Info (L '  [0] 返回' '  [0] Back')
    while ($true) {
        $ans = (Read-Host (L '請輸入 A 或編號後按 Enter' 'Type A or a number and press Enter')).Trim()
        if ($ans -eq '0') { return @() }
        if ($ans -match '^[Aa]$') { return @($installed) }
        $picked = @()
        $ok = $true
        foreach ($tok in ($ans -split ',')) {
            $t = $tok.Trim()
            if ($t -notmatch '^\d+$' -or [int]$t -lt 1 -or [int]$t -gt $installed.Count) { $ok = $false; break }
            $picked += $installed[[int]$t - 1]
        }
        if ($ok -and $picked.Count -gt 0) { return @($picked | Sort-Object -Unique) }
        Write-Bad (L '輸入格式不對，請重新輸入。' 'Invalid input, please try again.')
    }
}

function Invoke-Menu {
    param($Manifest, [string]$PackageDir, [string]$GameRoot)
    while ($true) {
        Write-Head (L 'Minidoracat Project Zomboid 客戶端模組管理' 'Minidoracat Project Zomboid client patch manager')
        Write-Info (L "  遊戲目錄：$GameRoot" "  Game folder: $GameRoot")
        if (Test-PendingTransaction $GameRoot) {
            # 光是開選單不會動任何檔案；復原一律等到真的要安裝／卸載時才做
            Write-Warn2 (L '偵測到未完成的安裝／卸載交易，執行安裝或卸載時會先自動復原（請先關閉遊戲）。' 'An unfinished install/uninstall was detected; it will be restored automatically the next time you install or remove (close the game first).')
        }
        Write-Info (L '  [1] 安裝或更新修補' '  [1] Install or update patches')
        Write-Info (L '  [2] 移除部分修補' '  [2] Remove some patches')
        Write-Info (L '  [3] 全部移除（遊戲要更新前選這個）' '  [3] Remove everything (do this before a game update)')
        Write-Info (L '  [4] 查看目前安裝狀態' '  [4] Show installation status')
        Write-Info (L '  [L] English' '  [L] 中文')
        Write-Info (L '  [0] 離開' '  [0] Exit')
        $c = Read-Choice (L '請輸入數字後按 Enter' 'Type a number and press Enter') @('0', '1', '2', '3', '4', 'L')
        if ($c -eq 'L') {
            # 切換語言後直接重畫選單，不需要「按 Enter 回到主選單」
            $script:Lang = if ($script:Lang -eq 'en') { 'zh' } else { 'en' }
            continue
        }
        try {
            switch ($c) {
                '1' {
                    if (-not $Manifest) {
                        Write-Bad (L '找不到 manifest.json——安裝必須從解壓後的安裝包資料夾執行 Install-Patches.bat。' 'manifest.json not found. To install, run Install-Patches.bat from the extracted package folder.')
                        break
                    }
                    $picked = Select-InstallModules $Manifest
                    if ($picked.Count -eq 0) { break }
                    Invoke-Install -Manifest $Manifest -PackageDir $PackageDir -GameRoot $GameRoot -Requested $picked -RequestAll $false -Interactive $true
                }
                '2' {
                    Invoke-Recover $GameRoot
                    $st = Read-State $GameRoot
                    $ids = @(Get-StateModuleIds $st)
                    if ($ids.Count -eq 0) {
                        Invoke-Uninstall -Manifest $Manifest -GameRoot $GameRoot -Requested @() -RemoveAll $false -Interactive $true
                        break
                    }
                    $picked = Select-UninstallModules $ids
                    if ($picked.Count -eq 0) { break }
                    Invoke-Uninstall -Manifest $Manifest -GameRoot $GameRoot -Requested $picked -RemoveAll $false -Interactive $true
                }
                '3' { Invoke-Uninstall -Manifest $Manifest -GameRoot $GameRoot -Requested @() -RemoveAll $true -Interactive $true }
                '4' { Invoke-Status -Manifest $Manifest -GameRoot $GameRoot }
                '0' { return }
            }
        } catch {
            # 選單模式下單一動作失敗不該直接關掉視窗，讓使用者能看訊息並改選
            Write-Bad $_.Exception.Message
            Write-Info (L "（代碼 $(if ($script:FailCode) { $script:FailCode } else { $EXIT_INTERNAL })）" "(code $(if ($script:FailCode) { $script:FailCode } else { $EXIT_INTERNAL }))")
            $script:FailCode = $null
        }
        Write-Info ''
        Read-Host (L '按 Enter 回到主選單' 'Press Enter to return to the main menu') | Out-Null
    }
}

# ---------------------------------------------------------------- 主流程
function Main {
    $scriptDir = $PSScriptRoot
    $interactive = -not $NonInteractive

    $requested = @()
    foreach ($m in (ConvertTo-Array $Modules)) {
        foreach ($part in ($m -split ',')) {
            $t = $part.Trim()
            if ($t) { $requested += $t }
        }
    }

    if ($Action -eq 'menu' -and $NonInteractive) {
        Fail $EXIT_USAGE (L '-NonInteractive 必須搭配 -Action status|install|uninstall。' '-NonInteractive requires -Action status|install|uninstall.')
    }

    $packageDir = Resolve-PackageDir $PackageDir $scriptDir
    $manifest = $null
    if ($packageDir) { $manifest = Read-Manifest $packageDir }
    if ($Action -eq 'install' -and -not $manifest) {
        Fail $EXIT_PACKAGE (L '找不到 manifest.json。請在解壓後的安裝包資料夾內執行，或用 -PackageDir 指定。' 'manifest.json not found. Run this from the extracted package folder, or pass -PackageDir.')
    }

    $gameRoot = Resolve-GameDir $GameDir $scriptDir $interactive
    # 這裡故意不復原：復原會動檔案，只有 install／uninstall 這種本來就要動檔案的
    # 動作才做（而且是在確認遊戲已關閉之後）。status／menu 一律唯讀。

    switch ($Action) {
        'status' { Invoke-Status -Manifest $manifest -GameRoot $gameRoot }
        'install' {
            $req = $requested
            if (-not $All -and $req.Count -eq 0 -and $interactive) {
                $req = Select-InstallModules $manifest
                if ($req.Count -eq 0) { Write-Info (L '已取消。' 'Cancelled.'); return }
            }
            Invoke-Install -Manifest $manifest -PackageDir $packageDir -GameRoot $gameRoot -Requested @($req) -RequestAll ([bool]$All) -Interactive $interactive
        }
        'uninstall' {
            $req = $requested
            if (-not $All -and $req.Count -eq 0 -and $interactive) {
                # 先復原再列清單，否則使用者選的是復原前的舊模組清單
                Invoke-Recover $gameRoot
                $st = Read-State $gameRoot
                $ids = @(Get-StateModuleIds $st)
                if ($ids.Count -eq 0) {
                    Invoke-Uninstall -Manifest $manifest -GameRoot $gameRoot -Requested @() -RemoveAll $false -Interactive $true
                    return
                }
                $req = Select-UninstallModules $ids
                if ($req.Count -eq 0) { Write-Info (L '已取消。' 'Cancelled.'); return }
            }
            Invoke-Uninstall -Manifest $manifest -GameRoot $gameRoot -Requested @($req) -RemoveAll ([bool]$All) -Interactive $interactive
        }
        'menu' { Invoke-Menu -Manifest $manifest -PackageDir $packageDir -GameRoot $gameRoot }
    }
}

try {
    Main
    exit $EXIT_OK
} catch {
    $code = if ($script:FailCode) { $script:FailCode } else { $EXIT_INTERNAL }
    Write-Host ''
    Write-Bad $_.Exception.Message
    if ($code -eq $EXIT_INTERNAL) {
        Write-Host $_.ScriptStackTrace -ForegroundColor DarkGray
    }
    Write-Host (L "結束代碼：$code" "Exit code: $code") -ForegroundColor DarkGray
    exit $code
}
