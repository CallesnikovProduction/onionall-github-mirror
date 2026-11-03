# Compatibility entrypoint; use build-project.ps1.
[CmdletBinding()]
param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$Maven = 'mvn',
    [string]$Repository,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'build-project.ps1') @PSBoundParameters
