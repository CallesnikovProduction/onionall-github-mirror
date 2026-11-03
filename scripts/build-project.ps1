[CmdletBinding()]
param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$Maven = 'mvn',
    [string]$Repository,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'cli/build-project.ps1') @PSBoundParameters
