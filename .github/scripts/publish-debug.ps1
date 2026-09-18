[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$AssetDirectory,
    [Parameter(Mandatory)][string]$Title,
    [Parameter(Mandatory)][string]$Tag
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($env:GITHUB_SHA -notmatch '^[0-9a-f]{40}$' -or
    $Tag -cne "debug-$($env:GITHUB_SHA)" -or
    [string]::IsNullOrWhiteSpace($env:GITHUB_REPOSITORY)) {
    throw 'Debug tag must be debug-<full commit SHA> and match the checked-out commit.'
}
$metadata = Get-Content (Join-Path $AssetDirectory 'release-metadata.json') -Raw | ConvertFrom-Json
if ($metadata.channel -ne 'debug' -or $metadata.commitSha -cne $env:GITHUB_SHA) {
    throw 'Debug metadata does not match the tag commit.'
}
$assets = @(Get-ChildItem -LiteralPath $AssetDirectory -File | Select-Object -ExpandProperty FullName)
& gh release create $Tag @assets --repo $env:GITHUB_REPOSITORY `
    --verify-tag --draft --prerelease --title "$Title ($($env:GITHUB_SHA.Substring(0, 7)))" `
    --notes "Manually tagged debug build: $($env:GITHUB_SHA)"
if ($LASTEXITCODE -ne 0) { throw 'Failed to create immutable debug release draft.' }
& gh release edit $Tag --repo $env:GITHUB_REPOSITORY --draft=false --prerelease --latest=false
if ($LASTEXITCODE -ne 0) { throw 'Failed to publish debug release.' }
