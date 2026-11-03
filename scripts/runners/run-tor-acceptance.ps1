[CmdletBinding()]
param(
    [string]$Python = 'python',
    [switch]$CachedImages,
    [switch]$Help,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$RunnerArguments = @()
)
$ErrorActionPreference = 'Stop'
$runner = Join-Path $PSScriptRoot 'tor_acceptance_runner.py'
$pythonArguments = @()
if ($CachedImages) { $pythonArguments += '--cached-images' }
if ($Help) { $pythonArguments += '--help' }
$pythonArguments += $RunnerArguments
& $Python $runner @pythonArguments
exit $LASTEXITCODE
