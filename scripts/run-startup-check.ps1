[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'runners/run-startup-check.ps1') @PSBoundParameters
