$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../lib/validation-network.ps1')
$cases=@(
    @{Text='Could not transfer artifact: Connection reset [Help 1] PluginResolutionException';Expected=$true},
    @{Text='Could not transfer artifact: Read timed out [Help 1] DependencyResolutionException';Expected=$true},
    @{Text='Could not transfer artifact: 404 Not Found PluginResolutionException';Expected=$false},
    @{Text='HTTP Connection reset; Errors: 2; MojoFailureException';Expected=$false},
    @{Text='Could not transfer artifact Connection reset PluginResolutionException; Failures: 1';Expected=$false},
    @{Text='Compilation failure: cannot find symbol';Expected=$false}
)
foreach($case in $cases){if((Test-MavenTransportFailure $case.Text)-ne $case.Expected){throw "Unexpected retry classification: $($case.Text)"}}
Write-Output 'PASS: transport failures are retryable; test, compiler and permanent resolution failures are not.'

# Exercise the real retry loop with child processes, without downloading anything.
$syntaxTokens=$null;$syntaxErrors=$null
$syntax=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot '../validate.ps1'),[ref]$syntaxTokens,[ref]$syntaxErrors)
if($syntaxErrors.Count){throw 'Validation script has syntax errors'}
$definition=$syntax.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-ValidationStep'},$true)
Invoke-Expression $definition.Extent.Text
$validationRoot=Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) ('target/network-retry-test-'+[guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($validationRoot)|Out-Null
$stub=Join-Path $validationRoot 'transport.py'
@'
import pathlib,sys
mode,state=sys.argv[1:3]
path=pathlib.Path(state)
attempt=int(path.read_text())+1 if path.exists() else 1
path.write_text(str(attempt))
if mode=='transport' and attempt==2:
    assert '-U' in sys.argv
    print('resolved')
    sys.exit(0)
if mode=='test': print('Errors: 1; HTTP Connection reset; MojoFailureException')
elif mode=='missing': print('Could not transfer artifact: 404 Not Found; PluginResolutionException')
else: print('Could not transfer artifact: Connection reset; PluginResolutionException')
sys.exit(1)
'@ | Set-Content -LiteralPath $stub -Encoding utf8
$previousActions=$env:GITHUB_ACTIONS
try{
    $env:GITHUB_ACTIONS='false'
    $singleStub=Join-Path $validationRoot 'single.py'
    'import sys; assert len(sys.argv) == 1; print("single argument preserved")' | Set-Content -LiteralPath $singleStub -Encoding utf8
    $validationSteps=[Collections.Generic.List[object]]::new()
    Invoke-ValidationStep 'single-argument' 'python' @($singleStub)
    foreach($case in @(@{Mode='transport';Count=2;Pass=$true},@{Mode='test';Count=1;Pass=$false},@{Mode='missing';Count=1;Pass=$false},@{Mode='persistent';Count=3;Pass=$false})){
        $validationSteps=[Collections.Generic.List[object]]::new()
        $state=Join-Path $validationRoot ($case.Mode+'.count')
        $passed=$true
        try{Invoke-ValidationStep $case.Mode 'python' @($stub,$case.Mode,$state) -RetryTransport}catch{$passed=$false}
        if($passed-ne $case.Pass -or [int](Get-Content -LiteralPath $state)-ne $case.Count){throw "Incorrect retry behavior: $($case.Mode)"}
    }
}finally{$env:GITHUB_ACTIONS=$previousActions}
Write-Output 'PASS: retry loop refreshes failed downloads, stops after three attempts, and never retries test failures.'
