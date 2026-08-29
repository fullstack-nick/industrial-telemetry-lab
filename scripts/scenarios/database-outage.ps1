[CmdletBinding()]
param([string] $EnvFile)

. (Join-Path $PSScriptRoot "../lib/common.ps1")
if ($EnvFile) { Use-TelemetryEnvironment $EnvFile }
Push-Location $script:TelemetryRepoRoot
try {
    Write-ScenarioHeader "database-outage"
    Start-CoreStack
    Reset-SimulatorFaults
    Wait-ForSpoolDrain
    $before = Get-CollectorStatus
    Invoke-TelemetryCompose @("stop", "timescaledb")
    Wait-ForCondition -Description "collector retains observations during database outage" -TimeoutSeconds 40 -Condition {
        return [long] (Get-CollectorStatus).spoolObservationCount -gt [long] $before.spoolObservationCount
    }
    $during = Get-CollectorStatus
    Assert-Condition ([long] $during.sourceCursor -gt [long] $before.sourceCursor) "edge acquisition remains independent of the platform database"

    Invoke-TelemetryCompose @("start", "timescaledb")
    $user = Get-TelemetryEnvValue "POSTGRES_USER" "telemetry"
    $database = Get-TelemetryEnvValue "POSTGRES_DB" "telemetry"
    Wait-ForCondition -Description "TimescaleDB accepts connections after restart" -TimeoutSeconds 90 -Condition {
        $null = & docker compose --env-file $script:TelemetryEnvFile exec -T timescaledb pg_isready -U $user -d $database
        return $LASTEXITCODE -eq 0
    }
    Wait-TelemetryHttp "http://localhost:8080/actuator/health/readiness" 120
    Wait-TelemetryHttp "http://localhost:8083/actuator/health/readiness" 120
    Wait-ForSpoolDrain 180
    Write-ScenarioPass "database-outage"
} finally {
    try { Invoke-TelemetryCompose @("start", "timescaledb", "telemetry-gateway", "telemetry-worker") } catch {}
    try { Reset-SimulatorFaults } catch {}
    Pop-Location
}
