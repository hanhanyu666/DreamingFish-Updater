$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$fixture = Join-Path $repo ('target\remediation-2026-10-18\release-plan-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $fixture 'packaging\lib'), (Join-Path $fixture 'dist'), (Join-Path $fixture 'notes'), (Join-Path $fixture 'other cwd') | Out-Null
Copy-Item -LiteralPath (Join-Path $repo 'packaging\publish-release.ps1') -Destination (Join-Path $fixture 'packaging')
Copy-Item -LiteralPath (Join-Path $repo 'packaging\lib\release-plan.ps1') -Destination (Join-Path $fixture 'packaging\lib')
$notes = Join-Path $fixture 'notes\说明 带 空格.md'
Set-Content -LiteralPath $notes -Value 'Synthetic release notes; never publish.' -Encoding utf8
$dist = Join-Path $fixture 'dist'
Set-Content -LiteralPath (Join-Path $dist 'dfs-admin-windows-x64-1.2.3.zip') -Value 'synthetic asset'
Set-Content -LiteralPath (Join-Path $dist 'latest.json') -Value 'original latest, must remain untouched during carry preview'
Set-Content -LiteralPath (Join-Path $dist 'SHA256SUMS.txt') -Value 'original checksums'
function Assert-True($Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Assert-Fails([scriptblock]$Work) { $failed=$false; try { & $Work } catch { $failed=$true }; Assert-True $failed 'Expected a validation failure' }
function Get-DistFingerprint { (@(Get-ChildItem -LiteralPath $dist -File | Sort-Object Name | ForEach-Object { $_.Name + ':' + (Get-FileHash -LiteralPath $_.FullName).Hash }) -join '|') }
# Any accidental remote command is a test failure. These functions do not send messages or publish.
function gh { throw 'DryRun attempted gh' }
function git { throw 'DryRun attempted git' }
$script = Join-Path $fixture 'packaging\publish-release.ps1'
$before = Get-DistFingerprint
Push-Location $fixture
try { & $script -Tag v1.2.3 -AdminVersion 1.2.3 -PlayerVersion 1.0.0 -NotesFile 'notes\说明 带 空格.md' -DryRun } finally { Pop-Location }
Push-Location (Join-Path $fixture 'other cwd')
try { & $script -Tag v1.2.3 -AdminVersion 1.2.3 -PlayerVersion 1.0.0 -NotesFile '..\notes\说明 带 空格.md' -DryRun } finally { Pop-Location }
& $script -Tag v1.2.3 -AdminVersion 1.2.3 -PlayerVersion 1.0.0 -NotesFile $notes -DryRun
Assert-True ((Get-DistFingerprint) -eq $before) 'DryRun modified dist'
Assert-Fails { & $script -Tag v1.2.3 -AdminVersion 1.2.3 -PlayerVersion 1.0.0 -NotesFile (Join-Path $fixture 'missing.md') -DryRun }
$installer = 'dfs-admin-desktop-windows-x64-1.2.3-setup.exe'
Set-Content -LiteralPath (Join-Path $dist $installer) -Value 'synthetic installer'
Assert-Fails { & $script -Tag v1.2.3 -AdminVersion 1.2.3 -PlayerVersion 1.0.0 -NotesFile $notes -DryRun }
$manifest = @{version='1.2.3';platforms=@{'windows-x86_64'=@{url="https://github.com/hanhanyu666/DreamingFish-Updater/releases/download/v1.2.3/$installer";signature='synthetic-public-signature'}}}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $dist 'latest.json') -Encoding utf8
Assert-Fails { & $script -Tag v1.2.3 -AdminVersion 1.2.3 -PlayerVersion 1.0.0 -NotesFile $notes -DryRun }
Set-Content -LiteralPath (Join-Path $dist ($installer+'.sig')) -Value 'synthetic-public-signature'
$before = Get-DistFingerprint
& $script -Tag v1.2.3 -AdminVersion 1.2.3 -PlayerVersion 1.0.0 -NotesFile $notes -DryRun
Assert-True ((Get-DistFingerprint) -eq $before) 'Fresh manifest DryRun modified dist'
Assert-Fails { & $script -Tag v9.9.9 -AdminVersion 1.2.3 -PlayerVersion 1.0.0 -NotesFile $notes -DryRun }
Write-Output 'PASS: 8 release planning scenarios; no gh, git, download, publication or dist mutation.'
