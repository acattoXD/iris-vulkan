param(
    [Parameter(Mandatory = $true)][string]$JdkPath,
    [Parameter(Mandatory = $true)][string]$RuntimeClassPath
)

$ErrorActionPreference = 'Stop'
$keyProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$keyOutput = Join-Path $keyProject 'build/pipeline-key-tests'
$keyRuntime = if ([IO.Path]::IsPathRooted($RuntimeClassPath)) { $RuntimeClassPath } else { Join-Path $keyProject $RuntimeClassPath }
New-Item -ItemType Directory -Path $keyOutput -Force | Out-Null
$keyEntries = @()
foreach ($relative in @('common/build/classes/java/main', 'common/build/classes/java/api', 'common/build/classes/java/vendored', 'common/build/classes/java/headers')) {
    $keyEntries += Join-Path $keyProject $relative
}
$keyEntries += if ([IO.Path]::GetExtension($keyRuntime) -eq '.json') {
    Get-Content -LiteralPath $keyRuntime -Raw | ConvertFrom-Json
} else {
    Get-Content -LiteralPath $keyRuntime
}
$keyProductionClassPath = ($keyEntries | ForEach-Object { $_.Replace('\', '/') }) -join ';'
$keyClassPath = $keyOutput.Replace('\', '/') + ';' + $keyProductionClassPath

function Invoke-KeyCompile([string[]]$Sources, [string]$ClassPath, [string]$Label) {
    $keyArguments = @('-proc:none', '-encoding', 'UTF-8', '-classpath', ('"' + $ClassPath + '"'), '-d', ('"' + $keyOutput.Replace('\', '/') + '"'))
    $keyArguments += $Sources | ForEach-Object { '"' + (Join-Path $keyProject $_).Replace('\', '/') + '"' }
    $keyArgumentFile = Join-Path $keyOutput ($Label + '-compile.args')
    [IO.File]::WriteAllLines($keyArgumentFile, $keyArguments)
    & (Join-Path $JdkPath 'bin/javac.exe') "@$keyArgumentFile"
    if ($LASTEXITCODE -ne 0) { throw "Pipeline-key $Label compilation failed" }
}

# Compile production against real Iris, before the CPU-only startup facades are added.
Invoke-KeyCompile @(
    'common/src/main/java/net/irisshaders/iris/vulkan/IrisNativeVulkan.java',
    'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanWorldPipelineStates.java'
) $keyProductionClassPath 'production'
Invoke-KeyCompile @(
    'tests/pipeline-warmup/stubs/VKOnly_RenderPipelineAccessor.java',
    'tests/pipeline-warmup/stubs/HandRenderer.java',
    'tests/pipeline-warmup/stubs/Iris.java',
    'tests/pipeline-warmup/stubs/PipelineManager.java',
    'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanPhaseContext.java',
    'tests/pipeline-keys/IrisVulkanWorldShaderKeyTest.java'
) $keyClassPath 'regression'
$keyRunFile = Join-Path $keyOutput 'verify-run.args'
[IO.File]::WriteAllLines($keyRunFile, @('--enable-native-access=ALL-UNNAMED', '-Djava.awt.headless=true', '-classpath', ('"' + $keyClassPath + '"'), 'net.irisshaders.iris.vulkan.IrisVulkanWorldShaderKeyTest'))
& (Join-Path $JdkPath 'bin/java.exe') "@$keyRunFile"
if ($LASTEXITCODE -ne 0) { throw 'Pipeline-key regression failed' }
