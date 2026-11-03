# Compatibility entrypoint; use run-controlled-crawl.ps1.
[CmdletBinding()]
param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'run-controlled-crawl.ps1') @PSBoundParameters
