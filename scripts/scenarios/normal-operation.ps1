[CmdletBinding()]
param([string] $EnvFile)

. (Join-Path $PSScriptRoot "../lib/common.ps1")
if ($EnvFile) { Use-TelemetryEnvironment $EnvFile }
Push-Location $script:TelemetryRepoRoot
try {
    Write-ScenarioHeader "normal-operation"
    Start-CoreStack
    Reset-SimulatorFaults
    Wait-ForSpoolDrain
    $before = [long] (Get-DatabaseScalar "SELECT COUNT(*) FROM telemetry_sample")
    Wait-ForCondition -Description "canonical telemetry continues to grow" -TimeoutSeconds 45 -Condition {
        return [long] (Get-DatabaseScalar "SELECT COUNT(*) FROM telemetry_sample") -gt $before
    }
    Wait-ForCondition -Description "all accepted manifests finish processing" -TimeoutSeconds 60 -Condition {
        return [long] (Get-DatabaseScalar "SELECT COUNT(*) FROM ingestion_batch WHERE processing_status <> 'PROCESSED'") -eq 0
    }

    $token = Get-TelemetryEnvValue "LOCAL_API_TOKEN" "local-development-token-change-me"
    $reconciliation = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/admin/raw-objects/reconciliation" -Headers @{ Authorization = "Bearer $token" }
    Assert-Condition ([bool] $reconciliation.healthy) "raw objects and manifests reconcile exactly"

    $counts = (Get-DatabaseScalar "SELECT COALESCE(SUM(observation_count),0) || '|' || COALESCE(SUM(accepted_count + flagged_count + rejected_count + duplicate_count),0) FROM ingestion_batch") -split '\|'
    Assert-Condition ([long] $counts[0] -eq [long] $counts[1]) "every durable raw observation has a recorded processing outcome"
    $status = Get-CollectorStatus
    Write-Host "Raw observations persisted:   $($counts[0])"
    Write-Host "Processing outcomes recorded: $($counts[1])"
    Write-Host "Collector spool observations: $($status.spoolObservationCount)"
    Write-ScenarioPass "normal-operation"
} finally {
    try { Reset-SimulatorFaults } catch {}
    Pop-Location
}
