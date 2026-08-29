[CmdletBinding()]
param([string] $EnvFile)

. (Join-Path $PSScriptRoot "../lib/common.ps1")
if ($EnvFile) { Use-TelemetryEnvironment $EnvFile }
Push-Location $script:TelemetryRepoRoot
try {
    Write-ScenarioHeader "worker-backlog"
    Start-CoreStack
    Reset-SimulatorFaults
    $manifestsBefore = [long] (Get-DatabaseScalar "SELECT COUNT(*) FROM ingestion_batch")
    $samplesBefore = [long] (Get-DatabaseScalar "SELECT COUNT(*) FROM telemetry_sample")
    Invoke-TelemetryCompose @("stop", "telemetry-worker")
    Wait-ForCondition -Description "gateway continues storing manifests while worker is stopped" -TimeoutSeconds 40 -Condition {
        return [long] (Get-DatabaseScalar "SELECT COUNT(*) FROM ingestion_batch") -gt $manifestsBefore
    }
    Wait-ForCondition -Description "RabbitMQ worker queue accumulates durable messages" -TimeoutSeconds 40 -Condition {
        return (Get-QueueReadyCount) -gt 0
    }
    $peak = Get-QueueReadyCount
    Write-Host "Ready messages at backlog peak: $peak"

    Invoke-TelemetryCompose @("start", "telemetry-worker")
    Wait-TelemetryHttp "http://localhost:8083/actuator/health/readiness"
    Wait-ForCondition -Description "worker queue drains after recovery" -TimeoutSeconds 150 -Condition {
        return (Get-QueueReadyCount) -eq 0
    }
    Assert-Condition ([long] (Get-DatabaseScalar "SELECT COUNT(*) FROM telemetry_sample") -gt $samplesBefore) "canonical writes resume after the worker restarts"
    Write-ScenarioPass "worker-backlog"
} finally {
    try { Invoke-TelemetryCompose @("start", "telemetry-worker") } catch {}
    try { Reset-SimulatorFaults } catch {}
    Pop-Location
}
