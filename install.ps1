# MedPad Offline Installer v1.9.11 - PowerShell bootstrap (alternative entry)
# install.bat is the primary entry (self-elevation + bundled Node.js MSI install);
# this script runs the same node pipeline when invoked directly.
# v1.9.11: install.mjs shows a MENU (target: this PC / Linux VPS / Cloudflare,
# interface: browser installer GUI - opens automatically - or CLI wizard).
# NOTE: ASCII-only on purpose - Windows PowerShell 5.1 misparses UTF-8 .ps1
# files saved without BOM (same root cause as the v1.9.5 install.bat crash).
$ErrorActionPreference = 'Stop'

Write-Host ''
Write-Host '==============================================' -ForegroundColor Cyan
Write-Host '  MedPad Offline Installer v1.9.11' -ForegroundColor Cyan
Write-Host '  Node.js and all prerequisites are bundled.' -ForegroundColor Cyan
Write-Host '==============================================' -ForegroundColor Cyan

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
  $msi = Get-ChildItem -Path (Join-Path $PSScriptRoot 'offline') -Filter 'node-*.msi' -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($msi) {
    Write-Host '[..] Node.js not found - installing from the offline bundle...' -ForegroundColor Yellow
    Start-Process msiexec.exe -ArgumentList "/i `"$($msi.FullName)`" /qn /norestart" -Wait
    $env:Path = "$env:Path;C:\Program Files\nodejs"
  }
}

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
  Write-Host '[X] Node.js not found and bundled MSI missing - package incomplete' -ForegroundColor Red
  exit 1
}

$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
& node "$dir\install.mjs" @args
exit $LASTEXITCODE
