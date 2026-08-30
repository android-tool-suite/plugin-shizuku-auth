[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$AssetDirectory,
    [Parameter(Mandatory)]
    [string]$Title,
    [string]$Tag = 'debug'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($env:GITHUB_REPOSITORY) -or
    [string]::IsNullOrWhiteSpace($env:GITHUB_SHA)) {
    throw 'GITHUB_REPOSITORY 和 GITHUB_SHA 必须存在'
}
$assets = @(Get-ChildItem -LiteralPath $AssetDirectory -File | Select-Object -ExpandProperty FullName)
if ($assets.Count -eq 0) { throw "调试发布目录为空：$AssetDirectory" }
$shortSha = $env:GITHUB_SHA.Substring(0, 7)
$snapshotTag = "$Tag-$($env:GITHUB_SHA.ToLowerInvariant())"

& gh release view $snapshotTag --repo $env:GITHUB_REPOSITORY *> $null
if ($LASTEXITCODE -ne 0) {
    & gh release create $snapshotTag @assets --repo $env:GITHUB_REPOSITORY `
        --target $env:GITHUB_SHA `
        --title "$Title 历史快照 ($shortSha)" `
        --notes "main 分支通过 CI 的不可变调试快照：$($env:GITHUB_SHA)" `
        --prerelease
    if ($LASTEXITCODE -ne 0) { throw '无法创建 debug 历史快照' }
}

& gh release view $Tag --repo $env:GITHUB_REPOSITORY *> $null
$releaseExists = $LASTEXITCODE -eq 0
if ($releaseExists) {
    & gh api --method PATCH `
        "repos/$($env:GITHUB_REPOSITORY)/git/refs/tags/$Tag" `
        -f "sha=$($env:GITHUB_SHA)" -F force=true --silent
    if ($LASTEXITCODE -ne 0) { throw '无法更新 debug 标签' }
    & gh release upload $Tag @assets --clobber --repo $env:GITHUB_REPOSITORY
    if ($LASTEXITCODE -ne 0) { throw '无法更新 debug 发布资产' }
    & gh release edit $Tag --repo $env:GITHUB_REPOSITORY `
        --title "$Title ($shortSha)" `
        --notes "main 分支最新通过 CI 的调试构建：$($env:GITHUB_SHA)" `
        --prerelease
    if ($LASTEXITCODE -ne 0) { throw '无法更新 debug 预发布' }
}
else {
    & gh release create $Tag @assets --repo $env:GITHUB_REPOSITORY `
        --target $env:GITHUB_SHA `
        --title "$Title ($shortSha)" `
        --notes "main 分支最新通过 CI 的调试构建：$($env:GITHUB_SHA)" `
        --prerelease
    if ($LASTEXITCODE -ne 0) { throw '无法创建 debug 预发布' }
}
