# Tests the built Windows one-line installer (install-windows.ps1) the way players run it: irm | iex, the scriptblock form with
# arguments, a bad download, a script cut off halfway, and once for real with a child Windows PowerShell running the extracted
# Manage-Patches.ps1. build-client.ps1 runs this under both powershell.exe and pwsh.exe.
# Invoke-WebRequest and powershell.exe are replaced by same-named functions (functions win over cmdlets and programs), so the
# product script has no test hook. Variables in the fakes use $script: so they never read the bootstrap's own variables.
param([Parameter(Mandatory)][string]$Boot, [Parameter(Mandatory)][string]$Zip, [Parameter(Mandatory)][string]$Jar, [Parameter(Mandatory)][string]$Work)
$ErrorActionPreference = 'Stop'
$text = [IO.File]::ReadAllText($Boot)
$script:FakeZip = $Zip
$zipName = Split-Path -Leaf $Zip
if ($zipName -notmatch '^MinidoracatClientPatches-[^-]+-(.+)\.zip$') { throw "unexpected zip name $zipName" }
$version = $Matches[1]
$latest = 'https://github.com/Minidoracat/MinidoracatJavaPatchFor42/releases/latest/download/install-windows.ps1'
$script:Fails = 0
function Check([bool]$ok, [string]$what) {
    if ($ok) { Write-Host "  PASS $what" } else { $script:Fails++; Write-Host "  FAIL $what" -ForegroundColor Red }
}
function Invoke-WebRequest {
    param([string]$Uri, [string]$OutFile, [switch]$UseBasicParsing)
    $script:Uri = $Uri
    if ($script:Mode -eq 'fail') { throw 'fake 404' }
    if ($script:Mode -eq 'trunc') { [IO.File]::WriteAllBytes($OutFile, [byte[]]([IO.File]::ReadAllBytes($script:FakeZip)[0..999])); return }
    Copy-Item -LiteralPath $script:FakeZip -Destination $OutFile
}
function powershell.exe {
    $script:PsArgs = @($args)
    $f = $args[[Array]::IndexOf($args, '-File') + 1]
    $script:Seen = @{ File = $f; Script = (Test-Path -LiteralPath $f); Manifest = (Test-Path -LiteralPath (Join-Path (Split-Path $f) 'manifest.json'))
        ModPath = $env:PSModulePath; Enc = [Console]::OutputEncoding.WebName }
    $global:LASTEXITCODE = $script:FakeExit
}
function Reset { $script:Uri = $null; $script:PsArgs = $null; $script:Seen = $null; $script:Mode = 'ok'; $script:FakeExit = 0; $global:LASTEXITCODE = 99 }
# Out-String -Width: Windows PowerShell 5.1 would otherwise wrap long lines
function Run([scriptblock]$sb) { & $sb 6>&1 | Out-String -Width 4096 }
function TempLeft { @(Get-ChildItem -LiteralPath ([IO.Path]::GetTempPath()) -Filter 'mdc-client-patches-*' -ErrorAction SilentlyContinue).Count }

$mp0 = $env:PSModulePath
$enc0 = [Console]::OutputEncoding.WebName
$temp0 = TempLeft
Write-Host "bootstrap-test: PowerShell $($PSVersionTable.PSVersion), $zipName"

Write-Host '1) irm | iex, no arguments (Chinese UI)'
Reset
[Threading.Thread]::CurrentThread.CurrentUICulture = 'zh-TW'
$out = Run { $text | iex }
Check ($script:Uri -eq "https://github.com/Minidoracat/MinidoracatJavaPatchFor42/releases/download/client-v$version/$zipName") "download URL $script:Uri"
Check (($script:PsArgs -join '|') -eq "-NoProfile|-ExecutionPolicy|Bypass|-File|$($script:Seen.File)|quick") "installer arguments $($script:PsArgs -join ' ')"
Check ($script:Seen.Script -and $script:Seen.Manifest) 'package extracted, manifest.json next to Manage-Patches.ps1'
Check ([string]::IsNullOrEmpty($script:Seen.ModPath) -and $script:Seen.Enc -eq $enc0) 'installer sees an empty PSModulePath and an untouched console encoding'
Check (-not (Test-Path -LiteralPath (Split-Path (Split-Path $script:Seen.File)))) 'temporary folder removed'
Check ([string]$env:PSModulePath -eq [string]$mp0 -and [Console]::OutputEncoding.WebName -eq $enc0) 'PSModulePath and console encoding restored'
Check ($LASTEXITCODE -eq 0) "LASTEXITCODE $LASTEXITCODE"
Check ($out.Contains([regex]::Unescape('\u4e0b\u8f09\u4e2d\uff1a') + $zipName) -and $out.Contains("& ([scriptblock]::Create((irm $latest))) menu")) 'Chinese text decoded and the menu command printed'
Check (-not ($out -match '\\u[0-9a-fA-F]{4}')) 'no \uXXXX escapes reach the screen'
Check (-not (Get-Command L -ErrorAction SilentlyContinue) -and -not (Test-Path variable:sha) -and -not (Test-Path variable:releases) -and $ErrorActionPreference -eq 'Stop' -and $ProgressPreference -ne 'SilentlyContinue') 'nothing left in the caller''s session'

Write-Host '2) scriptblock form with menu (English UI and region)'
Reset
[Threading.Thread]::CurrentThread.CurrentUICulture = 'en-US'
[Threading.Thread]::CurrentThread.CurrentCulture = 'en-US'
$out = Run { & ([scriptblock]::Create($text)) menu }
Check ($script:PsArgs.Count -eq 6 -and $script:PsArgs[-1] -eq 'menu') "arguments end with menu ($($script:PsArgs -join ' '))"
Check (-not $out.Contains('paste this line') -and $out.Contains("Downloading $zipName") -and -not ($out -match '[^\x00-\x7F]') -and -not ($out -match '\\u[0-9a-fA-F]{4}')) 'English only, no menu hint after menu'

Write-Host '3) arguments pass through unchanged; -Lang en also switches the bootstrap''s own lines (Chinese UI)'
Reset
[Threading.Thread]::CurrentThread.CurrentUICulture = 'zh-TW'
$out = Run { iex "& { $text } uninstall -All -Lang en" }
Check (($script:PsArgs[5..8] -join ' ') -eq 'uninstall -All -Lang en') "arguments $($script:PsArgs[5..8] -join ' ')"
Check ($out.Contains("Downloading $zipName") -and -not ($out -match '[^\x00-\x7F]')) 'English bootstrap lines with -Lang en'
[Threading.Thread]::CurrentThread.CurrentUICulture = 'en-US'

Write-Host '4) installer fails with code 7'
Reset
$script:FakeExit = 7
$out = Run { $text | iex }
Check ($LASTEXITCODE -eq 7 -and $out.Contains('paste this line')) "LASTEXITCODE $LASTEXITCODE and the menu command still shown"

Write-Host '5) truncated download'
Reset
$script:Mode = 'trunc'
$out = Run { $text | iex }
Check ($LASTEXITCODE -eq 6 -and $null -eq $script:PsArgs -and $out.Contains('SHA-256') -and $out.Contains('[Error]') -and -not $out.Contains('paste this line')) "LASTEXITCODE $LASTEXITCODE, installer not run"
Check ((TempLeft) -eq $temp0) 'no temporary folder left'

Write-Host '6) download fails'
Reset
$script:Mode = 'fail'
$out = Run { $text | iex }
Check ($LASTEXITCODE -eq 6 -and $null -eq $script:PsArgs -and $out.Contains('fake 404')) "LASTEXITCODE $LASTEXITCODE"

Write-Host '7) script cut off halfway runs nothing'
$ran = 0
foreach ($k in 1..15) {
    Reset
    try { $null = Run { $text.Substring(0, [int]($text.Length * $k / 16)) | iex } } catch { }
    if ($script:Uri -or $script:PsArgs) { $ran++ }
}
Reset
try { $null = Run { $text.Substring(0, $text.LastIndexOf('} @args')) | iex } } catch { }
if ($script:Uri -or $script:PsArgs) { $ran++ }
Check ($ran -eq 0) "cut-off scripts that ran something: $ran"

Write-Host '8) real child Windows PowerShell runs the extracted installer'
Reset
Remove-Item function:powershell.exe
$game = Join-Path $Work 'game'
if (Test-Path -LiteralPath $Work) { Remove-Item -LiteralPath $Work -Recurse -Force }
New-Item -ItemType Directory -Path $game -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $game 'ProjectZomboid64.exe'), 'stub')
Copy-Item -LiteralPath $Jar -Destination (Join-Path $game 'projectzomboid.jar')
# no pipe into Select-Object -First: it would stop the child process early
$out = Run { & ([scriptblock]::Create($text)) status -NonInteractive -GameDir $game -Lang en }
Check ($LASTEXITCODE -eq 0 -and $out.Contains("This package offers: $version") -and -not $out.Contains('does not match this package')) "status exit $LASTEXITCODE, offers $version for this game jar"
Check ((TempLeft) -eq $temp0) 'no temporary folder left'
Write-Host '  caller can still print after the installer ran'
$global:PSNativeCommandUseErrorActionPreference = $true
$out = Run { & ([scriptblock]::Create($text)) status -NonInteractive -GameDir (Join-Path $Work 'none') -Lang en }
Check ($LASTEXITCODE -eq 3) "installer exit code survives PSNativeCommandUseErrorActionPreference ($LASTEXITCODE)"
$global:PSNativeCommandUseErrorActionPreference = $false
Remove-Item -LiteralPath $Work -Recurse -Force

if ($script:Fails -gt 0) { Write-Host "bootstrap-test: $script:Fails failed" -ForegroundColor Red; exit 1 }
Write-Host 'bootstrap-test: all passed'
exit 0
