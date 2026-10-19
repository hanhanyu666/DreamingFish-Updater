param([string]$JdkHome = 'D:\Program Files\Java\jdk-21')
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$output = Join-Path $repo ('target\review-2026-10-14\run-' + [guid]::NewGuid())
$classes = Join-Path $output 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$classpaths = foreach ($module in @('update-engine', 'management-core')) {
    $report = Get-ChildItem -LiteralPath (Join-Path $repo "$module\target\surefire-reports") -Filter 'TEST-*.xml' | Select-Object -First 1
    [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw
    ($xml.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
}
$classpath = (($classpaths -join ';').Split(';') | Select-Object -Unique) -join ';'
$sources = Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*ReviewProbe.java' | ForEach-Object FullName
& (Join-Path $JdkHome 'bin\javac.exe') -encoding UTF-8 -cp $classpath -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Probe compilation failed' }
foreach ($probe in @('cn.dreamingfish.updater.engine.EngineReviewProbe', 'cn.dreamingfish.updater.management.ManagementReviewProbe')) {
    & (Join-Path $JdkHome 'bin\java.exe') '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp ($classes + ';' + $classpath) $probe $output | Tee-Object -FilePath (Join-Path $output 'observations.txt') -Append
    if ($LASTEXITCODE -ne 0) { throw "Probe execution failed: $probe" }
}
& node (Join-Path $PSScriptRoot 'workbench-probe.mjs') | Tee-Object -FilePath (Join-Path $output 'observations.txt') -Append
if ($LASTEXITCODE -ne 0) { throw 'Workbench probe failed' }
Write-Output "Probe artifacts: $output"
