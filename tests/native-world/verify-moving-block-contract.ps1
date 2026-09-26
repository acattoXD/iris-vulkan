param([Parameter(Mandatory = $true)][string]$JdkPath, [string]$RuntimeClassPath = 'build/isolated-runtime-classpath.txt')
$ErrorActionPreference = 'Stop'
$movingProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$movingOutput = Join-Path $movingProject 'build/moving-block-contract-tests'
New-Item -ItemType Directory -Path $movingOutput -Force | Out-Null
$movingRuntime = if ([IO.Path]::IsPathRooted($RuntimeClassPath)) { $RuntimeClassPath } else { Join-Path $movingProject $RuntimeClassPath }
$movingEntries = @($movingOutput)
foreach ($relative in @('common/build/classes/java/main','common/build/classes/java/api','common/build/classes/java/vendored')) { $movingEntries += Join-Path $movingProject $relative }
$movingEntries += Get-Content -LiteralPath $movingRuntime
$movingClassPath = ($movingEntries | ForEach-Object { $_.Replace('\','/') }) -join ';'
$movingCompile = @('-proc:none','-classpath',('"' + $movingClassPath + '"'),'-d',('"' + $movingOutput.Replace('\','/') + '"'),('"' + (Join-Path $PSScriptRoot 'IrisVulkanMovingBlockContractTest.java').Replace('\','/') + '"'))
$movingCompileFile = Join-Path $movingOutput 'compile.args'; [IO.File]::WriteAllLines($movingCompileFile,$movingCompile)
& (Join-Path $JdkPath 'bin/javac.exe') "@$movingCompileFile"
if ($LASTEXITCODE -ne 0) { throw 'Moving-block contract compilation failed' }
$movingRun = Join-Path $movingOutput 'run.args'
[IO.File]::WriteAllLines($movingRun,@('--enable-native-access=ALL-UNNAMED','--sun-misc-unsafe-memory-access=allow','-classpath',('"' + $movingClassPath + '"'),'net.irisshaders.iris.vulkan.IrisVulkanMovingBlockContractTest'))
& (Join-Path $JdkPath 'bin/java.exe') "@$movingRun"
if ($LASTEXITCODE -ne 0) { throw 'Moving-block contract failed' }
