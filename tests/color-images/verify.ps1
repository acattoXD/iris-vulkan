param(
    [string]$JdkPath = 'C:/Program Files/Java/jdk-25.0.3',
    [string]$RuntimeClassPath = 'build/optimization-26.3/uniform-tests/classpath.json',
    [string]$OutputDirectory = 'build/color-images',
    [string]$PhotonComputeSource = ''
)
$ErrorActionPreference = 'Stop'
$colorWorkspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$colorProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
function Resolve-ColorPath([string]$Path) {
    if ([IO.Path]::IsPathRooted($Path)) { return [IO.Path]::GetFullPath($Path) }
    return [IO.Path]::GetFullPath((Join-Path $colorWorkspace $Path))
}
$colorOutput = Resolve-ColorPath $OutputDirectory
$colorRuntime = Resolve-ColorPath $RuntimeClassPath
$colorActual = Join-Path $colorOutput 'actual'
$colorTests = Join-Path $colorOutput 'tests'
New-Item -ItemType Directory -Path $colorActual,$colorTests -Force | Out-Null
$colorDependencies = @()
foreach ($colorRelative in @('common/build/classes/java/main', 'common/build/classes/java/api', 'common/build/classes/java/vendored', 'common/build/classes/java/headers')) {
    $colorDependencies += Join-Path $colorProject $colorRelative
}
$colorDependencies += if ([IO.Path]::GetExtension($colorRuntime) -eq '.json') {
    Get-Content -LiteralPath $colorRuntime -Raw | ConvertFrom-Json
} else {
    (Get-Content -LiteralPath $colorRuntime) -split ';'
}
# The lightweight runtime manifest omits Iris's compile-only parser dependency.
$colorGradleCache = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$colorParser = Get-ChildItem -LiteralPath (Join-Path $colorGradleCache 'caches/modules-2/files-2.1/org.anarres/jcpp/1.4.14') -Filter 'jcpp-1.4.14.jar' -Recurse | Select-Object -First 1
if (-not $colorParser) { throw 'Cached jcpp 1.4.14 is required for the real shader property parser' }
$colorDependencies += $colorParser.FullName
$colorSourceNames = @(
    'vulkan/IrisVulkanColorImages.java', 'vulkan/IrisVulkanTargetModel.java',
    'vulkan/IrisVulkanTargetSpec.java', 'vulkan/IrisVulkanComputeCompiler.java', 'vulkan/IrisVulkanShaderPruning.java'
)
$colorSources = @($colorSourceNames | ForEach-Object { Join-Path $colorProject ('common/src/main/java/net/irisshaders/iris/' + $_) })
$colorCp = ($colorDependencies | ForEach-Object { $_.Replace('\', '/') }) -join ';'
$colorArgs = Join-Path $colorOutput 'compile-actual.args'
$colorCompile = @('-proc:none', '-encoding', 'UTF-8', '-classpath', ('"' + $colorCp + '"'), '-d', ('"' + $colorActual.Replace('\', '/') + '"'))
$colorCompile += $colorSources | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }
[IO.File]::WriteAllLines($colorArgs, $colorCompile)
& (Join-Path $JdkPath 'bin/javac.exe') "@$colorArgs"
if ($LASTEXITCODE -ne 0) { throw 'Current production color-image compilation failed' }
$colorCp = (@($colorTests, $colorActual) + $colorDependencies | ForEach-Object { $_.Replace('\', '/') }) -join ';'
$colorTestSources = @(Join-Path $PSScriptRoot 'stubs/Iris.java'; Join-Path $PSScriptRoot 'ColorImageContract.java'; Join-Path $PSScriptRoot 'PhotonColorImageCompile.java')
$colorBytecodeSource = Join-Path $PSScriptRoot 'ColorImageBytecodeContract.java'
if (Test-Path -LiteralPath $colorBytecodeSource) { $colorTestSources += $colorBytecodeSource }
$colorTestCompile = @('-proc:none', '-encoding', 'UTF-8', '-classpath', ('"' + $colorCp + '"'), '-d', ('"' + $colorTests.Replace('\', '/') + '"'))
$colorTestCompile += $colorTestSources | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }
$colorTestArgs = Join-Path $colorOutput 'compile-tests.args'
[IO.File]::WriteAllLines($colorTestArgs, $colorTestCompile)
& (Join-Path $JdkPath 'bin/javac.exe') "@$colorTestArgs"
if ($LASTEXITCODE -ne 0) { throw 'Color-image test compilation failed' }
$colorRun = Join-Path $colorOutput 'run.args'
[IO.File]::WriteAllLines($colorRun, @('--enable-native-access=ALL-UNNAMED', '--sun-misc-unsafe-memory-access=allow',
    '-classpath', ('"' + $colorCp + '"'), 'net.irisshaders.iris.vulkan.ColorImageContract', ('"' + $colorActual.Replace('\', '/') + '"')))
& (Join-Path $JdkPath 'bin/java.exe') "@$colorRun" | Tee-Object -FilePath (Join-Path $colorOutput 'result.txt')
if ($LASTEXITCODE -ne 0) { throw 'Color-image CPU contract failed' }
if (Test-Path -LiteralPath $colorBytecodeSource) {
    $colorMixinConfig = Join-Path $colorProject 'common/src/main/resources/mixins.iris.json'
    [IO.File]::WriteAllLines($colorRun, @('--enable-native-access=ALL-UNNAMED', '-classpath', ('"' + $colorCp + '"'),
        'net.irisshaders.iris.vulkan.ColorImageBytecodeContract', ('"' + $colorMixinConfig.Replace('\', '/') + '"')))
    & (Join-Path $JdkPath 'bin/java.exe') "@$colorRun" | Tee-Object -FilePath (Join-Path $colorOutput 'bytecode-result.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Color-image bytecode contract failed' }
}
if ($PhotonComputeSource) {
    $colorPhotonSource = Resolve-ColorPath $PhotonComputeSource
    [IO.File]::WriteAllLines($colorRun, @('--enable-native-access=ALL-UNNAMED', '-classpath', ('"' + $colorCp + '"'),
        'net.irisshaders.iris.vulkan.PhotonColorImageCompile', ('"' + $colorPhotonSource.Replace('\', '/') + '"'),
        ('"' + (Join-Path $colorOutput 'photon').Replace('\', '/') + '"')))
    & (Join-Path $JdkPath 'bin/java.exe') "@$colorRun" | Tee-Object -FilePath (Join-Path $colorOutput 'photon-result.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Actual Photon compute shader compilation failed' }
}
$colorHashes = [ordered]@{}
foreach ($colorFile in @($colorSources + $colorTestSources)) { $colorHashes[$colorFile] = (Get-FileHash -LiteralPath $colorFile -Algorithm SHA256).Hash.ToLowerInvariant() }
$colorEngine = $colorDependencies | Where-Object { $_ -like '*fabric-loom*26.3*minecraft-client.jar' } | Select-Object -First 1
if ($colorEngine) { $colorHashes[$colorEngine] = (Get-FileHash -LiteralPath $colorEngine -Algorithm SHA256).Hash.ToLowerInvariant() }
$colorCompiledHashes = [ordered]@{}
foreach ($colorCompiledRelative in @(
    'vulkan/IrisVulkanComputeExecutor$Program.class',
    'mixin/vulkan/VKOnly_MixinVulkanConst_ColorImages.class'
)) {
    $colorCompiledFile = Join-Path $colorProject ('common/build/classes/java/main/net/irisshaders/iris/' + $colorCompiledRelative)
    $colorCompiledHashes[$colorCompiledFile] = (Get-FileHash -LiteralPath $colorCompiledFile -Algorithm SHA256).Hash.ToLowerInvariant()
}
$colorMixinConfig = Join-Path $colorProject 'common/src/main/resources/mixins.iris.json'
$colorHashes[$colorMixinConfig] = (Get-FileHash -LiteralPath $colorMixinConfig -Algorithm SHA256).Hash.ToLowerInvariant()
[ordered]@{
    utc = (Get-Date).ToUniversalTime().ToString('o'); passed = $true; source_and_engine_sha256 = $colorHashes;
    precompiled_integration_sha256 = $colorCompiledHashes;
    limits = 'CPU fakes record current production allocation and lifecycle methods. ASM checks compilation and engine integration points. No driver calls, GPU allocation, game process, or live cache/render result. No transformed Fabric mixin launch.'
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $colorOutput 'provenance.json')
