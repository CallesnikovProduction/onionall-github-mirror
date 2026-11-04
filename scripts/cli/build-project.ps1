[CmdletBinding()]
param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$Maven = 'mvn',
    [string]$Repository,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:PATH
Push-Location $root
try {
    if ($JdkHome) {
        if (-not (Test-Path -LiteralPath "$JdkHome/bin/javac.exe")) {
            throw "JDK not found: $JdkHome"
        }
        $env:JAVA_HOME = $JdkHome
        $env:PATH = "$JdkHome/bin;$env:PATH"
    }
    $mavenCommand = Get-Command $Maven -ErrorAction SilentlyContinue
    if (-not $mavenCommand -and $Maven -eq 'mvn') {
        $bundledMaven = Get-ChildItem "$env:ProgramFiles/JetBrains/*/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending | Select-Object -First 1
        if ($bundledMaven) { $mavenCommand = Get-Command $bundledMaven.FullName }
    }
    if (-not $mavenCommand) { throw 'Maven not found. Add mvn to PATH or pass -Maven with the path to mvn.cmd.' }
    New-Item -ItemType Directory -Force .runtime | Out-Null
    & $mavenCommand.Source -version
    if ($LASTEXITCODE -ne 0) { throw 'Maven environment check failed' }
    $arguments = @('-B', '-pl', ':onionall-brawler', 'clean', 'package')
    if ($Offline) { $arguments += '-o' }
    if ($Repository) { $arguments += "-Dmaven.repo.local=$Repository" }
    & $mavenCommand.Source @arguments 2>&1 | Tee-Object -FilePath .runtime/build.log
    if ($LASTEXITCODE -ne 0) { throw "Build failed; see .runtime/build.log" }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:PATH = $previousPath
    Pop-Location
}
