[CmdletBinding()]
param([string] $EnvFile)

. (Join-Path $PSScriptRoot "../lib/common.ps1")
if ($EnvFile) { Use-TelemetryEnvironment $EnvFile }
Push-Location $script:TelemetryRepoRoot
try {
    Write-ScenarioHeader "duplicate-delivery"
    Start-CoreStack
    Reset-SimulatorFaults
    $duplicatesBefore = [long] (Get-DatabaseScalar "SELECT COALESCE(SUM(duplicate_count),0) FROM ingestion_batch")
    $outOfOrderSql = 'SELECT COUNT(*) FROM telemetry_sample WHERE flags @> ''["OUT_OF_ORDER"]''::jsonb'
    $outOfOrderBefore = [long] (Get-DatabaseScalar $outOfOrderSql)
    $null = Set-SimulatorFaults @{ duplicateRate = 1.0; outOfOrderRate = 1.0 }
    Wait-ForCondition -Description "duplicate observations reach the idempotent worker" -TimeoutSeconds 50 -Condition {
        return [long] (Get-DatabaseScalar "SELECT COALESCE(SUM(duplicate_count),0) FROM ingestion_batch") -gt $duplicatesBefore
    }
    Reset-SimulatorFaults
    Wait-ForCondition -Description "late events are retained with OUT_OF_ORDER flags" -TimeoutSeconds 50 -Condition {
        return [long] (Get-DatabaseScalar $outOfOrderSql) -gt $outOfOrderBefore
    }
    $duplicateRows = [long] (Get-DatabaseScalar "SELECT COUNT(*) FROM (SELECT observation_id FROM telemetry_sample_identity GROUP BY observation_id HAVING COUNT(*) > 1) d")
    Assert-Condition ($duplicateRows -eq 0) "deterministic identity constraint prevents duplicate canonical identities"
    $duplicatesAfter = [long] (Get-DatabaseScalar "SELECT COALESCE(SUM(duplicate_count),0) FROM ingestion_batch")
    Write-Host "Duplicate outcomes added: $($duplicatesAfter - $duplicatesBefore)"
    Write-ScenarioPass "duplicate-delivery"
} finally {
    try { Reset-SimulatorFaults } catch {}
    Pop-Location
}
