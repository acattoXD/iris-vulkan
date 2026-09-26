param(
    [Parameter(Mandatory = $true)][string]$JdkPath,
    [string]$RuntimeClassPath = 'build/isolated-runtime-classpath.txt'
)

$ErrorActionPreference = 'Stop'
$cameraProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$cameraOutput = Join-Path $cameraProject 'build/camera-projection-tests'
$cameraRuntime = if ([IO.Path]::IsPathRooted($RuntimeClassPath)) { $RuntimeClassPath } else { Join-Path $cameraProject $RuntimeClassPath }
if (-not (Test-Path -LiteralPath $cameraRuntime)) { throw "Missing runtime classpath: $cameraRuntime" }
New-Item -ItemType Directory -Path $cameraOutput -Force | Out-Null
$cameraEntries = @($cameraOutput)
foreach ($relative in @('common/build/classes/java/main', 'common/build/classes/java/api', 'common/build/classes/java/vendored')) {
    $cameraEntries += Join-Path $cameraProject $relative
}
$cameraEntries += Get-Content -LiteralPath $cameraRuntime
$cameraClassPath = ($cameraEntries | ForEach-Object { $_.Replace('\', '/') }) -join ';'
$cameraSources = @(
    'tests/projection/stubs/IrisPlatformHelpers.java',
    'tests/projection/stubs/Iris.java',
    'common/src/main/java/net/irisshaders/iris/mixin/IrisMixinPlugin.java',
    'common/src/main/java/net/irisshaders/iris/mixin/MixinModelViewBobbing.java',
    'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanProjection.java',
    'tests/projection/IrisVulkanCameraMixinTest.java',
    'tests/projection/IrisVulkanCameraGateTest.java',
    'tests/projection/IrisVulkanProjectionTest.java',
    'tests/native-world/IrisVulkanMixinContractTest.java'
)
$cameraCompileArgs = @('-proc:none', '-encoding', 'UTF-8', '-classpath', ('"' + $cameraClassPath + '"'), '-d', ('"' + $cameraOutput.Replace('\', '/') + '"'))
$cameraCompileArgs += $cameraSources | ForEach-Object { '"' + (Join-Path $cameraProject $_).Replace('\', '/') + '"' }
$cameraArgsFile = Join-Path $cameraOutput 'verify-compile.args'
[IO.File]::WriteAllLines($cameraArgsFile, $cameraCompileArgs)
& (Join-Path $JdkPath 'bin/javac.exe') "@$cameraArgsFile"
if ($LASTEXITCODE -ne 0) { throw 'Camera regression compilation failed' }
foreach ($cameraTest in @('IrisVulkanCameraMixinTest', 'IrisVulkanCameraGateTest', 'IrisVulkanProjectionTest', 'IrisVulkanMixinContractTest')) {
    $cameraRunArgs = @('-classpath', ('"' + $cameraClassPath + '"'), ('net.irisshaders.iris.vulkan.' + $cameraTest))
    if ($cameraTest -eq 'IrisVulkanMixinContractTest') {
        $cameraRunArgs += '"' + (Join-Path $cameraProject 'common/src/main/java/net/irisshaders/iris/mixin/vulkan').Replace('\', '/') + '"'
    }
    $cameraArgsFile = Join-Path $cameraOutput ($cameraTest + '.args')
    [IO.File]::WriteAllLines($cameraArgsFile, $cameraRunArgs)
    & (Join-Path $JdkPath 'bin/java.exe') "@$cameraArgsFile"
    if ($LASTEXITCODE -ne 0) { throw "Camera regression failed: $cameraTest" }
}
