[CmdletBinding()]
param([string] $EnvFile)

. (Join-Path $PSScriptRoot "../lib/common.ps1")
if ($EnvFile) { Use-TelemetryEnvironment $EnvFile }
Push-Location $script:TelemetryRepoRoot
try {
    Write-ScenarioHeader "invalid-unit"
    Start-CoreStack
    Reset-SimulatorFaults
    $before = [long] (Get-DatabaseScalar "SELECT COUNT(*) FROM telemetry_rejection WHERE reason_code='UNSUPPORTED_RAW_UNIT'")
    $rawBefore = [long] (Get-DatabaseScalar "SELECT COALESCE(SUM(observation_count),0) FROM ingestion_batch")
    $null = Set-SimulatorFaults @{ invalidUnitRate = 1.0 }
    Wait-ForCondition -Description "invalid units are rejected with a stable reason code" -TimeoutSeconds 50 -Condition {
        return [long] (Get-DatabaseScalar "SELECT COUNT(*) FROM telemetry_rejection WHERE reason_code='UNSUPPORTED_RAW_UNIT'") -gt $before
    }
    Reset-SimulatorFaults
    Assert-Condition ([long] (Get-DatabaseScalar "SELECT COALESCE(SUM(observation_count),0) FROM ingestion_batch") -gt $rawBefore) "invalid source observations remain durable in raw batches"
    Write-ScenarioPass "invalid-unit"
} finally {
    try { Reset-SimulatorFaults } catch {}
    Pop-Location
}
