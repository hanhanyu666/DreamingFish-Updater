<#
Publishes a GitHub release from dist/ and always attaches latest.json.

Installed desktop copies look for updates in releases/latest/download/latest.json, so every
release must carry it. When this release has a new desktop build, the latest.json produced by
build-admin-desktop.ps1 is used; otherwise the previous release's latest.json is carried over.

  .\packaging\publish-release.ps1 -Tag v0.2.4 -NotesFile release-notes.md
  .\packaging\publish-release.ps1 -Tag v0.2.4 -NotesFile release-notes.md -DryRun

Push the commit and the annotated tag first. Assets: desktop installer and portable zip, admin
terminal zips, player zip, latest.json and SHA256SUMS.txt (those present in dist/).
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Tag,
    [Parameter(Mandatory = $true)][string]$NotesFile,
    [string]$AdminVersion = '',
    [string]$PlayerVersion = '',
    [string]$Repository = 'hanhanyu666/DreamingFish-Updater',
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $repoRoot 'dist'

if (-not $AdminVersion) {
    $AdminVersion = (Get-Content -LiteralPath (Join-Path $repoRoot 'admin-desktop\package.json') -Raw | ConvertFrom-Json).version
}
if (-not $PlayerVersion) {
    $runtime = Get-Content -LiteralPath (Join-Path $repoRoot 'player-app\src\main\java\cn\dreamingfish\updater\player\PlayerRuntime.java') -Raw
    $PlayerVersion = [regex]::Match($runtime, 'VERSION\s*=\s*"([^"]+)"').Groups[1].Value
}
if (-not (Test-Path -LiteralPath $NotesFile -PathType Leaf)) { throw "Release notes not found: $NotesFile" }

$gh = (Get-Command gh -ErrorAction SilentlyContinue).Source
if (-not $gh) { $gh = Join-Path $env:LOCALAPPDATA 'Programs\GitHubCLI\bin\gh.exe' }
if (-not (Test-Path -LiteralPath $gh)) { throw 'GitHub CLI (gh) is required' }
if (-not (& git -C $repoRoot ls-remote --tags origin "refs/tags/$Tag")) { throw "Push the tag $Tag before publishing" }

$installer = "dfs-admin-desktop-windows-x64-$AdminVersion-setup.exe"
$candidates = @(
    "dfs-admin-desktop-windows-x64-$AdminVersion.zip",
    $installer,
    "dfs-admin-linux-x64-$AdminVersion.zip",
    "dfs-admin-windows-x64-$AdminVersion.zip",
    "dreamingfish-player-windows-x64-$PlayerVersion.zip"
)
$assets = @($candidates | Where-Object { Test-Path -LiteralPath (Join-Path $dist $_) -PathType Leaf })
if (-not $assets) { throw "No release files for admin $AdminVersion / player $PlayerVersion in $dist" }

# latest.json: this release's desktop build, or the previous release's file carried over.
$latest = Join-Path $dist 'latest.json'
$fresh = $false
if ((Test-Path -LiteralPath $latest) -and ($assets -contains $installer)) {
    $manifest = Get-Content -LiteralPath $latest -Raw -Encoding utf8 | ConvertFrom-Json
    if ($manifest.version -eq $AdminVersion) {
        $expected = "https://github.com/$Repository/releases/download/$Tag/$installer"
        if ($manifest.platforms.'windows-x86_64'.url -ne $expected) {
            throw "latest.json points to $($manifest.platforms.'windows-x86_64'.url); rebuild the desktop with -ReleaseTag $Tag"
        }
        $fresh = $true
    }
}
if (-not $fresh) {
    $carried = Join-Path ([IO.Path]::GetTempPath()) ('dfs-latest-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $carried | Out-Null
    & $gh release download --repo $Repository --pattern latest.json --dir $carried
    if ($LASTEXITCODE -ne 0) { throw 'Could not download latest.json from the current latest release' }
    Copy-Item -LiteralPath (Join-Path $carried 'latest.json') -Destination $latest -Force
    Remove-Item -LiteralPath $carried -Recurse -Force
    Write-Host 'No new desktop build in this release; carried over the previous latest.json.'
}
$assets += 'latest.json'

$sums = foreach ($name in $assets) {
    "$((Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $dist $name)).Hash.ToLowerInvariant())  $name"
}
[IO.File]::WriteAllText((Join-Path $dist 'SHA256SUMS.txt'), (($sums -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
$assets += 'SHA256SUMS.txt'

Write-Host "Release $Tag (admin $AdminVersion, player $PlayerVersion):"
$assets | ForEach-Object { Write-Host "  $_" }
if ($DryRun) { return }

Push-Location $dist
try {
    & $gh release create $Tag --repo $Repository --verify-tag --latest --title "DreamingFish Updater $Tag" --notes-file (Resolve-Path -LiteralPath $NotesFile).Path @assets
    if ($LASTEXITCODE -ne 0) { throw 'gh release create failed' }
} finally { Pop-Location }

# Check what installed copies will actually read.
$check = Join-Path ([IO.Path]::GetTempPath()) ('dfs-latest-check-' + [Guid]::NewGuid().ToString('N') + '.json')
Invoke-WebRequest -UseBasicParsing -Uri "https://github.com/$Repository/releases/latest/download/latest.json" -OutFile $check
if ((Get-FileHash -LiteralPath $check).Hash -ne (Get-FileHash -LiteralPath $latest).Hash) { throw 'releases/latest/download/latest.json does not match the uploaded file' }
$url = (Get-Content -LiteralPath $check -Raw -Encoding utf8 | ConvertFrom-Json).platforms.'windows-x86_64'.url
Remove-Item -LiteralPath $check -Force
$head = Invoke-WebRequest -UseBasicParsing -Method Head -Uri $url
Write-Host "Update check OK: latest.json is served and $url answers $($head.StatusCode)."
