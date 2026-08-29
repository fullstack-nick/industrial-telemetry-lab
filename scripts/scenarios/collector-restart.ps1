[CmdletBinding()]
param([string] $EnvFile)

. (Join-Path $PSScriptRoot "../lib/common.ps1")
if ($EnvFile) { Use-TelemetryEnvironment $EnvFile }
Push-Location $script:TelemetryRepoRoot
try {
    Write-ScenarioHeader "collector-restart"
    Start-CoreStack
    Reset-SimulatorFaults
    Wait-ForSpoolDrain
    Invoke-TelemetryCompose @("stop", "telemetry-gateway")
    Wait-ForCondition -Description "a durable collector backlog exists before restart" -TimeoutSeconds 35 -Condition {
        return [long] (Get-CollectorStatus).spoolObservationCount -gt 0
    }
    $before = Get-CollectorStatus
    Invoke-TelemetryCompose @("restart", "edge-collector")
    Wait-TelemetryHttp "http://localhost:8082/actuator/health/readiness"
    $after = Get-CollectorStatus
    Assert-Condition ($after.sourceEpoch -eq $before.sourceEpoch) "source epoch survives collector restart"
    Assert-Condition ([long] $after.sourceCursor -ge [long] $before.sourceCursor) "durable source cursor never rewinds"
    Assert-Condition ([long] $after.spoolObservationCount -ge [long] $before.spoolObservationCount) "buffered observations survive collector restart"

    Invoke-TelemetryCompose @("start", "telemetry-gateway")
    Wait-TelemetryHttp "http://localhost:8080/actuator/health/readiness"
    Wait-ForSpoolDrain 150
    Write-ScenarioPass "collector-restart"
} finally {
    try { Invoke-TelemetryCompose @("start", "telemetry-gateway", "edge-collector") } catch {}
    try { Reset-SimulatorFaults } catch {}
    Pop-Location
}
