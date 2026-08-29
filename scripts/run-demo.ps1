[CmdletBinding()]
param([string] $EnvFile)

. (Join-Path $PSScriptRoot "lib/common.ps1")
if ($EnvFile) { Use-TelemetryEnvironment $EnvFile }
Push-Location $script:TelemetryRepoRoot
try {
    Write-Host "Starting the complete local portfolio stack..." -ForegroundColor Cyan
    & docker compose --env-file $script:TelemetryEnvFile --profile observability up -d --build
    if ($LASTEXITCODE -ne 0) { throw "Demo stack failed to start." }
    Wait-CoreStack
    Wait-TelemetryHttp "http://localhost:3000/api/health" 120
    Reset-SimulatorFaults
    $null = Set-SimulatorFaults @{ duplicateRate = 0.08; outOfOrderRate = 0.08; invalidUnitRate = 0.04; newUnknownTagEnabled = $true }
    Start-Sleep -Seconds 12
    Reset-SimulatorFaults
    Write-Host "Demo evidence is flowing. Open:" -ForegroundColor Green
    Write-Host "  Grafana dashboards  http://localhost:3000/dashboards"
    Write-Host "  OpenAPI / Swagger   http://localhost:8080/swagger-ui.html"
    Write-Host "  RabbitMQ management http://localhost:15672"
    Write-Host "  SeaweedFS filer     http://localhost:8888"
    Write-Host ""
    Write-Host "Run docs/demo.md for the complete outage, backlog, replay, raw-object, and trace walkthrough."
} finally {
    try { Reset-SimulatorFaults } catch {}
    Pop-Location
}
