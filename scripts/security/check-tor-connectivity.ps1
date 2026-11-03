[CmdletBinding()]
param([string]$Python = 'python', [int]$Seconds = 180)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
& $Python (Join-Path $root 'test/m2/tor_preflight.py') (Join-Path $root '.runtime/m2/connectivity-preflight') --seconds $Seconds
exit $LASTEXITCODE
