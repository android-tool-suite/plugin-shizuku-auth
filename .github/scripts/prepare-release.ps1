[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$ArtifactPath,
    [Parameter(Mandatory)]
    [string]$OutputDirectory,
    [string]$ExpectedTag = '',
    [ValidateSet('release', 'debug')]
    [string]$Channel = 'release',
    [string]$CommitSha = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$artifact = Get-Item -LiteralPath $ArtifactPath
$archive = [IO.Compression.ZipFile]::OpenRead($artifact.FullName)
try {
    $entry = $archive.GetEntry('manifest.json')
    if ($null -eq $entry) { throw '插件包缺少 manifest.json' }
    $reader = [IO.StreamReader]::new($entry.Open(), [Text.Encoding]::UTF8)
    try { $manifest = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
}
finally {
    $archive.Dispose()
}

$plugin = $manifest.plugin
if ([int]$manifest.formatVersion -ne 3) { throw 'Release 插件必须使用 formatVersion 3' }
if ($Channel -eq 'release') {
    if ($ExpectedTag -ne "v$($plugin.version)") {
        throw "标签 $ExpectedTag 与插件版本 $($plugin.version) 不一致"
    }
    if (-not (Select-String -LiteralPath (Join-Path $repositoryRoot 'CHANGELOG.md') -SimpleMatch $plugin.version -Quiet)) {
        throw "CHANGELOG.md 缺少 $($plugin.version)"
    }
}
elseif ($CommitSha -notmatch '^[0-9a-fA-F]{40}$') {
    throw 'Debug 发布必须提供完整的 commit SHA'
}

$compatibility = $null
$compatibilityPath = Join-Path $repositoryRoot 'data-compatibility.json'
if (Test-Path -LiteralPath $compatibilityPath -PathType Leaf) {
    $compatibility = Get-Content -LiteralPath $compatibilityPath -Raw -Encoding utf8 | ConvertFrom-Json
    $dataFormatVersion = [int]$compatibility.dataFormatVersion
    $minReadableDataFormatVersion = [int]$compatibility.minReadableDataFormatVersion
    $maxReadableDataFormatVersion = [int]$compatibility.maxReadableDataFormatVersion
    if ($compatibility.schemaVersion -ne 1 -or
        $minReadableDataFormatVersion -lt 0 -or
        $minReadableDataFormatVersion -gt $dataFormatVersion -or
        $dataFormatVersion -gt $maxReadableDataFormatVersion) {
        throw 'data-compatibility.json 中的数据兼容范围无效'
    }
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$targetArtifact = Join-Path $OutputDirectory $artifact.Name
Copy-Item -LiteralPath $artifact.FullName -Destination $targetArtifact -Force
$targetFile = Get-Item -LiteralPath $targetArtifact
$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $targetArtifact).Hash.ToLowerInvariant()
$repositoryUrl = if ($env:GITHUB_REPOSITORY) {
    "https://github.com/$($env:GITHUB_REPOSITORY)"
} else {
    'https://github.com/android-tool-suite/plugin-shizuku-auth'
}
$dependencies = @($manifest.requires.plugins | ForEach-Object { $_.id })

$metadata = [ordered]@{
    schemaVersion = 1
    type = 'plugin'
    channel = $Channel
    id = $plugin.id
    title = $plugin.title
    description = $plugin.description
    author = $plugin.publisher
    publisher = $plugin.publisher
    kind = $plugin.kind
    repositoryUrl = $repositoryUrl
    versionName = $plugin.version
    versionCode = [int]$plugin.versionCode
    minHostVersionCode = [int]$plugin.minHostVersionCode
    dependencies = $dependencies
    artifactName = $targetFile.Name
}
if ($null -ne $compatibility) {
    $metadata.dataCompatibility = [ordered]@{
        schemaVersion = 1
        dataFormatVersion = [int]$compatibility.dataFormatVersion
        minReadableDataFormatVersion = [int]$compatibility.minReadableDataFormatVersion
        maxReadableDataFormatVersion = [int]$compatibility.maxReadableDataFormatVersion
    }
}
if ($Channel -eq 'debug') {
    $metadata.commitSha = $CommitSha.ToLowerInvariant()
}
$metadata | ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath (Join-Path $OutputDirectory 'release-metadata.json') -Encoding utf8
"$hash  $($targetFile.Name)" |
    Set-Content -LiteralPath (Join-Path $OutputDirectory 'SHA256SUMS.txt') -Encoding ascii
