# Compatibility entrypoint; use run-tor-acceptance.ps1.
[CmdletBinding()]
param(
    [string]$Python = 'python',
    [switch]$CachedImages,
    [switch]$Help,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$RunnerArguments = @()
)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'run-tor-acceptance.ps1') @PSBoundParameters
exit $LASTEXITCODE
