param(
    [Parameter(Mandatory = $true)][string]$JdkPath,
    [string]$RuntimeClassPath = 'build/isolated-runtime-classpath.txt'
)
$ErrorActionPreference = 'Stop'
$glintProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$glintOutput = Join-Path $glintProject 'build/glint-shadow-tests'
$glintRuntimeFile = [IO.Path]::GetFullPath((Join-Path $glintProject $RuntimeClassPath))
$glintSources = @(
    'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanShadowDrawPolicy.java',
    'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanUniformSnapshot.java',
    'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanShaderResources.java',
    'common/src/main/java/net/irisshaders/iris/mixin/vulkan/VKOnly_MixinPreparedRenderType_Materials.java',
    'tests/native-shadows/IrisVulkanGlintShadowTest.java',
    'tests/native-shadows/IrisVulkanHandGlintTest.java'
)
New-Item -ItemType Directory -Path $glintOutput -Force | Out-Null
$glintClassPath = ((@($glintOutput) + (Get-Content -LiteralPath $glintRuntimeFile)) -join ';').Replace('\', '/')
$glintCompile = @('-proc:none', '-classpath', ('"' + $glintClassPath + '"'), '-d', ('"' + $glintOutput.Replace('\', '/') + '"'))
$glintCompile += $glintSources | ForEach-Object { '"' + (Join-Path $glintProject $_).Replace('\', '/') + '"' }
$glintCompileFile = Join-Path $glintOutput 'compile.args'
[IO.File]::WriteAllLines($glintCompileFile, $glintCompile)
& (Join-Path $JdkPath 'bin/javac.exe') "@$glintCompileFile"
if ($LASTEXITCODE -ne 0) { throw 'Glint regression compilation failed' }
foreach ($glintTest in @('IrisVulkanGlintShadowTest', 'IrisVulkanHandGlintTest')) {
    $glintRunFile = Join-Path $glintOutput ($glintTest + '.args')
    [IO.File]::WriteAllLines($glintRunFile, @('--enable-native-access=ALL-UNNAMED', '--sun-misc-unsafe-memory-access=allow', '-classpath', ('"' + $glintClassPath + '"'), ('net.irisshaders.iris.vulkan.' + $glintTest)))
    & (Join-Path $JdkPath 'bin/java.exe') "@$glintRunFile"
    if ($LASTEXITCODE -ne 0) { throw "Glint regression failed: $glintTest" }
}
