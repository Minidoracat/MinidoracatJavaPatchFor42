# Minidoracat client patches for Windows, one-line installer: downloads this release's package, checks its SHA-256 and opens
# the installer's menu (install, remove, status, memory limit; [L] switches the language):
#   irm https://github.com/Minidoracat/MinidoracatJavaPatchFor42/releases/latest/download/install-windows.ps1 | iex
# To run one action instead, anything after the last parenthesis goes to Manage-Patches.ps1 (status, uninstall -All, -Lang en):
#   & ([scriptblock]::Create((irm https://github.com/Minidoracat/MinidoracatJavaPatchFor42/releases/latest/download/install-windows.ps1))) status
# build-client.ps1 fills in the @...@ values and turns every non-ASCII character into \uXXXX: Windows PowerShell 5.1 reads the
# download as Latin-1, so players must get pure ASCII. Chinese text goes only in the first argument of L, as a fixed single-quoted
# string without backslashes; values are added with -f afterwards. The zip and this script come from the same build.
# Everything is one script block that only the last line closes and calls: half a download does not parse, so nothing runs.
# No exit, no functions or variables left behind: irm | iex runs inside the player's own PowerShell window.
& {
    $ErrorActionPreference = 'Stop'
    $ProgressPreference = 'SilentlyContinue' # the Windows PowerShell 5.1 progress bar makes the download many times slower
    $PSNativeCommandUseErrorActionPreference = $false # PowerShell 7.4+: keep the installer's own exit code instead of a thrown error
    $version = '@PACKAGE_VERSION@'
    $zipName = '@ZIP_NAME@'
    $sha = '@ZIP_SHA256@'
    $releases = 'https://github.com/Minidoracat/MinidoracatJavaPatchFor42/releases'
    # same rule as -Lang auto in Manage-Patches.ps1
    $zh = @([Globalization.CultureInfo]::CurrentUICulture.Name, [Globalization.CultureInfo]::CurrentCulture.Name) -like 'zh*'
    function L([string]$z, [string]$e) { if ($zh) { [regex]::Unescape($z) } else { $e } }
    $a = @($args)
    if ($a.Count -eq 0) { $a = @('menu') }
    # -Lang zh/en passed on to the installer also sets the language of these few lines
    for ($i = 0; $i -lt $a.Count - 1; $i++) { if ($a[$i] -eq '-Lang' -and $a[$i + 1] -in 'zh', 'en') { $zh = $a[$i + 1] -eq 'zh' } }
    Write-Host "Minidoracat Client Patches $version (Project Zomboid @GAME_VERSION@)"
    if ($Host.Name -eq 'Windows PowerShell ISE Host') {
        # the installer is a console program that asks questions; the ISE cannot run those
        Write-Host (L '請改用一般的 Windows PowerShell 視窗（不是 ISE），再貼一次指令。' 'Use a regular Windows PowerShell window (not the ISE) and paste the command again.') -ForegroundColor Red
        return
    }
    $dir = Join-Path ([IO.Path]::GetTempPath()) ('mdc-client-patches-' + [Guid]::NewGuid().ToString('N'))
    $oldModulePath = $env:PSModulePath
    $code = 6
    try {
        New-Item -ItemType Directory -Path $dir | Out-Null
        $zip = Join-Path $dir $zipName
        Write-Host ((L '下載中：{0}' 'Downloading {0}') -f $zipName)
        try { Invoke-WebRequest -UseBasicParsing -Uri "$releases/download/client-v$version/$zipName" -OutFile $zip }
        catch { throw ((L '下載失敗，請檢查網路後再貼一次指令。（{0}）' 'Download failed. Check your connection and paste the command again. ({0})') -f $_.Exception.Message) }
        # .NET instead of Get-FileHash and Expand-Archive: both fail in a Windows PowerShell 5.1 that inherited PowerShell 7's module path
        $got = [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash([IO.File]::ReadAllBytes($zip))).Replace('-', '').ToLowerInvariant()
        if ($got -ne $sha) {
            throw ((L '下載的檔案不完整或和這一版不符（SHA-256 {0}，應為 {1}）。過幾分鐘再試一次。' 'The download is incomplete or does not belong to this release (SHA-256 {0}, expected {1}). Try again in a few minutes.') -f $got, $sha)
        }
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        [IO.Compression.ZipFile]::ExtractToDirectory($zip, (Join-Path $dir 'pkg'))
        # like Install-Patches.bat: the installer itself needs Get-FileHash. The console code page is left alone on purpose:
        # Windows PowerShell 5.1 started on a UTF-8 console reports en-US as the UI language.
        $env:PSModulePath = $null
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $dir 'pkg\Manage-Patches.ps1') @a
        $code = $LASTEXITCODE
    } catch {
        Write-Host ''
        Write-Host ((L '[錯誤] {0}' '[Error] {0}') -f $_.Exception.Message) -ForegroundColor Red
    } finally {
        $env:PSModulePath = $oldModulePath
        Remove-Item -LiteralPath $dir -Recurse -Force -ErrorAction SilentlyContinue
    }
    $global:LASTEXITCODE = $code
} @args
