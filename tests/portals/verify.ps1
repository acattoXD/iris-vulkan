param(
    [Parameter(Mandatory = $true)][string]$JdkPath,
    [Parameter(Mandatory = $true)][string]$ShaderPack,
    [string]$RuntimeClassPath = 'build/isolated-runtime-classpath.txt'
)
$ErrorActionPreference = 'Stop'
$portalProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$portalOutput = Join-Path $portalProject 'build/portal-contract-tests'
$portalRuntimeFile = if ([IO.Path]::IsPathRooted($RuntimeClassPath)) { $RuntimeClassPath } else { Join-Path $portalProject $RuntimeClassPath }
New-Item -ItemType Directory -Path $portalOutput -Force | Out-Null
$portalEntries = @($portalOutput)
foreach ($relative in @('common/build/classes/java/main', 'common/build/classes/java/api', 'common/build/classes/java/vendored')) { $portalEntries += Join-Path $portalProject $relative }
$portalEntries += Get-Content -LiteralPath $portalRuntimeFile
$portalClassPath = ($portalEntries | ForEach-Object { $_.Replace('\', '/') }) -join ';'
$portalSources = @(
    'tests/portals/stubs/Iris.java',
    'tests/projection/stubs/IrisPlatformHelpers.java',
    'common/src/main/java/net/irisshaders/iris/mixin/IrisMixinPlugin.java',
    'common/src/main/java/net/irisshaders/iris/mixin/MixinTheEndPortalRenderer.java',
    'common/src/main/java/net/irisshaders/iris/mixin/MixinTheEndGatewayRenderer.java',
    'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanShaderResources.java',
    'tests/portals/IrisPortalContractTest.java'
)
$portalCompile = @('-proc:none', '-encoding', 'UTF-8', '-classpath', ('"' + $portalClassPath + '"'), '-d', ('"' + $portalOutput.Replace('\', '/') + '"'))
$portalCompile += $portalSources | ForEach-Object { '"' + (Join-Path $portalProject $_).Replace('\', '/') + '"' }
$portalCompileFile = Join-Path $portalOutput 'compile.args'
[IO.File]::WriteAllLines($portalCompileFile, $portalCompile)
& (Join-Path $JdkPath 'bin/javac.exe') "@$portalCompileFile"
if ($LASTEXITCODE -ne 0) { throw 'Portal regression compilation failed' }
$portalRunFile = Join-Path $portalOutput 'run.args'
$portalPackPath = [IO.Path]::GetFullPath($ShaderPack).Replace('\', '/')
[IO.File]::WriteAllLines($portalRunFile, @('--enable-native-access=ALL-UNNAMED', '--sun-misc-unsafe-memory-access=allow', '-classpath', ('"' + $portalClassPath + '"'), 'net.irisshaders.iris.vulkan.IrisPortalContractTest', ('"' + $portalPackPath + '"')))
& (Join-Path $JdkPath 'bin/java.exe') "@$portalRunFile" 2>&1 | Tee-Object -FilePath (Join-Path $portalOutput 'result.txt')
if ($LASTEXITCODE -ne 0) { throw 'Portal regression failed' }
