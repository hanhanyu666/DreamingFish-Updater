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

# Resolve user paths before any work-directory change; planning never rewrites dist.
. (Join-Path $PSScriptRoot 'lib\release-plan.ps1')
$plan = Get-ReleasePlan -Dist $dist -NotesFile $NotesFile -Tag $Tag -Repository $Repository -AdminVersion $AdminVersion -PlayerVersion $PlayerVersion
Write-Host "Release $Tag (admin $AdminVersion, player $PlayerVersion):"
$plan.Assets | ForEach-Object { Write-Host "  $([IO.Path]::GetFileName($_))" }
Write-Host '  latest.json'
Write-Host '  SHA256SUMS.txt'
Write-Host "Notes: $($plan.Notes)"
if ($plan.CarryLatest) { Write-Host 'Manifest decision: carry the previous release latest.json; remote contents are checked only during execution.' }
else { Write-Host 'Manifest decision: use the matching signed desktop manifest.' }
if ($DryRun) { Write-Host 'DryRun: no remote mutation, download, staging file or dist manifest was written.'; return }

$ghCommand = Get-Command gh -ErrorAction SilentlyContinue
$gh = if ($ghCommand) { $ghCommand.Source } else { Join-Path $env:LOCALAPPDATA 'Programs\GitHubCLI\bin\gh.exe' }
if (-not (Test-Path -LiteralPath $gh)) { throw 'GitHub CLI (gh) is required' }
if (-not (& git -C $repoRoot ls-remote --tags origin "refs/tags/$Tag")) { throw "Push the tag $Tag before publishing" }

$stagingParent = Join-Path $repoRoot 'target'
New-Item -ItemType Directory -Path $stagingParent -Force | Out-Null
$staging = Join-Path $stagingParent ('release-upload-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $staging | Out-Null
try {
    $latest = $plan.Latest
    if ($plan.CarryLatest) {
        & $gh release download --repo $Repository --pattern latest.json --dir $staging
        if ($LASTEXITCODE -ne 0) { throw 'Could not read latest.json from the current latest release' }
        $latest = Join-Path $staging 'latest.json'
        $manifest = Get-Content -LiteralPath $latest -Raw -Encoding utf8 | ConvertFrom-Json
        $carried = $manifest.platforms.'windows-x86_64'
        if (-not $carried.signature -or $carried.url -notlike "https://github.com/$Repository/releases/download/*") { throw 'Carried latest.json has an unexpected signature or artifact URL' }
    }
    $assets = @($plan.Assets) + @($latest)
    $sums = foreach ($path in $assets) { "$((Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant())  $([IO.Path]::GetFileName($path))" }
    $sumsFile = Join-Path $staging 'SHA256SUMS.txt'
    [IO.File]::WriteAllText($sumsFile, (($sums -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
    $assets += $sumsFile
    & $gh release create $Tag --repo $Repository --verify-tag --latest --title "DreamingFish Updater $Tag" --notes-file $plan.Notes @assets
    if ($LASTEXITCODE -ne 0) { throw 'gh release create failed' }
    $check = Join-Path $staging 'served-latest.json'
    Invoke-WebRequest -UseBasicParsing -Uri "https://github.com/$Repository/releases/latest/download/latest.json" -OutFile $check
    if ((Get-FileHash -LiteralPath $check).Hash -ne (Get-FileHash -LiteralPath $latest).Hash) { throw 'Served latest.json does not match the uploaded file' }
    $url = (Get-Content -LiteralPath $check -Raw -Encoding utf8 | ConvertFrom-Json).platforms.'windows-x86_64'.url
    $head = Invoke-WebRequest -UseBasicParsing -Method Head -Uri $url
    Write-Host "Update check OK: latest.json and its artifact answer $($head.StatusCode)."
} finally {
    $absolute = [IO.Path]::GetFullPath($staging)
    $boundary = [IO.Path]::GetFullPath($stagingParent).TrimEnd('\') + '\'
    if (-not $absolute.StartsWith($boundary, [StringComparison]::OrdinalIgnoreCase) -or ((Get-Item -LiteralPath $absolute -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Unsafe release staging cleanup path' }
    Remove-Item -LiteralPath $absolute -Recurse -Force
}
