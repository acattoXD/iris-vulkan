param(
    [Parameter(Mandatory = $true)][string]$JdkPath,
    [string]$RuntimeClassPath = 'build/isolated-runtime-classpath.txt'
)

$ErrorActionPreference = 'Stop'
$warmupProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$warmupOutput = Join-Path $warmupProject 'build/pipeline-warmup-tests'
$warmupRuntime = if ([IO.Path]::IsPathRooted($RuntimeClassPath)) { $RuntimeClassPath } else { Join-Path $warmupProject $RuntimeClassPath }
New-Item -ItemType Directory -Path $warmupOutput -Force | Out-Null
$warmupEntries = @($warmupOutput)
foreach ($relative in @('common/build/classes/java/main', 'common/build/classes/java/api', 'common/build/classes/java/vendored', 'common/build/classes/java/headers')) {
    $warmupEntries += Join-Path $warmupProject $relative
}
$warmupEntries += if ([IO.Path]::GetExtension($warmupRuntime) -eq '.json') {
    Get-Content -LiteralPath $warmupRuntime -Raw | ConvertFrom-Json
} else {
    Get-Content -LiteralPath $warmupRuntime
}
$warmupClassPath = ($warmupEntries | ForEach-Object { $_.Replace('\', '/') }) -join ';'
$warmupSources = @(
    'tests/pipeline-warmup/stubs/VKOnly_RenderPipelineAccessor.java',
    'tests/pipeline-warmup/stubs/HandRenderer.java',
    'tests/pipeline-warmup/stubs/Iris.java',
    'tests/pipeline-warmup/stubs/PipelineManager.java',
    'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanPipelineWarmup.java',
    'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanWorldPipelineStates.java',
    'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanPhaseContext.java',
    'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanShadowDrawPolicy.java',
    'common/src/main/java/net/irisshaders/iris/pipeline/IrisPipelines.java',
    'tests/pipeline-warmup/IrisVulkanPipelineWarmupTest.java'
)
$warmupArguments = @('-proc:none', '-encoding', 'UTF-8', '-classpath', ('"' + $warmupClassPath + '"'), '-d', ('"' + $warmupOutput.Replace('\', '/') + '"'))
$warmupArguments += $warmupSources | ForEach-Object { '"' + (Join-Path $warmupProject $_).Replace('\', '/') + '"' }
$warmupCompileFile = Join-Path $warmupOutput 'verify-compile.args'
[IO.File]::WriteAllLines($warmupCompileFile, $warmupArguments)
& (Join-Path $JdkPath 'bin/javac.exe') "@$warmupCompileFile"
if ($LASTEXITCODE -ne 0) { throw 'Pipeline warmup test compilation failed' }
$warmupRunFile = Join-Path $warmupOutput 'verify-run.args'
[IO.File]::WriteAllLines($warmupRunFile, @('--enable-native-access=ALL-UNNAMED', '-classpath', ('"' + $warmupClassPath + '"'), 'net.irisshaders.iris.vulkan.IrisVulkanPipelineWarmupTest'))
& (Join-Path $JdkPath 'bin/java.exe') "@$warmupRunFile"
if ($LASTEXITCODE -ne 0) { throw 'Pipeline warmup regression failed' }
