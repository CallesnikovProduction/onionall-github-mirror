# Compatibility entrypoint; use check-tor-connectivity.ps1.
[CmdletBinding()]
param([string]$Python = 'python', [int]$Seconds = 180)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'check-tor-connectivity.ps1') @PSBoundParameters
exit $LASTEXITCODE
