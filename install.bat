@echo off
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul 2>&1
title MedPad Offline Installer v1.9.13-dev.3

rem ============================================================
rem  MedPad Offline Installer v1.9.13-dev.3
rem
rem  IMPORTANT: this file is ASCII-ONLY on purpose.
rem  Windows cmd.exe cannot reliably parse batch files that
rem  contain UTF-8/Persian text (v1.9.5 crashed with errors
rem  like "'MEDI_DIR' is not recognized as an internal or
rem  external command"). All Persian messages are printed by
rem  install.mjs / wizard.mjs (Node.js handles UTF-8 correctly).
rem
rem  v1.9.12: after the checks below, install.mjs shows a MENU:
rem    Q1 - where to install: 1) this computer  2) Linux VPS
rem                            3) Cloudflare (public HTTPS address)
rem    Q2 - how: 1) browser installer (GUI - opens automatically)
rem              2) wizard in this console (CLI)
rem
rem  Keeps every v1.9.4 - v1.9.10 fix:
rem   - every exit path pauses (no flash-close)
rem   - UAC elevation with cancel/failure handling
rem   - detects running from inside the ZIP
rem   - install.log diagnostics
rem   - auto-installs bundled Node.js MSI (100% offline)
rem   - app bundle version check BEFORE touching anything
rem ============================================================

set "MEDI_BAT=%~f0"
set "MEDI_DIR=%~dp0"
set "MEDI_LOG=%~dp0install.log"

rem -- pre-flight: whole package must be extracted (not run from inside ZIP)
if not exist "%~dp0install.mjs" (
    echo.
    echo  [X] install.mjs not found next to install.bat
    echo  ------------------------------------------------------
    echo  Most likely cause: you double-clicked install.bat
    echo  INSIDE the zip file.
    echo.
    echo  Fix:
    echo    1. Right-click the zip file and choose "Extract All"
    echo    2. Open the extracted folder
    echo    3. Run install.bat again
    echo.
    echo %DATE% %TIME% PREFLIGHT-FAIL install.mjs missing, bat=%MEDI_BAT% >> "%MEDI_LOG%"
    pause
    exit /b 1
)

rem -- pre-flight 2 (v1.9.12): the browser installer + server manager files
if not exist "%~dp0wizard.mjs" (
    echo  [X] wizard.mjs not found next to install.bat
    echo      The package is incomplete - extract the FULL zip again.
    echo %DATE% %TIME% PREFLIGHT-FAIL wizard.mjs missing >> "%MEDI_LOG%"
    pause
    exit /b 1
)
if not exist "%~dp0server-manager.mjs" (
    echo  [X] server-manager.mjs not found next to install.bat
    echo      The package is incomplete - extract the FULL zip again.
    echo %DATE% %TIME% PREFLIGHT-FAIL server-manager.mjs missing >> "%MEDI_LOG%"
    pause
    exit /b 1
)

rem -- self-elevation (UAC) with full error handling
net session >nul 2>&1
if %errorlevel% equ 0 goto :elevated

echo.
echo [..] Requesting administrator access (UAC prompt) ...
echo %DATE% %TIME% elevation requested, dir=%MEDI_DIR% >> "%MEDI_LOG%"

if "%~1"=="-elevated" (
    echo [X] Already relaunched but elevation still failed.
    echo     Continuing WITHOUT admin rights - panel will install,
    echo     but Node.js silent install may be limited.
    goto :elevated
)

powershell -NoProfile -ExecutionPolicy Bypass -Command "try { Start-Process -FilePath $env:MEDI_BAT -ArgumentList '-elevated' -Verb RunAs -WorkingDirectory $env:MEDI_DIR } catch { exit 1 }"
if %errorlevel% neq 0 (
    echo.
    echo [X] UAC elevation was CANCELLED or FAILED.
    echo     Common reasons: clicked "No" on the UAC prompt,
    echo     or admin rights are restricted on this PC.
    echo.
    echo     Continue WITHOUT admin rights? Panel will still install,
    echo     but the bundled Node.js silent install may be limited.
    echo.
    set /p CONT="Continue WITHOUT admin rights? (y/n): "
    if /i not "!CONT!"=="y" (
        echo %DATE% %TIME% elevation cancelled by user >> "%MEDI_LOG%"
        pause
        exit /b 1
    )
    echo %DATE% %TIME% elevation failed, continuing non-admin >> "%MEDI_LOG%"
    goto :elevated
)

rem elevated copy launched successfully - this non-elevated window can close
echo [OK] Elevated window launched. Please use that new window.
timeout /t 2 >nul
exit /b 0

:elevated
cd /d "%~dp0"
echo.
echo ==============================================
echo   MedPad Offline Installer v1.9.13-dev.3
echo   Node.js and all prerequisites are bundled.
echo   100%% offline - no internet needed.
echo ==============================================
echo.
echo  Next: a menu will ask WHERE to install
echo  (this PC / Linux VPS / Cloudflare) and HOW
echo  (browser installer - opens automatically -
echo   or a wizard in this console).
echo.
echo %DATE% %TIME% main start, dir=%CD% >> "%MEDI_LOG%"

rem -- Node.js: use system node, else install the bundled MSI (100% offline)
where node >nul 2>&1
if %errorlevel% equ 0 goto :node-ok

echo [..] Node.js not found - installing automatically from the offline bundle...
echo %DATE% %TIME% node missing - bundled MSI install >> "%MEDI_LOG%"

set "NODE_MSI="
for /r "%~dp0offline" %%f in (node-*.msi) do set "NODE_MSI=%%f"

if not defined NODE_MSI (
    echo [X] node-*.msi was not found in the "offline" folder.
    echo     The downloaded package is incomplete - please re-download it.
    echo %DATE% %TIME% FAIL bundled msi not found >> "%MEDI_LOG%"
    echo.
    pause
    exit /b 1
)

echo [..] Installing "!NODE_MSI!" silently (this can take a minute)...
msiexec /i "!NODE_MSI!" /qn /norestart /l* "%MEDI_LOG%-msi.txt"
if %errorlevel% neq 0 (
    echo [X] Silent MSI install failed (exit code %errorlevel%).
    echo     Details: %MEDI_LOG%-msi.txt
    echo %DATE% %TIME% FAIL msiexec exit=%errorlevel% >> "%MEDI_LOG%"
    echo.
    pause
    exit /b 1
)

rem -- refresh PATH for THIS session (MSI adds it system-wide, not to us)
set "PATH=%PATH%;C:\Program Files\nodejs"
where node >nul 2>&1
if %errorlevel% neq 0 (
    echo [X] Node.js still not found after MSI install.
    echo     Close this window once and run install.bat again.
    echo %DATE% %TIME% FAIL node still missing after MSI >> "%MEDI_LOG%"
    echo.
    pause
    exit /b 1
)
echo %DATE% %TIME% OK node installed from bundled MSI >> "%MEDI_LOG%"

:node-ok
for /f "delims=" %%v in ('node --version') do set NODEV=%%v
echo [OK] Node.js !NODEV!

rem -- run main pipeline (install.mjs shows the menu and prints all
rem    Persian messages; full details go to install.log)
node "%~dp0install.mjs" %*
set EXITCODE=%errorlevel%
echo %DATE% %TIME% install.mjs exit=%EXITCODE% >> "%MEDI_LOG%"
echo.
if %EXITCODE% neq 0 (
    echo [X] Install failed with code %EXITCODE%
    echo     Full technical details: %MEDI_LOG%
    echo     Please send install.log for support.
) else (
    echo [OK] Install finished successfully.
    echo     Start the panel:  double-click start.bat
    echo     Stop the panel:   double-click stop-server.bat
    echo     GUI control:      double-click server-manager.bat
    echo     The panel page opens in your browser automatically
    echo     after every start.
)
echo.
pause
exit /b %EXITCODE%
