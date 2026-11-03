# Compatibility entrypoint; use run-startup-check.ps1.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'run-startup-check.ps1') @PSBoundParameters
