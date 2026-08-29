[CmdletBinding()]
param(
    [string] $Snapshot = (Join-Path (Resolve-Path (Join-Path $PSScriptRoot "..")).Path "contracts/openapi/telemetry-api-v1.json")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$runtime = (Invoke-WebRequest -Uri "http://localhost:8080/v3/api-docs" -UseBasicParsing -TimeoutSec 15).Content.Trim()
$expected = (Get-Content -Raw $Snapshot).Trim()
if ($runtime -cne $expected) {
    throw "OpenAPI drift detected. Review the API change, then run scripts/export-openapi.ps1 to update the approved snapshot."
}
Write-Host "ASSERT PASS: runtime OpenAPI exactly matches contracts/openapi/telemetry-api-v1.json" -ForegroundColor Green
