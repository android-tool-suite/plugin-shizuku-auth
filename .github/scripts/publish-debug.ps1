[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$AssetDirectory,
    [Parameter(Mandatory)][string]$Title,
    [Parameter(Mandatory)][string]$Tag
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($env:GITHUB_SHA -notmatch '^[0-9a-f]{40}$' -or
    $Tag -notmatch '^debug-v\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$' -or
    [string]::IsNullOrWhiteSpace($env:GITHUB_REPOSITORY)) {
    throw 'Debug tag must use debug-v<version>; build provenance must include a commit SHA.'
}
$metadata = Get-Content (Join-Path $AssetDirectory 'release-metadata.json') -Raw | ConvertFrom-Json
if ($metadata.channel -ne 'debug' -or $metadata.commitSha -cne $env:GITHUB_SHA -or
    $Tag -cne "debug-v$($metadata.versionName)") {
    throw 'Debug metadata does not match the tag version or build commit.'
}
$assets = @(Get-ChildItem -LiteralPath $AssetDirectory -File | Select-Object -ExpandProperty FullName)
& gh release create $Tag @assets --repo $env:GITHUB_REPOSITORY `
    --verify-tag --draft --prerelease --title "$Title v$($metadata.versionName)" `
    --notes "Manually tagged debug build: $($env:GITHUB_SHA)"
if ($LASTEXITCODE -ne 0) { throw 'Failed to create immutable debug release draft.' }
& gh release edit $Tag --repo $env:GITHUB_REPOSITORY --draft=false --prerelease --latest=false
if ($LASTEXITCODE -ne 0) { throw 'Failed to publish debug release.' }
