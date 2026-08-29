[CmdletBinding()]
param(
    [string] $EnvFile,
    [switch] $SkipScenarios
)

. (Join-Path $PSScriptRoot "lib/common.ps1")
if ($EnvFile) { Use-TelemetryEnvironment $EnvFile }
Push-Location $script:TelemetryRepoRoot
try {
    & (Join-Path $PSScriptRoot "check-prerequisites.ps1")
    if ($LASTEXITCODE -ne 0) { throw "Prerequisite checks failed." }

    Write-Host "Running the Maven unit, formatting, SpotBugs, and coverage gates..." -ForegroundColor Cyan
    & (Join-Path $script:TelemetryRepoRoot "mvnw.cmd") --no-transfer-progress verify
    if ($LASTEXITCODE -ne 0) { throw "Maven verification failed." }

    & docker compose --env-file $script:TelemetryEnvFile config --quiet
    if ($LASTEXITCODE -ne 0) { throw "Core Compose configuration is invalid." }
    & docker compose --env-file $script:TelemetryEnvFile --profile observability config --quiet
    if ($LASTEXITCODE -ne 0) { throw "Observability Compose configuration is invalid." }
    Get-ChildItem (Join-Path $script:TelemetryRepoRoot "observability/grafana/dashboards/*.json") | ForEach-Object {
        Get-Content -Raw $_.FullName | ConvertFrom-Json | Out-Null
    }
    Write-Host "ASSERT PASS: Compose and dashboard artifacts parse cleanly" -ForegroundColor Green

    Invoke-TelemetryCompose @("up", "-d", "--build")
    Wait-CoreStack
    & (Join-Path $PSScriptRoot "verify-openapi.ps1")
    if (-not $SkipScenarios) {
        & (Join-Path $PSScriptRoot "run-end-to-end-tests.ps1") -EnvFile $script:TelemetryEnvFile
    }
    Write-Host "Local verification passed." -ForegroundColor Green
} finally {
    Pop-Location
}
