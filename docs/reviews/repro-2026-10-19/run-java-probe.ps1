param([string]$JdkHome = 'D:\Program Files\Java\jdk-21')
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$reviewRoot = Join-Path $repo ('target\review-2026-10-19\java-' + [guid]::NewGuid())
$classDir = Join-Path $repo ('target\review-2026-10-19\classes-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $classDir | Out-Null
$report = Get-ChildItem -LiteralPath (Join-Path $repo 'management-core\target\surefire-reports') -Filter 'TEST-*.xml' | Select-Object -First 1
[xml]$xml = Get-Content -LiteralPath $report.FullName -Raw
$classpath = ($xml.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
& (Join-Path $JdkHome 'bin\javac.exe') -encoding UTF-8 -cp $classpath -d $classDir (Join-Path $PSScriptRoot 'RemediationReviewProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'Probe compilation failed' }
Push-Location $repo
try {
    & (Join-Path $JdkHome 'bin\java.exe') '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp ($classDir+';'+$classpath) cn.dreamingfish.updater.management.RemediationReviewProbe $reviewRoot
    if ($LASTEXITCODE -ne 0) { throw 'Probe execution failed' }
} finally { Pop-Location }
