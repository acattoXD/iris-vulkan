param(
    [string]$JdkPath = 'C:/Program Files/Java/jdk-25.0.3',
    [string]$ShaderPack = 'build/port-26.3/motion-alpha15-translucent-02/shaderpacks/photon_v1.3b.zip',
    [string]$RuntimeClassPath = 'build/optimization-26.3/uniform-tests/classpath.json',
    [string]$OutputDirectory = 'build/custom-volumes'
)
$ErrorActionPreference = 'Stop'
$volumeWorkspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$volumeProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
function Resolve-VolumePath([string]$Path) {
    if ([IO.Path]::IsPathRooted($Path)) { return [IO.Path]::GetFullPath($Path) }
    return [IO.Path]::GetFullPath((Join-Path $volumeWorkspace $Path))
}
$volumeOutput = Resolve-VolumePath $OutputDirectory
$volumeRuntime = Resolve-VolumePath $RuntimeClassPath
$volumePack = Resolve-VolumePath $ShaderPack
$volumeJavac = Join-Path $JdkPath 'bin/javac.exe'
$volumeJava = Join-Path $JdkPath 'bin/java.exe'
foreach ($volumeRequired in @($volumeRuntime, $volumePack, $volumeJavac, $volumeJava)) {
    if (-not (Test-Path -LiteralPath $volumeRequired -PathType Leaf)) { throw "Required input not found: $volumeRequired" }
}
# Reuse the already resolved 26.3 runtime manifest, but isolate the CPU test to four LWJGL jars.
# No Minecraft, Fabric, OpenGL, Vulkan driver, Gradle, or live-game state is loaded.
if ([IO.Path]::GetExtension($volumeRuntime) -eq '.json') {
    $volumeRuntimeEntries = @(Get-Content -LiteralPath $volumeRuntime -Raw | ConvertFrom-Json)
} else {
    $volumeRuntimeEntries = @((Get-Content -LiteralPath $volumeRuntime) -split ';')
}
$volumeDependencies = @($volumeRuntimeEntries | Where-Object {
    ($_ -replace '\\', '/') -match '/(?:org/lwjgl|org\.lwjgl)/(lwjgl|lwjgl-shaderc)/'
} | Where-Object {
    [IO.Path]::GetFileName($_) -match '^lwjgl(?:-shaderc)?-[0-9.]+(?:-natives-windows)?\.jar$'
} | Select-Object -Unique)
if ($volumeDependencies.Count -ne 4) { throw "Expected core/shaderc Java and Windows x64 native jars, found $($volumeDependencies.Count)" }
foreach ($volumeDependency in $volumeDependencies) {
    if (-not (Test-Path -LiteralPath $volumeDependency -PathType Leaf)) { throw "Runtime dependency not found: $volumeDependency" }
}
$volumeClasses = Join-Path $volumeOutput 'classes'
$volumeShaders = Join-Path $volumeOutput 'shaders'
New-Item -ItemType Directory -Path $volumeClasses,$volumeShaders -Force | Out-Null
$volumeCp = (@($volumeClasses) + $volumeDependencies | ForEach-Object { $_.Replace('\', '/') }) -join ';'
$volumeSource = Join-Path $volumeProject 'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanStaticVolume.java'
$volumeCompileFile = Join-Path $volumeOutput 'compile.args'
[IO.File]::WriteAllLines($volumeCompileFile, @(
    '-proc:none', '-encoding', 'UTF-8', '--release', '25',
    '-classpath', ('"' + $volumeCp + '"'), '-d', ('"' + $volumeClasses.Replace('\', '/') + '"'),
    ('"' + $volumeSource.Replace('\', '/') + '"'),
    ('"' + (Join-Path $PSScriptRoot 'IrisVulkanStaticVolumeTest.java').Replace('\', '/') + '"')
))
& $volumeJavac "@$volumeCompileFile"
if ($LASTEXITCODE -ne 0) { throw 'Static volume CPU test compilation failed' }
$volumeRunFile = Join-Path $volumeOutput 'run.args'
[IO.File]::WriteAllLines($volumeRunFile, @(
    '--enable-native-access=ALL-UNNAMED', '-classpath', ('"' + $volumeCp + '"'),
    'net.irisshaders.iris.vulkan.IrisVulkanStaticVolumeTest',
    ('"' + $volumePack.Replace('\', '/') + '"'), ('"' + $volumeShaders.Replace('\', '/') + '"')
))
& $volumeJava "@$volumeRunFile" | Tee-Object -FilePath (Join-Path $volumeOutput 'result.txt')
if ($LASTEXITCODE -ne 0) { throw 'Static volume CPU regression failed' }
# Separately compile the integration against the real cached Minecraft/native API.
# Startup-only Iris/ShaderPack facades prevent application initialization in routing tests.
$volumeIntegration = Join-Path $volumeOutput 'integration'
New-Item -ItemType Directory -Path $volumeIntegration -Force | Out-Null
$volumeIntegrationEntries = @($volumeIntegration, $volumeClasses)
foreach ($volumeRelative in @('common/build/classes/java/main', 'common/build/classes/java/api', 'common/build/classes/java/vendored', 'common/build/classes/java/headers')) {
    $volumeIntegrationEntries += Join-Path $volumeProject $volumeRelative
}
$volumeIntegrationEntries += $volumeRuntimeEntries
$volumeIntegrationCp = ($volumeIntegrationEntries | ForEach-Object { $_.Replace('\', '/') }) -join ';'
$volumeIntegrationSources = @(
    (Join-Path $volumeProject 'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanCustomTextures.java'),
    (Join-Path $volumeProject 'common/src/main/java/net/irisshaders/iris/shaderpack/texture/CustomTextureData.java'),
    (Join-Path $PSScriptRoot 'stubs/Iris.java'), (Join-Path $PSScriptRoot 'stubs/ShaderPack.java'),
    (Join-Path $PSScriptRoot 'IrisVulkanVolumeRoutingTest.java')
)
$volumeIntegrationCompile = @('-proc:none', '-encoding', 'UTF-8', '--release', '25',
    '-classpath', ('"' + $volumeIntegrationCp + '"'), '-d', ('"' + $volumeIntegration.Replace('\', '/') + '"'))
$volumeIntegrationCompile += $volumeIntegrationSources | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }
$volumeIntegrationArgs = Join-Path $volumeOutput 'integration-compile.args'
[IO.File]::WriteAllLines($volumeIntegrationArgs, $volumeIntegrationCompile)
& $volumeJavac "@$volumeIntegrationArgs"
if ($LASTEXITCODE -ne 0) { throw 'Static volume native API integration compilation failed' }
$volumeIntegrationRun = Join-Path $volumeOutput 'integration-run.args'
[IO.File]::WriteAllLines($volumeIntegrationRun, @('--enable-native-access=ALL-UNNAMED',
    '-classpath', ('"' + $volumeIntegrationCp + '"'), 'net.irisshaders.iris.vulkan.IrisVulkanVolumeRoutingTest'))
& $volumeJava "@$volumeIntegrationRun" | Tee-Object -FilePath (Join-Path $volumeOutput 'integration-result.txt')
if ($LASTEXITCODE -ne 0) { throw 'Static volume alias routing regression failed' }
