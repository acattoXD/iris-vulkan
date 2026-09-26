param(
    [Parameter(Mandatory = $true)][string]$JdkPath,
    [Parameter(Mandatory = $true)][string]$NewSodiumJar,
    [Parameter(Mandatory = $true)][string]$ShaderPack,
    [string]$RuntimeClassPath = 'build/isolated-runtime-classpath.txt'
)
$ErrorActionPreference = 'Stop'
$sodiumProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$sodiumRuntimeFile = if ([IO.Path]::IsPathRooted($RuntimeClassPath)) { $RuntimeClassPath } else { Join-Path $sodiumProject $RuntimeClassPath }
$sodiumRuntime = Get-Content -LiteralPath $sodiumRuntimeFile
$sodiumBeta = @($sodiumRuntime | Where-Object { [IO.Path]::GetFileName($_) -eq 'sodium-fabric-0.9.1-beta.3+mc26.2.jar' })[0]
if (-not $sodiumBeta) { throw 'The reference runtime must contain the frozen beta3 Sodium artifact' }
$sodiumNew = [IO.Path]::GetFullPath($NewSodiumJar)
$sodiumPack = [IO.Path]::GetFullPath($ShaderPack)
$sodiumOutput = Join-Path $sodiumProject 'build/sodium-version-tests'
New-Item -ItemType Directory -Path $sodiumOutput -Force | Out-Null
$sodiumBase = @($sodiumRuntime | Where-Object { -not ([IO.Path]::GetFileName($_) -match '^sodium.*\.jar$') })
$sodiumSources = @(
    'common/src/main/java/net/irisshaders/iris/compat/sodium/mixin/MixinRenderRegionArenas.java',
    'common/src/main/java/net/irisshaders/iris/compat/sodium/mixin/MixinArenaAggregator.java',
    'common/src/main/java/net/irisshaders/iris/mixin/vulkan/VKOnly_MixinVulkanRenderPipeline_PushConstants.java',
    'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanPipelineLayout.java',
    'tests/native-world/IrisSodiumArenaCompatibilityTest.java',
    'tests/native-world/IrisSodiumArenaAllocatorTest.java',
    'tests/native-world/IrisVulkanMixinContractTest.java',
    'tests/native-world/IrisVulkanWorldContractTest.java'
)
$sodiumCompilePath = ((@($sodiumOutput, $sodiumNew) + $sodiumBase) -join ';').Replace('\', '/')
$sodiumCompile = @('-proc:none', '-classpath', ('"' + $sodiumCompilePath + '"'), '-d', ('"' + $sodiumOutput.Replace('\', '/') + '"'))
$sodiumCompile += $sodiumSources | ForEach-Object { '"' + (Join-Path $sodiumProject $_).Replace('\', '/') + '"' }
$sodiumCompileFile = Join-Path $sodiumOutput 'compile.args'
[IO.File]::WriteAllLines($sodiumCompileFile, $sodiumCompile)
& (Join-Path $JdkPath 'bin/javac.exe') "@$sodiumCompileFile"
if ($LASTEXITCODE -ne 0) { throw 'Sodium compatibility compilation failed' }
foreach ($sodiumVersion in @(@{name='beta3';jar=$sodiumBeta}, @{name='0.9.2';jar=$sodiumNew})) {
    $sodiumClassPath = ((@($sodiumOutput, $sodiumVersion.jar) + $sodiumBase) -join ';').Replace('\', '/')
    foreach ($sodiumTest in @('IrisSodiumArenaCompatibilityTest', 'IrisSodiumArenaAllocatorTest', 'IrisVulkanMixinContractTest', 'IrisVulkanWorldContractTest')) {
        if ($sodiumTest -eq 'IrisSodiumArenaAllocatorTest' -and $sodiumVersion.name -eq 'beta3') { continue }
        $sodiumRunArgs = @('--enable-native-access=ALL-UNNAMED', '--sun-misc-unsafe-memory-access=allow', '-classpath', ('"' + $sodiumClassPath + '"'), ('net.irisshaders.iris.vulkan.' + $sodiumTest))
        if ($sodiumTest -eq 'IrisSodiumArenaCompatibilityTest' -or $sodiumTest -eq 'IrisSodiumArenaAllocatorTest') {
            $sodiumRunArgs += @(('"' + $sodiumBeta.Replace('\', '/') + '"'), ('"' + $sodiumNew.Replace('\', '/') + '"'))
        } elseif ($sodiumTest -eq 'IrisVulkanMixinContractTest') {
            $sodiumRunArgs += '"' + (Join-Path $sodiumProject 'common/src/main/java/net/irisshaders/iris/mixin/vulkan').Replace('\', '/') + '"'
        } else {
            $sodiumRunArgs += '"' + $sodiumPack.Replace('\', '/') + '"'
        }
        $sodiumStem = $sodiumVersion.name + '-' + $sodiumTest
        $sodiumRunFile = Join-Path $sodiumOutput ($sodiumStem + '.args')
        [IO.File]::WriteAllLines($sodiumRunFile, $sodiumRunArgs)
        & (Join-Path $JdkPath 'bin/java.exe') "@$sodiumRunFile" 2>&1 | Tee-Object -FilePath (Join-Path $sodiumOutput ($sodiumStem + '.txt'))
        if ($LASTEXITCODE -ne 0) { throw "Sodium $($sodiumVersion.name) failed $sodiumTest" }
    }
}
