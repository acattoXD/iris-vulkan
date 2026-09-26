param(
    [Parameter(Mandatory = $true)][string]$JdkPath,
    [Parameter(Mandatory = $true)][string]$ShaderPack,
    [string]$RuntimeClassPath = 'build/isolated-runtime-classpath.txt'
)
$ErrorActionPreference = 'Stop'
$mineekProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$mineekOutput = Join-Path $mineekProject 'build/mineek-contract-tests'
New-Item -ItemType Directory -Path $mineekOutput -Force | Out-Null
$mineekRuntime = if ([IO.Path]::IsPathRooted($RuntimeClassPath)) { $RuntimeClassPath } else { Join-Path $mineekProject $RuntimeClassPath }
$mineekEntries = @($mineekOutput)
foreach ($relative in @('common/build/classes/java/main', 'common/build/classes/java/api', 'common/build/classes/java/vendored')) { $mineekEntries += Join-Path $mineekProject $relative }
$mineekEntries += Get-Content -LiteralPath $mineekRuntime
$mineekClassPath = ($mineekEntries | ForEach-Object { $_.Replace('\', '/') }) -join ';'
$mineekSources = @('tests/native-world/mineek-stubs/Iris.java', 'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanShaderResources.java', 'tests/native-world/MineekShaderContractTest.java')
$mineekCompile = @('-proc:none', '-encoding', 'UTF-8', '-classpath', ('"' + $mineekClassPath + '"'), '-d', ('"' + $mineekOutput.Replace('\', '/') + '"'))
$mineekCompile += $mineekSources | ForEach-Object { '"' + (Join-Path $mineekProject $_).Replace('\', '/') + '"' }
$mineekCompileFile = Join-Path $mineekOutput 'compile.args'
[IO.File]::WriteAllLines($mineekCompileFile, $mineekCompile)
& (Join-Path $JdkPath 'bin/javac.exe') "@$mineekCompileFile"
if ($LASTEXITCODE -ne 0) { throw 'Mineek regression compilation failed' }
$mineekRunFile = Join-Path $mineekOutput 'run.args'
$mineekPack = [IO.Path]::GetFullPath($ShaderPack).Replace('\', '/')
[IO.File]::WriteAllLines($mineekRunFile, @('--enable-native-access=ALL-UNNAMED', '--sun-misc-unsafe-memory-access=allow', '-classpath', ('"' + $mineekClassPath + '"'), 'net.irisshaders.iris.vulkan.MineekShaderContractTest', ('"' + $mineekPack + '"'), ('"' + $mineekOutput.Replace('\', '/') + '"')))
$mineekPreviousErrorAction = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    & (Join-Path $JdkPath 'bin/java.exe') "@$mineekRunFile" 2>&1 | Tee-Object -FilePath (Join-Path $mineekOutput 'result.txt')
    $mineekExitCode = $LASTEXITCODE
} finally { $ErrorActionPreference = $mineekPreviousErrorAction }
if ($mineekExitCode -ne 0) { throw 'Mineek shader contract failed' }
