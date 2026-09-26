param(
    [Parameter(Mandatory = $true)][string]$JdkPath,
    [Parameter(Mandatory = $true)][string]$MakeUpPack,
    [Parameter(Mandatory = $true)][string[]]$SodiumJars,
    [string]$RuntimeClassPath = 'build/isolated-runtime-classpath.txt'
)
$ErrorActionPreference = 'Stop'
$layoutProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$layoutOutput = Join-Path $layoutProject 'build/pipeline-layout-tests'
$layoutRuntime = if ([IO.Path]::IsPathRooted($RuntimeClassPath)) { $RuntimeClassPath } else { Join-Path $layoutProject $RuntimeClassPath }
$layoutEntries = @()
foreach ($relative in @('common/build/classes/java/main', 'common/build/classes/java/api', 'common/build/classes/java/vendored', 'common/build/classes/java/headers')) {
    $layoutEntries += Join-Path $layoutProject $relative
}
$layoutEntries += Get-Content -LiteralPath $layoutRuntime
$layoutDependencies = ($layoutEntries | ForEach-Object { $_.Replace('\', '/') }) -join ';'
$layoutCp = $layoutOutput.Replace('\', '/') + ';' + $layoutDependencies
New-Item -ItemType Directory -Path $layoutOutput -Force | Out-Null
$layoutSources = @(
    'tests/pipeline-layout/stubs/Iris.java',
    'tests/pipeline-layout/IrisVulkanPushConstantLayoutTest.java',
    'tests/pipeline-layout/MakeUpPushConstantMslTest.java',
    'tests/native-world/IrisVulkanMixinContractTest.java',
    'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanPipelineLayout.java',
    'common/src/main/java/net/irisshaders/iris/mixin/vulkan/VKOnly_MixinVulkanRenderPipeline_PushConstants.java',
    'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanShaderResources.java'
)
$layoutCompile = @('-proc:none', '-encoding', 'UTF-8', '-classpath', ('"' + $layoutCp + '"'), '-d', ('"' + $layoutOutput.Replace('\', '/') + '"'))
$layoutCompile += $layoutSources | ForEach-Object { '"' + (Join-Path $layoutProject $_).Replace('\', '/') + '"' }
$layoutArgs = Join-Path $layoutOutput 'compile.args'
[IO.File]::WriteAllLines($layoutArgs, $layoutCompile)
& (Join-Path $JdkPath 'bin/javac.exe') "@$layoutArgs"
if ($LASTEXITCODE -ne 0) { throw 'Pipeline-layout test compilation failed' }
foreach ($layoutSodium in $SodiumJars) {
    $layoutVersionCp = $layoutOutput.Replace('\', '/') + ';' + [IO.Path]::GetFullPath($layoutSodium).Replace('\', '/') + ';' + $layoutDependencies
    $layoutArgs = Join-Path $layoutOutput 'layout-run.args'
    [IO.File]::WriteAllLines($layoutArgs, @('--enable-native-access=ALL-UNNAMED', '-classpath', ('"' + $layoutVersionCp + '"'), 'net.irisshaders.iris.vulkan.IrisVulkanPushConstantLayoutTest'))
    & (Join-Path $JdkPath 'bin/java.exe') "@$layoutArgs"
    if ($LASTEXITCODE -ne 0) { throw "Pipeline-layout test failed with $layoutSodium" }
}
$layoutArgs = Join-Path $layoutOutput 'makeup-run.args'
[IO.File]::WriteAllLines($layoutArgs, @('--enable-native-access=ALL-UNNAMED', '-classpath', ('"' + $layoutCp + '"'), 'net.irisshaders.iris.vulkan.MakeUpPushConstantMslTest', ('"' + [IO.Path]::GetFullPath($MakeUpPack).Replace('\', '/') + '"'), ('"' + $layoutOutput.Replace('\', '/') + '"')))
& (Join-Path $JdkPath 'bin/java.exe') "@$layoutArgs"
if ($LASTEXITCODE -ne 0) { throw 'MakeUp MSL-binding regression failed' }
$layoutArgs = Join-Path $layoutOutput 'hooks-run.args'
[IO.File]::WriteAllLines($layoutArgs, @('--enable-native-access=ALL-UNNAMED', '-classpath', ('"' + $layoutCp + '"'), 'net.irisshaders.iris.vulkan.IrisVulkanMixinContractTest', ('"' + (Join-Path $layoutProject 'common/src/main/java/net/irisshaders/iris/mixin/vulkan').Replace('\', '/') + '"')))
& (Join-Path $JdkPath 'bin/java.exe') "@$layoutArgs"
if ($LASTEXITCODE -ne 0) { throw 'Native mixin hook contract failed' }
