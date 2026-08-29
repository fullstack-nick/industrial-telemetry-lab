[CmdletBinding()]
param([string] $EnvFile)

. (Join-Path $PSScriptRoot "lib/common.ps1")
if ($EnvFile) { Use-TelemetryEnvironment $EnvFile }
Push-Location $script:TelemetryRepoRoot
try {
    Write-Host "Building and starting the core telemetry stack..." -ForegroundColor Cyan
    Invoke-TelemetryCompose @("up", "-d", "--build")
    Wait-CoreStack
    $scenarios = @(
        "normal-operation",
        "gateway-outage",
        "collector-restart",
        "worker-backlog",
        "database-outage",
        "duplicate-delivery",
        "invalid-unit",
        "unknown-tag-and-replay"
    )
    foreach ($scenario in $scenarios) {
        & (Join-Path $PSScriptRoot "scenarios/$scenario.ps1") -EnvFile $script:TelemetryEnvFile
        if (-not $?) { throw "Scenario failed: $scenario" }
    }
    Write-Host "All eight end-to-end scenarios passed." -ForegroundColor Green
} finally {
    try { Reset-SimulatorFaults } catch {}
    try { Invoke-TelemetryCompose @("start", "timescaledb", "rabbitmq", "seaweedfs", "controller-simulator", "telemetry-gateway", "telemetry-worker", "edge-collector") } catch {}
    Pop-Location
}
