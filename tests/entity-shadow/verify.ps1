param(
    [Parameter(Mandatory = $true)][string]$JdkPath,
    [Parameter(Mandatory = $true)][string]$ShaderPack,
    [string]$RuntimeClassPath = 'build/isolated-runtime-classpath.txt'
)
$ErrorActionPreference = 'Stop'
$shadowProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$shadowOutput = Join-Path $shadowProject 'build/entity-shadow-tests'
$shadowRuntime = if ([IO.Path]::IsPathRooted($RuntimeClassPath)) { $RuntimeClassPath } else { Join-Path $shadowProject $RuntimeClassPath }
$shadowEntries = @($shadowOutput)
foreach ($relative in @('common/build/classes/java/main', 'common/build/classes/java/api', 'common/build/classes/java/vendored', 'common/build/classes/java/headers')) { $shadowEntries += Join-Path $shadowProject $relative }
$shadowEntries += Get-Content -LiteralPath $shadowRuntime
$shadowCp = ($shadowEntries | ForEach-Object { $_.Replace('\', '/') }) -join ';'
New-Item -ItemType Directory -Path $shadowOutput -Force | Out-Null
$shadowSources = @('tests/entity-shadow/stubs/Iris.java', 'tests/projection/stubs/IrisPlatformHelpers.java', 'tests/entity-shadow/IrisVulkanEntityShadowGateTest.java',
    'common/src/main/java/net/irisshaders/iris/pipeline/NativeVulkanWorldRenderingPipeline.java', 'common/src/main/java/net/irisshaders/iris/mixin/MixinEntityRenderDispatcher.java', 'common/src/main/java/net/irisshaders/iris/mixin/IrisMixinPlugin.java')
$shadowCompile = @('-proc:none', '-encoding', 'UTF-8', '-classpath', ('"' + $shadowCp + '"'), '-d', ('"' + $shadowOutput.Replace('\', '/') + '"'))
$shadowCompile += $shadowSources | ForEach-Object { '"' + (Join-Path $shadowProject $_).Replace('\', '/') + '"' }
$shadowArgs = Join-Path $shadowOutput 'compile.args'
[IO.File]::WriteAllLines($shadowArgs, $shadowCompile)
& (Join-Path $JdkPath 'bin/javac.exe') "@$shadowArgs"
if ($LASTEXITCODE -ne 0) { throw 'Entity shadow gate compilation failed' }
$shadowArgs = Join-Path $shadowOutput 'run.args'
[IO.File]::WriteAllLines($shadowArgs, @('--enable-native-access=ALL-UNNAMED', '-classpath', ('"' + $shadowCp + '"'), 'net.irisshaders.iris.vulkan.IrisVulkanEntityShadowGateTest', ('"' + [IO.Path]::GetFullPath($ShaderPack).Replace('\', '/') + '"')))
& (Join-Path $JdkPath 'bin/java.exe') "@$shadowArgs" | Tee-Object -FilePath (Join-Path $shadowOutput 'result.txt')
if ($LASTEXITCODE -ne 0) { throw 'Entity shadow gate regression failed' }
