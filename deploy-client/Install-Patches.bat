@echo off
setlocal EnableExtensions
set "SRC=%~dp0"

rem Launching from a PowerShell 7 terminal leaves pwsh's module dirs ahead of the
rem Windows PowerShell 5.1 ones, which breaks Get-FileHash. Clearing the variable
rem makes powershell.exe rebuild its own default module path.
set "PSModulePath="

rem Linear goto flow, no parenthesised blocks: cmd mis-parses ( ) blocks when the
rem path itself contains parentheses (measured on real installs).

if exist "%SRC%Manage-Patches.ps1" goto :haveps
echo [ERROR] Manage-Patches.ps1 is missing next to this file.
echo         Re-extract the whole zip, keeping every file together.
pause
exit /b 6
:haveps

rem UTF-8 console so the Chinese menu renders on any Windows locale; restore on exit.
set "OLDCP="
for /f "tokens=2 delims=:" %%C in ('chcp') do set "OLDCP=%%C"
set "OLDCP=%OLDCP: =%"
chcp 65001 >nul

powershell -NoProfile -ExecutionPolicy Bypass -File "%SRC%Manage-Patches.ps1" %*
set "RC=%ERRORLEVEL%"

if not defined OLDCP goto :nocp
chcp %OLDCP% >nul
:nocp

if "%RC%"=="0" goto :done
echo.
echo [ERROR] Finished with exit code %RC%.
:done
echo.
pause
exit /b %RC%
