param(
    [Parameter(Mandatory = $true)][string]$JdkPath,
    [Parameter(Mandatory = $true)][string]$ShaderPack,
    [string]$RuntimeClassPath = 'build/isolated-runtime-classpath.txt'
)
$ErrorActionPreference = 'Stop'
$puddleProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$puddleOutput = Join-Path $puddleProject 'build/storage-2d-tests'
$puddleRuntime = if ([IO.Path]::IsPathRooted($RuntimeClassPath)) { $RuntimeClassPath } else { Join-Path $puddleProject $RuntimeClassPath }
$puddleEntries = @($puddleOutput)
foreach ($relative in @('common/build/classes/java/main', 'common/build/classes/java/api', 'common/build/classes/java/vendored', 'common/build/classes/java/headers')) {
    $puddleEntries += Join-Path $puddleProject $relative
}
$puddleEntries += Get-Content -LiteralPath $puddleRuntime
$puddleCp = ($puddleEntries | ForEach-Object { $_.Replace('\', '/') }) -join ';'
New-Item -ItemType Directory -Path $puddleOutput -Force | Out-Null
$puddleSources = @('tests/storage-2d/stubs/Iris.java', 'tests/storage-2d/IrisVulkanCustom2DSamplerTest.java', 'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanShaderResources.java')
$puddleCompile = @('-proc:none', '-encoding', 'UTF-8', '-classpath', ('"' + $puddleCp + '"'), '-d', ('"' + $puddleOutput.Replace('\', '/') + '"'))
$puddleCompile += $puddleSources | ForEach-Object { '"' + (Join-Path $puddleProject $_).Replace('\', '/') + '"' }
$puddleArgs = Join-Path $puddleOutput 'compile.args'
[IO.File]::WriteAllLines($puddleArgs, $puddleCompile)
& (Join-Path $JdkPath 'bin/javac.exe') "@$puddleArgs"
if ($LASTEXITCODE -ne 0) { throw '2D storage regression compilation failed' }
$puddleArgs = Join-Path $puddleOutput 'run.args'
[IO.File]::WriteAllLines($puddleArgs, @('--enable-native-access=ALL-UNNAMED', '-classpath', ('"' + $puddleCp + '"'), 'net.irisshaders.iris.vulkan.IrisVulkanCustom2DSamplerTest', ('"' + [IO.Path]::GetFullPath($ShaderPack).Replace('\', '/') + '"')))
& (Join-Path $JdkPath 'bin/java.exe') "@$puddleArgs"
if ($LASTEXITCODE -ne 0) { throw '2D storage regression failed' }
