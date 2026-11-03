[CmdletBinding()]
param([string]$Python = 'python', [int]$Seconds = 180)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'security/check-tor-connectivity.ps1') @PSBoundParameters
exit $LASTEXITCODE
