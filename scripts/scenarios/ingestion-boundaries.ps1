[CmdletBinding()]
param([string] $EnvFile)

. (Join-Path $PSScriptRoot "../lib/common.ps1")
if ($EnvFile) { Use-TelemetryEnvironment $EnvFile }

function New-BoundaryBatch {
    param(
        [Parameter(Mandatory)] [string] $BatchId,
        [Parameter(Mandatory)] [string] $SourceEpoch,
        [Parameter(Mandatory)] [double] $Value,
        [string] $Unit = "degC"
    )

    $now = [DateTimeOffset]::UtcNow
    $body = [ordered]@{
        contractVersion = "raw-observation.batch.v1"
        batchId = $BatchId
        collectorId = "boundary-probe-01"
        collectorVersion = "1.0.0"
        facilityId = "facility-alpha"
        createdAt = $now.ToString("o")
        observations = @([ordered]@{
            sourceSystem = "boundary-probe"
            sourceEpoch = $SourceEpoch
            sourceSequence = 1
            sourceTag = "CTRL_A.ZONE[07].TEMP_PV"
            observedAt = $now.AddSeconds(-1).ToString("o")
            rawValue = $Value
            rawUnit = $Unit
            sourceQualityCode = 192
        })
    }
    $jsonBytes = [Text.UTF8Encoding]::new($false).GetBytes(($body | ConvertTo-Json -Depth 5 -Compress))
    $stream = [IO.MemoryStream]::new()
    $gzip = [IO.Compression.GZipStream]::new($stream, [IO.Compression.CompressionMode]::Compress, $true)
    try { $gzip.Write($jsonBytes, 0, $jsonBytes.Length) } finally { $gzip.Dispose() }
    $bytes = $stream.ToArray()
    $stream.Dispose()
    $hash = [Security.Cryptography.SHA256]::HashData($bytes)
    return [pscustomobject]@{
        BatchId = $BatchId
        Bytes = $bytes
        ContentDigest = "sha-256=:$([Convert]::ToBase64String($hash)):"
    }
}

function New-BatchRequest {
    param(
        [Parameter(Mandatory)] $Batch,
        [bool] $FailAfterRawStore = $false
    )
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Post, "http://localhost:8080/api/v1/ingestion/batches")
    $request.Headers.TryAddWithoutValidation("Authorization", "Bearer $(Get-TelemetryEnvValue 'LOCAL_API_TOKEN' 'local-development-token-change-me')") | Out-Null
    $request.Headers.TryAddWithoutValidation("Content-Digest", $Batch.ContentDigest) | Out-Null
    if ($FailAfterRawStore) { $request.Headers.TryAddWithoutValidation("X-Lab-Fail-After-Raw-Store", "true") | Out-Null }
    $request.Content = [Net.Http.ByteArrayContent]::new([byte[]] $Batch.Bytes)
    $request.Content.Headers.ContentType = [Net.Http.Headers.MediaTypeHeaderValue]::new("application/json")
    $request.Content.Headers.ContentEncoding.Add("gzip")
    return $request
}

function Send-BoundaryBatch {
    param(
        [Parameter(Mandatory)] [Net.Http.HttpClient] $Client,
        [Parameter(Mandatory)] $Batch,
        [bool] $FailAfterRawStore = $false
    )
    $request = New-BatchRequest -Batch $Batch -FailAfterRawStore $FailAfterRawStore
    try {
        $response = $Client.SendAsync($request).GetAwaiter().GetResult()
        return [pscustomobject]@{
            StatusCode = [int] $response.StatusCode
            Body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        }
    } finally {
        $request.Dispose()
    }
}

Write-ScenarioHeader "ingestion boundaries"
$client = [Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(35)
try {
    Wait-ForCondition -Description "outbox is initially drained" -TimeoutSeconds 60 -Condition {
        return [long] (Get-DatabaseScalar "SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL") -eq 0
    }

    $sameBatch = New-BoundaryBatch -BatchId ([guid]::NewGuid().ToString()) -SourceEpoch ([guid]::NewGuid().ToString()) -Value 21.5
    $requests = @(
        New-BatchRequest -Batch $sameBatch
        New-BatchRequest -Batch $sameBatch
    )
    $tasks = @($requests | ForEach-Object { $client.SendAsync($_) })
    [Threading.Tasks.Task]::WaitAll([Threading.Tasks.Task[]] $tasks)
    $statuses = @($tasks | ForEach-Object { [int] $_.Result.StatusCode })
    $requests | ForEach-Object { $_.Dispose() }
    Assert-Condition ($statuses.Count -eq 2 -and @($statuses | Where-Object { $_ -ne 202 }).Count -eq 0) "concurrent identical uploads both receive idempotent acceptance"
    Assert-Condition ([long] (Get-DatabaseScalar "SELECT COUNT(*) FROM ingestion_batch WHERE batch_id='$($sameBatch.BatchId)'::uuid") -eq 1) "concurrent identical uploads create one manifest"
    Assert-Condition ([long] (Get-DatabaseScalar "SELECT COUNT(*) FROM outbox_event WHERE batch_id='$($sameBatch.BatchId)'::uuid") -eq 1) "concurrent identical uploads create one outbox event"

    $differentBytes = New-BoundaryBatch -BatchId $sameBatch.BatchId -SourceEpoch ([guid]::NewGuid().ToString()) -Value 22.5
    $conflict = Send-BoundaryBatch -Client $client -Batch $differentBytes
    Assert-Condition ($conflict.StatusCode -eq 409 -and $conflict.Body -match 'BATCH_CHECKSUM_CONFLICT') "same batch ID with different bytes returns a deterministic checksum conflict"

    $orphanBatch = New-BoundaryBatch -BatchId ([guid]::NewGuid().ToString()) -SourceEpoch ([guid]::NewGuid().ToString()) -Value 23.5
    $injected = Send-BoundaryBatch -Client $client -Batch $orphanBatch -FailAfterRawStore $true
    Assert-Condition ($injected.StatusCode -eq 500) "injected failure occurs after raw-object persistence and before manifest commit"
    Assert-Condition ([long] (Get-DatabaseScalar "SELECT COUNT(*) FROM ingestion_batch WHERE batch_id='$($orphanBatch.BatchId)'::uuid") -eq 0) "injected raw-write crash window leaves no partial manifest"
    $token = Get-TelemetryEnvValue "LOCAL_API_TOKEN" "local-development-token-change-me"
    $reconciliation = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/admin/raw-objects/reconciliation" -Headers @{ Authorization = "Bearer $token" }
    Assert-Condition (($reconciliation.orphanObjects -join ',') -match $orphanBatch.BatchId) "reconciliation exposes the orphan object"
    $repair = Send-BoundaryBatch -Client $client -Batch $orphanBatch
    Assert-Condition ($repair.StatusCode -eq 202) "same-byte retry repairs the orphaned raw write"
    Assert-Condition ([long] (Get-DatabaseScalar "SELECT COUNT(*) FROM ingestion_batch WHERE batch_id='$($orphanBatch.BatchId)'::uuid") -eq 1) "orphan repair commits one manifest"

    Wait-ForCondition -Description "outbox drains before confirm-gap injection" -TimeoutSeconds 60 -Condition {
        return [long] (Get-DatabaseScalar "SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL") -eq 0
    }
    $rejectedBatch = New-BoundaryBatch -BatchId ([guid]::NewGuid().ToString()) -SourceEpoch ([guid]::NewGuid().ToString()) -Value 24.5 -Unit "invalid-unit"
    $faultBody = @{ armed = $true; batchId = $rejectedBatch.BatchId } | ConvertTo-Json -Compress
    Invoke-RestMethod -Method Put -Uri "http://localhost:8080/api/v1/admin/faults/outbox-confirm-gap" -Headers @{ Authorization = "Bearer $token" } -ContentType "application/json" -Body $faultBody | Out-Null
    $accepted = Send-BoundaryBatch -Client $client -Batch $rejectedBatch
    Assert-Condition ($accepted.StatusCode -eq 202) "confirm-gap probe reaches the gateway durability boundary"
    Wait-ForCondition -Description "confirmed-but-uncommitted outbox event is republished and processed twice" -TimeoutSeconds 90 -Condition {
        return [long] (Get-DatabaseScalar "SELECT processing_attempt_count FROM ingestion_batch WHERE batch_id='$($rejectedBatch.BatchId)'::uuid") -ge 2
    }
    Assert-Condition ([long] (Get-DatabaseScalar "SELECT COUNT(*) FROM telemetry_rejection WHERE batch_id='$($rejectedBatch.BatchId)'::uuid AND reason_code='UNSUPPORTED_RAW_UNIT'") -eq 1) "duplicate rejected delivery does not multiply the rejection audit row"
    Assert-Condition ([long] (Get-DatabaseScalar "SELECT COUNT(*) FROM outbox_event WHERE batch_id='$($rejectedBatch.BatchId)'::uuid AND published_at IS NOT NULL") -eq 1) "outbox event eventually records a successful confirmed publication"

    $finalReconciliation = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/admin/raw-objects/reconciliation" -Headers @{ Authorization = "Bearer $token" }
    Assert-Condition ([bool] $finalReconciliation.healthy) "raw-object reconciliation is healthy after crash-window repair"
    Write-ScenarioPass "ingestion boundaries"
} finally {
    $client.Dispose()
}
