[CmdletBinding()]
param([string] $EnvFile)

. (Join-Path $PSScriptRoot "../lib/common.ps1")
if ($EnvFile) { Use-TelemetryEnvironment $EnvFile }
Push-Location $script:TelemetryRepoRoot
try {
    Write-ScenarioHeader "gateway-outage"
    Start-CoreStack
    Reset-SimulatorFaults
    Wait-ForSpoolDrain
    $before = Get-CollectorStatus
    Invoke-TelemetryCompose @("stop", "telemetry-gateway")
    Wait-ForCondition -Description "collector buffers readings while the gateway is down" -TimeoutSeconds 35 -Condition {
        $status = Get-CollectorStatus
        return [long] $status.spoolObservationCount -gt [long] $before.spoolObservationCount
    }
    $during = Get-CollectorStatus
    Assert-Condition ([long] $during.sourceCursor -gt [long] $before.sourceCursor) "source polling continues during the gateway outage"
    Wait-ForCondition -Description "at least one exact compressed batch remains pending" -TimeoutSeconds 20 -Condition {
        return [long] (Get-CollectorStatus).pendingBatchCount -gt 0
    }
    $during = Get-CollectorStatus
    Write-Host "Spool before outage: $($before.spoolObservationCount)"
    Write-Host "Spool during outage: $($during.spoolObservationCount)"

    Invoke-TelemetryCompose @("start", "telemetry-gateway")
    Wait-TelemetryHttp "http://localhost:8080/actuator/health/readiness"
    Wait-ForSpoolDrain 150
    Write-ScenarioPass "gateway-outage"
} finally {
    try { Invoke-TelemetryCompose @("start", "telemetry-gateway") } catch {}
    try { Reset-SimulatorFaults } catch {}
    Pop-Location
}
