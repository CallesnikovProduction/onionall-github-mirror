[CmdletBinding()]
param([string]$Python = 'python')
$ErrorActionPreference = 'Stop'
$runner = Join-Path $PSScriptRoot 'm2.py'
& $Python $runner
exit $LASTEXITCODE
