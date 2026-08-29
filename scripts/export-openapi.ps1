[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$targetDirectory = Join-Path $repoRoot "contracts/openapi"
$target = Join-Path $targetDirectory "telemetry-api-v1.json"
New-Item -ItemType Directory -Path $targetDirectory -Force | Out-Null
$content = (Invoke-WebRequest -Uri "http://localhost:8080/v3/api-docs" -UseBasicParsing -TimeoutSec 15).Content.Trim()
[IO.File]::WriteAllText($target, $content, [Text.UTF8Encoding]::new($false))
Write-Host "Updated $target"
