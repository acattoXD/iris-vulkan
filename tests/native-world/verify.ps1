param(
    [Parameter(Mandatory = $true)][string]$JdkPath,
    [Parameter(Mandatory = $true)][string]$ShaderPack,
    [string]$DumpDirectory,
    [string]$GradleCache = (Join-Path $env:USERPROFILE '.gradle/caches')
)

$ErrorActionPreference = 'Stop'
$projectDir = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$shaderPackPath = [System.IO.Path]::GetFullPath($ShaderPack)
$cacheDir = [System.IO.Path]::GetFullPath($GradleCache)
$outputDir = Join-Path $projectDir 'build/native-world-tests'
$mainClasses = Join-Path $projectDir 'common/build/classes/java/main'
if (-not (Test-Path -LiteralPath (Join-Path $mainClasses 'net/irisshaders/iris/vulkan/IrisVulkanEntityContext.class'))) {
    throw 'Compile the current project with :common:compileJava before running these tests.'
}
if (-not (Test-Path -LiteralPath $shaderPackPath)) { throw "Shader pack not found: $shaderPackPath" }

function Find-CachedArtifact([string]$Coordinate) {
    $parts = $Coordinate.Split(':')
    $directory = Join-Path $cacheDir ('modules-2/files-2.1/' + $parts[0] + '/' + $parts[1] + '/' + $parts[2])
    $classifier = if ($parts.Length -gt 3) { '-' + $parts[3] } else { '' }
    $filename = $parts[1] + '-' + $parts[2] + $classifier + '.jar'
    if (-not (Test-Path -LiteralPath $directory)) { return }
    @(& rg --files $directory -g $filename) | ForEach-Object { $_.Replace('\', '/') }
}

$classPath = [System.Collections.Generic.List[string]]::new()
$classPath.Add($outputDir.Replace('\', '/'))
foreach ($relative in @('common/build/classes/java/main', 'common/build/classes/java/api', 'common/build/classes/java/vendored', 'common/build/classes/java/headers', 'fabric/build/classes/java/main')) {
    $classPath.Add((Join-Path $projectDir $relative).Replace('\', '/'))
}
$classPath.Add((Join-Path $cacheDir 'fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar').Replace('\', '/'))
$metadata = Get-Content -LiteralPath (Join-Path $cacheDir 'fabric-loom/26.2/mojang_minecraft_info.json') -Raw | ConvertFrom-Json
foreach ($library in $metadata.libraries) {
    $parts = $library.name.Split(':')
    if ($parts.Length -gt 3 -and $parts[3] -notin @('unsafe', 'natives-windows')) { continue }
    foreach ($jar in @(Find-CachedArtifact $library.name)) { $classPath.Add($jar) }
}
foreach ($coordinate in @(
    'net.fabricmc:fabric-loader:0.19.2',
    'net.caffeinemc:sodium-fabric:0.9.1-beta.3+mc26.2',
    'org.anarres:jcpp:1.4.14',
    'org.antlr:antlr4-runtime:4.13.1',
    'io.github.douira:glsl-transformer:3.0.0-pre3',
    'org.ow2.asm:asm:9.9',
    'org.ow2.asm:asm-tree:9.9'
)) {
    foreach ($jar in @(Find-CachedArtifact $coordinate)) { $classPath.Add($jar) }
}

New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$cp = ($classPath | Select-Object -Unique) -join ';'
$compilerArgs = @('-proc:none', '-encoding', 'UTF-8', '-classpath', ('"' + $cp + '"'), '-d', ('"' + $outputDir.Replace('\', '/') + '"'))
$compilerArgs += @(& rg --files $PSScriptRoot -g '*.java' | ForEach-Object { '"' + $_.Replace('\', '/') + '"' })
$compilerArgsPath = Join-Path $outputDir 'javac.args'
[System.IO.File]::WriteAllLines($compilerArgsPath, $compilerArgs)
& (Join-Path $JdkPath 'bin/javac.exe') "@$compilerArgsPath"
if ($LASTEXITCODE -ne 0) { throw 'Native world test compilation failed.' }

$tests = @('IrisVulkanWorldContractTest', 'IrisVulkanMixinContractTest', 'WorldRenderingSettingsCacheTest', 'IrisVulkanDeferredDepthRegression', 'IrisVulkanCompileTimingTest')
if ($DumpDirectory) { $tests += 'IrisVulkanDumpCompileTest' }
foreach ($test in $tests) {
    $argument = switch ($test) {
        'IrisVulkanWorldContractTest' { $shaderPackPath }
        'IrisVulkanDumpCompileTest' { [System.IO.Path]::GetFullPath($DumpDirectory) }
        default { Join-Path $projectDir 'common/src/main/java/net/irisshaders/iris/mixin/vulkan' }
    }
    $runtimeArgsPath = Join-Path $outputDir ($test + '.args')
    [System.IO.File]::WriteAllLines($runtimeArgsPath, @('--enable-native-access=ALL-UNNAMED', '-classpath', ('"' + $cp + '"'), ('net.irisshaders.iris.vulkan.' + $test), ('"' + $argument.Replace('\', '/') + '"')))
    & (Join-Path $JdkPath 'bin/java.exe') "@$runtimeArgsPath"
    if ($LASTEXITCODE -ne 0) { throw "Native world test failed: $test" }
}
