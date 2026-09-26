param(
    [Parameter(Mandatory = $true)][string]$JdkPath,
    [Parameter(Mandatory = $true)][string]$ShaderPack,
    [string]$LegacyArtifact,
    [string]$RuntimeClassPath = 'build/isolated-runtime-classpath.txt'
)
$ErrorActionPreference = 'Stop'
$gbufferProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$gbufferOutput = Join-Path $gbufferProject 'build/gbuffers-only-tests'
$gbufferActual = (Join-Path $gbufferOutput 'actual').Replace('\', '/')
$gbufferTests = (Join-Path $gbufferOutput 'tests').Replace('\', '/')
$gbufferRuntime = if ([IO.Path]::IsPathRooted($RuntimeClassPath)) { $RuntimeClassPath } else { Join-Path $gbufferProject $RuntimeClassPath }
$gbufferEntries = @()
foreach ($relative in @('common/build/classes/java/main', 'common/build/classes/java/api', 'common/build/classes/java/vendored', 'common/build/classes/java/headers')) {
    $gbufferEntries += Join-Path $gbufferProject $relative
}
$gbufferEntries += Get-Content -LiteralPath $gbufferRuntime
$gbufferDependencies = ($gbufferEntries | ForEach-Object { $_.Replace('\', '/') }) -join ';'
New-Item -ItemType Directory -Path $gbufferActual,$gbufferTests -Force | Out-Null
$gbufferSources = @('vulkan/IrisVulkanScreenPassGraph.java', 'vulkan/IrisVulkanScreenPassPlanner.java', 'vulkan/IrisVulkanFinalPassRenderer.java', 'vulkan/IrisVulkanScreenPassExecutor.java', 'pipeline/NativeVulkanWorldRenderingPipeline.java')
$gbufferCompile = @('-proc:none', '-encoding', 'UTF-8', '-classpath', ('"' + $gbufferDependencies + '"'), '-d', ('"' + $gbufferActual + '"'))
$gbufferCompile += $gbufferSources | ForEach-Object { '"' + (Join-Path $gbufferProject ('common/src/main/java/net/irisshaders/iris/' + $_)).Replace('\', '/') + '"' }
$gbufferArgs = Join-Path $gbufferOutput 'actual.args'
[IO.File]::WriteAllLines($gbufferArgs, $gbufferCompile)
& (Join-Path $JdkPath 'bin/javac.exe') "@$gbufferArgs"
if ($LASTEXITCODE -ne 0) { throw 'Gbuffers-only production source compilation failed' }
$gbufferCp = $gbufferTests + ';' + $gbufferActual + ';' + $gbufferDependencies
$gbufferArgs = Join-Path $gbufferOutput 'test.args'
[IO.File]::WriteAllLines($gbufferArgs, @('-proc:none', '-encoding', 'UTF-8', '-classpath', ('"' + $gbufferCp + '"'), '-d', ('"' + $gbufferTests + '"'),
    ('"' + (Join-Path $PSScriptRoot 'IrisVulkanGbuffersOnlyTest.java').Replace('\', '/') + '"'),
    ('"' + (Join-Path $gbufferProject 'tests/storage-2d/stubs/Iris.java').Replace('\', '/') + '"')))
& (Join-Path $JdkPath 'bin/javac.exe') "@$gbufferArgs"
if ($LASTEXITCODE -ne 0) { throw 'Gbuffers-only fixture compilation failed' }
$gbufferArgs = Join-Path $gbufferOutput 'run.args'
$gbufferRun = @('--enable-native-access=ALL-UNNAMED', '-classpath', ('"' + $gbufferCp + '"'), 'net.irisshaders.iris.vulkan.IrisVulkanGbuffersOnlyTest', ('"' + [IO.Path]::GetFullPath($ShaderPack).Replace('\', '/') + '"'))
if ($LegacyArtifact) { $gbufferRun += '"' + [IO.Path]::GetFullPath($LegacyArtifact).Replace('\', '/') + '"' }
[IO.File]::WriteAllLines($gbufferArgs, $gbufferRun)
& (Join-Path $JdkPath 'bin/java.exe') "@$gbufferArgs" | Tee-Object -FilePath (Join-Path $gbufferOutput 'result.txt')
if ($LASTEXITCODE -ne 0) { throw 'Gbuffers-only regression failed' }
