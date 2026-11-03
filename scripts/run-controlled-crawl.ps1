[CmdletBinding()]
param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'runners/run-controlled-crawl.ps1') @PSBoundParameters
