[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    if (-not (Test-Path target/onionall-brawler-0.1.0-SNAPSHOT.jar)) {
        throw 'Build first with scripts/build.ps1 or mvn clean package'
    }
    & docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker engine is unavailable or access is denied. Check docker version in your normal host PowerShell; if Docker is running, execute this script there.'
    }
    $composeArgs = @('compose', '-f', 'compose.m0.yaml')
    & docker @composeArgs config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Compose validation failed' }
    New-Item -ItemType Directory -Force .runtime | Out-Null
    & docker @composeArgs pull
    if ($LASTEXITCODE -ne 0) { throw 'Image download failed' }
    try {
        & docker @composeArgs up --abort-on-container-exit --exit-code-from crawler 2>&1 |
            Tee-Object -FilePath .runtime/m0-console.log
        $runExit = $LASTEXITCODE
        & docker @composeArgs logs --no-color *> .runtime/m0-services.log
        if ($runExit -ne 0) { throw "M0 failed (exit $runExit); see .runtime/m0-services.log" }
        Write-Host 'Process exited successfully. Check logs for component startup, frontier RPCs and clean shutdown before accepting M0.'
    } finally {
        & docker @composeArgs down --timeout 60
        if ($LASTEXITCODE -ne 0) { Write-Warning 'Cleanup failed; inspect docker compose -f compose.m0.yaml ps -a' }
    }
} finally {
    Pop-Location
}
