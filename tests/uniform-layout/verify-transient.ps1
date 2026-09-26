param(
    [Parameter(Mandatory = $true)][string]$JdkPath,
    [string]$RuntimeClassPath = 'build/isolated-runtime-classpath.txt'
)
$ErrorActionPreference = 'Stop'
$uniformProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$uniformOutput = Join-Path $uniformProject 'build/transient-uniform-tests'
$uniformRuntime = if ([IO.Path]::IsPathRooted($RuntimeClassPath)) { $RuntimeClassPath } else { Join-Path $uniformProject $RuntimeClassPath }
New-Item -ItemType Directory -Path $uniformOutput -Force | Out-Null
$uniformClassPath = ((@($uniformOutput) + (Get-Content -LiteralPath $uniformRuntime)) -join ';').Replace('\', '/')
$uniformSources = @(
    'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanUniformSnapshot.java',
    'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanRenderPassBindings.java',
    'tests/uniform-layout/IrisVulkanTransientUniformTest.java',
    'tests/uniform-layout/IrisVulkanLayoutCacheTest.java',
    'tests/uniform-layout/IrisVulkanLayoutCacheProviderRegression.java'
)
$uniformCompile = @('-proc:none', '-classpath', ('"' + $uniformClassPath + '"'), '-d', ('"' + $uniformOutput.Replace('\', '/') + '"'))
$uniformCompile += $uniformSources | ForEach-Object { '"' + (Join-Path $uniformProject $_).Replace('\', '/') + '"' }
$uniformCompileFile = Join-Path $uniformOutput 'compile.args'
[IO.File]::WriteAllLines($uniformCompileFile, $uniformCompile)
& (Join-Path $JdkPath 'bin/javac.exe') "@$uniformCompileFile"
if ($LASTEXITCODE -ne 0) { throw 'Transient uniform regression compilation failed' }
foreach ($uniformTest in @('IrisVulkanTransientUniformTest', 'IrisVulkanLayoutCacheTest', 'IrisVulkanLayoutCacheProviderRegression')) {
    $uniformRunFile = Join-Path $uniformOutput ($uniformTest + '.args')
    [IO.File]::WriteAllLines($uniformRunFile, @('--enable-native-access=ALL-UNNAMED', '-classpath', ('"' + $uniformClassPath + '"'), ('net.irisshaders.iris.vulkan.' + $uniformTest)))
    & (Join-Path $JdkPath 'bin/java.exe') "@$uniformRunFile"
    if ($LASTEXITCODE -ne 0) { throw "Uniform regression failed: $uniformTest" }
}
