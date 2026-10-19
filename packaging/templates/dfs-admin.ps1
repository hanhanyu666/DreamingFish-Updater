[CmdletBinding(PositionalBinding = $false)]
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$AdminArguments)
$ErrorActionPreference = 'Stop'
$heap = 512
if ($env:DFS_JAVA_HEAP_MB) {
    if (-not [int]::TryParse($env:DFS_JAVA_HEAP_MB, [ref]$heap) -or $heap -lt 128 -or $heap -gt 16384) {
        throw 'DFS_JAVA_HEAP_MB must be an integer between 128 and 16384.'
    }
}
$javaArguments = @('-Xms64m', "-Xmx${heap}m", '-Dfile.encoding=UTF-8', '-Dstdin.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', "-Ddfs.home=$PSScriptRoot", '-jar', (Join-Path $PSScriptRoot 'app\dfs-admin.jar'))
& (Join-Path $PSScriptRoot 'runtime\bin\java.exe') @javaArguments @AdminArguments
exit $LASTEXITCODE
