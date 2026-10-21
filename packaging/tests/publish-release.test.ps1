$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$fixture = Join-Path $repo ('target\remediation-2026-10-18\release-plan-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $fixture 'packaging\lib'), (Join-Path $fixture 'dist'), (Join-Path $fixture 'notes'), (Join-Path $fixture 'other cwd') | Out-Null
Copy-Item -LiteralPath (Join-Path $repo 'packaging\publish-release.ps1') -Destination (Join-Path $fixture 'packaging')
Copy-Item -LiteralPath (Join-Path $repo 'packaging\lib\release-plan.ps1') -Destination (Join-Path $fixture 'packaging\lib')
$notes = Join-Path $fixture 'notes\说明 带 空格.md'
$completeNotes = @'
# [玩家 - 1.0.0][管理 - 1.2.3] 合成测试发布，禁止正式发布

## 更新教程

### 升级前备份
先完成正在执行的操作，再备份实际 data、management-settings.json、management-web-auth.json，保留签名身份与外部源目录。

### Windows 桌面安装版
通过连接页检查更新，或者完整退出旧程序后运行安装包，保留原来的实际管理数据目录。

### Windows 桌面便携版
解压整个程序文件夹，完整退出旧进程后替换程序文件，保留原来的 data 和外部目录。

### Windows / Linux 终端管理端
按原启动方式停止服务，替换对应平台的程序和运行环境，保留原设置、账户和数据后重新启动。

### 玩家端是否需要更新
本次玩家版本是否变化须在正式说明中明确；需要时在发放页面上传发行 ZIP、检查并确认发布。

### 升级后检查
检查程序版本、原有项目与发布历史、公共下载服务和本次修复，路径异常时先定位原来的数据目录。
'@
Set-Content -LiteralPath $notes -Value $completeNotes -Encoding utf8
if ($env:OS -eq 'Windows_NT' -and $PSVersionTable.PSVersion.Major -gt 5) {
    # Windows PowerShell reads BOM-less scripts as ANSI; exercise the real UTF-8
    # helper and notes in that runtime too, without any remote commands.
    $legacyPowerShell = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
    if (Test-Path -LiteralPath $legacyPowerShell -PathType Leaf) {
        $legacyProbe = Join-Path $fixture 'legacy-probe.ps1'
        $probeSource = @'
param([string]$Helper, [string]$Notes)
$ErrorActionPreference = 'Stop'
. $Helper
Assert-ReleaseNotes -NotesFile $Notes -AdminVersion 1.2.3 -PlayerVersion 1.0.0
'@
        [IO.File]::WriteAllText($legacyProbe, $probeSource, [Text.UTF8Encoding]::new($false))
        & $legacyPowerShell -NoProfile -NonInteractive -File $legacyProbe -Helper (Join-Path $fixture 'packaging\lib\release-plan.ps1') -Notes $notes
        if ($LASTEXITCODE -ne 0) { throw 'Windows PowerShell could not read the release tutorial validator' }
        Write-Output 'PASS: Windows PowerShell reads the UTF-8 tutorial validator and notes.'
    }
}
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
$badNotes = Join-Path $fixture 'notes\missing-tutorial.md'
Set-Content -LiteralPath $badNotes -Value '# [玩家 - 1.0.0][管理 - 1.2.3] 只有变更描述，没有更新教程' -Encoding utf8
Assert-Fails { & $script -Tag v1.2.3 -AdminVersion 1.2.3 -PlayerVersion 1.0.0 -NotesFile $badNotes -DryRun }
Set-Content -LiteralPath $badNotes -Value ($completeNotes -replace '(?ms)^### Windows 桌面便携版.*?(?=^### )', '') -Encoding utf8
Assert-Fails { & $script -Tag v1.2.3 -AdminVersion 1.2.3 -PlayerVersion 1.0.0 -NotesFile $badNotes -DryRun }
Set-Content -LiteralPath $badNotes -Value ($completeNotes -replace '(?ms)(^### 升级前备份\s*\n).*?(?=^### )', '$1') -Encoding utf8
Assert-Fails { & $script -Tag v1.2.3 -AdminVersion 1.2.3 -PlayerVersion 1.0.0 -NotesFile $badNotes -DryRun }
Set-Content -LiteralPath $badNotes -Value ($completeNotes.Replace('[管理 - 1.2.3]', '[管理 - 9.9.9]')) -Encoding utf8
Assert-Fails { & $script -Tag v1.2.3 -AdminVersion 1.2.3 -PlayerVersion 1.0.0 -NotesFile $badNotes -DryRun }
Assert-True ((Get-DistFingerprint) -eq $before) 'Invalid tutorials modified dist'
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
Write-Output 'PASS: 12 release planning scenarios, including missing/incomplete tutorials and wrong versions; no gh, git, download, publication or dist mutation.'
