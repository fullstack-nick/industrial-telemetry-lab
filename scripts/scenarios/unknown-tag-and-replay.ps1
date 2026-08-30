[CmdletBinding()]
param([string] $EnvFile)

. (Join-Path $PSScriptRoot "../lib/common.ps1")
if ($EnvFile) { Use-TelemetryEnvironment $EnvFile }
Push-Location $script:TelemetryRepoRoot
try {
    Write-ScenarioHeader "unknown-tag-and-replay"
    Start-CoreStack
    Reset-SimulatorFaults
    $rejectionsBefore = [long] (Get-DatabaseScalar "SELECT COUNT(*) FROM telemetry_rejection WHERE reason_code='UNKNOWN_SOURCE_TAG'")
    $from = [DateTimeOffset]::UtcNow.AddMinutes(-2)
    $null = Set-SimulatorFaults @{ newUnknownTagEnabled = $true }
    Wait-ForCondition -Description "mapping 1.0 rejects the new auxiliary temperature tag" -TimeoutSeconds 50 -Condition {
        return [long] (Get-DatabaseScalar "SELECT COUNT(*) FROM telemetry_rejection WHERE reason_code='UNKNOWN_SOURCE_TAG'") -gt $rejectionsBefore
    }
    Reset-SimulatorFaults
    $to = [DateTimeOffset]::UtcNow.AddMinutes(1)
    $auxBefore = [long] (Get-DatabaseScalar "SELECT COUNT(*) FROM telemetry_sample WHERE source_tag LIKE '%.TEMP_AUX_PV'")
    $cursorBeforeReplay = [long] (Get-CollectorStatus).sourceCursor
    $token = Get-TelemetryEnvValue "LOCAL_API_TOKEN" "local-development-token-change-me"
    $headers = @{ Authorization = "Bearer $token" }
    $body = @{
        facilityId = "facility-alpha"
        from = $from.ToString("o")
        to = $to.ToString("o")
        mappingVersion = "controller-a-mapping-1.1.0"
        qualityRulesVersion = "quality-rules-1.0.0"
        reason = "Demonstrate recovery after installing the auxiliary-temperature mapping"
    } | ConvertTo-Json -Compress
    $first = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/replays" -Headers $headers -ContentType "application/json" -Body $body
    Wait-ForCondition -Description "first replay completes with mapping 1.1" -TimeoutSeconds 150 -Condition {
        $run = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/replays/$($first.replayId)"
        return $run.status -eq "COMPLETED"
    }
    $auxAfterFirst = [long] (Get-DatabaseScalar "SELECT COUNT(*) FROM telemetry_sample WHERE source_tag LIKE '%.TEMP_AUX_PV'")
    Assert-Condition ($auxAfterFirst -gt $auxBefore) "previously rejected auxiliary observations become canonical"

    $second = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/replays" -Headers $headers -ContentType "application/json" -Body $body
    Wait-ForCondition -Description "repeat replay completes" -TimeoutSeconds 150 -Condition {
        $run = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/replays/$($second.replayId)"
        return $run.status -eq "COMPLETED"
    }
    $auxAfterSecond = [long] (Get-DatabaseScalar "SELECT COUNT(*) FROM telemetry_sample WHERE source_tag LIKE '%.TEMP_AUX_PV'")
    $secondDuplicateCount = [long] (Get-DatabaseScalar "SELECT duplicate_count FROM replay_run WHERE replay_id='$($second.replayId)'")
    Assert-Condition ($secondDuplicateCount -gt 0) "repeat replay recognizes already-canonical observations as duplicates"
    $duplicateCanonicalRows = [long] (Get-DatabaseScalar "SELECT COUNT(*) - COUNT(DISTINCT observation_id) FROM telemetry_sample WHERE source_tag LIKE '%.TEMP_AUX_PV'")
    Assert-Condition ($duplicateCanonicalRows -eq 0) "repeating replay creates no duplicate canonical samples"
    Assert-Condition ([long] (Get-CollectorStatus).sourceCursor -gt $cursorBeforeReplay) "live source acquisition continues while replay work is processed"
    Write-Host "Recovered auxiliary samples: $($auxAfterFirst - $auxBefore)"
    Write-Host "Additional unique auxiliary samples found by repeat replay: $($auxAfterSecond - $auxAfterFirst)"
    Write-ScenarioPass "unknown-tag-and-replay"
} finally {
    try { Reset-SimulatorFaults } catch {}
    Pop-Location
}
