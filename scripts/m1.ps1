[CmdletBinding()]
param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$oldRunDir = $env:M1_RUN_DIR
$oldRunRel = $env:M1_RUN_REL
$project = 'onionall-brawler-m1-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$relative = ".runtime/m1/$project"
$runDir = Join-Path $root $relative
$composeArgs = @('compose', '-p', $project, '-f', 'compose.m1.yaml')

function Invoke-M1Compose {
    param([string[]]$DockerArgs, [string]$LogName)
    if ($LogName) {
        & docker @composeArgs @DockerArgs 2>&1 | Tee-Object -FilePath (Join-Path $runDir $LogName)
    } else {
        & docker @composeArgs @DockerArgs
    }
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed ($LASTEXITCODE): $($DockerArgs -join ' ')" }
}

function Invoke-M1Ready {
    param([string]$Phase)
    $probeName = "$project-probe-$Phase"
    try {
        & docker network inspect "${project}_controlled" > "$runDir/network-$Phase.json"
        if ($LASTEXITCODE -ne 0) { throw 'M1 controlled network inspection failed' }
        $ids = @(& docker @composeArgs ps -aq frontier fixture)
        if ($LASTEXITCODE -ne 0 -or $ids.Count -ne 2) { throw 'M1 requires frontier and fixture containers' }
        & docker inspect @ids > "$runDir/services-$Phase.json"
        if ($LASTEXITCODE -ne 0) { throw 'M1 service inspection failed' }
        foreach ($service in (Get-Content "$runDir/services-$Phase.json" -Raw | ConvertFrom-Json)) {
            $name = $service.Config.Labels.'com.docker.compose.service'
            $network = $service.NetworkSettings.Networks.PSObject.Properties["${project}_controlled"].Value
            if (-not $service.State.Running -or -not $network -or $network.Aliases -notcontains $name) {
                throw "M1 $name is stopped or lacks its service alias on the controlled network"
            }
        }
        # Retain this short-lived probe until inspection, even if readiness fails.
        Invoke-M1Compose -DockerArgs @('run', '--name', $probeName, '--no-deps', 'probe', 'ready') -LogName "ready-$Phase.log"
    } catch {
        throw "M1 readiness failed ($Phase): $($_.Exception.Message). See ready-$Phase.log, network-$Phase.json, services-$Phase.json and services.log in $runDir"
    } finally {
        & docker inspect $probeName > "$runDir/probe-$Phase.json"
        # Also covered by the outer Compose down if inspection/removal fails.
        & docker rm $probeName *> "$runDir/probe-cleanup-$Phase.log"
    }
}

Push-Location $root
try {
    if (-not (Test-Path target/onionall-brawler-0.1.0-SNAPSHOT.jar)) { throw 'Build the project first' }
    if (-not $JdkHome -or -not (Test-Path "$JdkHome/bin/javac.exe")) { throw 'Pass -JdkHome pointing to a JDK (25 or newer)' }
    $pythonCommand = Get-Command $Python -ErrorAction Stop
    & docker info *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Docker API unavailable or denied. Run this script from normal host PowerShell.' }
    New-Item -ItemType Directory -Force "$runDir/classes" | Out-Null
    $env:M1_RUN_DIR = $runDir
    $env:M1_RUN_REL = $relative
    & $pythonCommand.Source test/m1/prepare.py $runDir
    if ($LASTEXITCODE -ne 0) { throw 'Topology derivation failed' }
    $javaSources = @(Get-ChildItem test/m1 -Filter '*.java' | ForEach-Object { $_.FullName })
    & "$JdkHome/bin/javac.exe" --release 25 -cp target/onionall-brawler-0.1.0-SNAPSHOT.jar -d "$runDir/classes" @javaSources
    if ($LASTEXITCODE -ne 0) { throw 'Test probe compilation failed' }
    Copy-Item -LiteralPath target/onionall-brawler-0.1.0-SNAPSHOT.jar -Destination "$runDir/crawler-m1.jar"
    & "$JdkHome/bin/jar.exe" --update --file "$runDir/crawler-m1.jar" -C "$runDir/classes" . -C test/m1/resources .
    if ($LASTEXITCODE -ne 0) { throw 'Test-only jar assembly failed' }
    Invoke-M1Compose -DockerArgs @('config', '--quiet')
    & docker @composeArgs --profile tools config --format json > "$runDir/compose.json"
    if ($LASTEXITCODE -ne 0) { throw 'Could not record resolved M1 configuration' }
    Invoke-M1Compose -DockerArgs @('--profile', 'tools', 'pull') -LogName 'pull.log'
    & docker image inspect crawlercommons/url-frontier:2.4 storm:3.1.0-jre25 python:3.12-slim > "$runDir/images.json"
    if ($LASTEXITCODE -ne 0) { throw 'Image inspection failed' }
    $verified = $false
    try {
        Invoke-M1Compose -DockerArgs @('up', '-d', '--wait', '--wait-timeout', '60', 'frontier', 'fixture') -LogName 'startup.log'
        Invoke-M1Compose -DockerArgs @('run', '--rm', '--no-deps', 'probe', 'filter-check') -LogName 'filter-check.log'
        Invoke-M1Compose -DockerArgs @('run', '--rm', '--no-deps', 'probe', 'delay-check') -LogName 'delay-check.log'
        Invoke-M1Ready -Phase 'initial'
        Invoke-M1Compose -DockerArgs @('run', '--rm', '--no-deps', 'probe', 'snapshot', 'empty.json')
        if ((Get-Content "$runDir/empty.json" -Raw | ConvertFrom-Json).entries.Count -ne 0) { throw 'Fresh volume was not empty' }
        Invoke-M1Compose -DockerArgs @('run', '--rm', '--no-deps', 'probe', 'seed', 'root')
        Invoke-M1Compose -DockerArgs @('run', '--rm', '--no-deps', 'crawler') -LogName 'crawl-first.log'
        # Reached only after Compose run returned exit 0; no text-log lifecycle inference.
        [ordered]@{ first_exit_code = 0 } | ConvertTo-Json | Set-Content "$runDir/crawl-runtime.json" -Encoding utf8
        Invoke-M1Compose -DockerArgs @('run', '--rm', '--no-deps', 'probe', 'snapshot', 'crawled.json')
        Invoke-M1Compose -DockerArgs @('run', '--rm', '--no-deps', 'probe', 'seed', 'pending')
        Invoke-M1Compose -DockerArgs @('run', '--rm', '--no-deps', 'probe', 'snapshot', 'before.json')
        Invoke-M1Compose -DockerArgs @('stop', '--timeout', '30', 'frontier') -LogName 'frontier-stop.log'
        Invoke-M1Compose -DockerArgs @('logs', '--no-color', 'frontier') -LogName 'frontier-before.log'
        # Remove only the stopped container; retain the named RocksDB volume.
        Invoke-M1Compose -DockerArgs @('rm', '-f', 'frontier')
        Invoke-M1Compose -DockerArgs @('up', '-d', '--wait', '--wait-timeout', '60', 'frontier')
        Invoke-M1Ready -Phase 'recreated'
        Invoke-M1Compose -DockerArgs @('run', '--rm', '--no-deps', 'probe', 'snapshot', 'after.json')
        Invoke-M1Compose -DockerArgs @('run', '--rm', '--no-deps', 'crawler') -LogName 'crawl-second.log'
        [ordered]@{ first_exit_code = 0; second_exit_code = 0 } |
            ConvertTo-Json | Set-Content "$runDir/crawl-runtime.json" -Encoding utf8
        Invoke-M1Compose -DockerArgs @('run', '--rm', '--no-deps', 'probe', 'snapshot', 'final.json')
        & $pythonCommand.Source test/m1/verify.py $runDir 2>&1 | Tee-Object -FilePath "$runDir/verification.log"
        if ($LASTEXITCODE -ne 0) { throw "M1 assertions failed; evidence: $runDir" }
        $verified = $true
    } catch {
        throw "M1 failed: $($_.Exception.Message). Inspect startup.log and services.log in $runDir; volume retained."
    } finally {
        & docker @composeArgs ps -a --format json > "$runDir/services-final.json"
        & docker @composeArgs logs --no-color *> "$runDir/services.log"
        & docker @composeArgs down --timeout 60 2>&1 | Tee-Object -FilePath "$runDir/cleanup.log"
        $cleanupExit = $LASTEXITCODE
        $remaining = @(& docker @composeArgs ps -aq)
        if ($LASTEXITCODE -ne 0 -or $cleanupExit -ne 0 -or @($remaining | Where-Object { $_ }).Count -ne 0) {
            throw "Cleanup could not be verified. Inspect $project; volume retained. Evidence: $runDir"
        }
        & docker volume ls --filter "label=com.docker.compose.project=$project" --format '{{.Name}}' > "$runDir/retained-volumes.txt"
        if ($LASTEXITCODE -ne 0) { throw 'Could not record retained volumes' }
    }
    if ($verified) {
        [ordered]@{ assertions_passed = $true; containers_removed = $true; project = $project;
            evidence = $relative; volume_retained = "${project}_frontier-data" } |
            ConvertTo-Json | Set-Content "$runDir/result.json" -Encoding utf8
        Write-Host "M1 assertions passed. Review evidence and warnings before milestone acceptance: $runDir"
    }
} finally {
    $env:M1_RUN_DIR = $oldRunDir
    $env:M1_RUN_REL = $oldRunRel
    Pop-Location
}
