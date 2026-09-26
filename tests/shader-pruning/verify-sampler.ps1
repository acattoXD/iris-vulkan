param(
    [string]$JdkPath = 'C:/Program Files/Java/jdk-25.0.3',
    [string]$OutputDirectory,
    [string]$DumpDirectory,
    [string]$ClasspathFile
)
$ErrorActionPreference = 'Stop'
$samplerPort = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$samplerWorkspace = (Resolve-Path (Join-Path $samplerPort '../..')).Path
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $samplerWorkspace 'build/optimization-26.3/photon-dump-compile-02-actual-helper' }
if (-not $ClasspathFile) { $ClasspathFile = Join-Path $samplerWorkspace 'build/optimization-26.3/uniform-tests/classpath.json' }
$samplerClasses = Join-Path $OutputDirectory 'classes'
$samplerProduction = Join-Path $samplerPort 'common/build/classes/java/main'
$samplerSource = Join-Path $PSScriptRoot 'IrisVulkanSamplerCompatibilityTest.java'
$samplerDependencies = Get-Content -Raw -LiteralPath $ClasspathFile | ConvertFrom-Json
$samplerClasspath = (@($samplerProduction) + @($samplerDependencies)) -join ';'
New-Item -ItemType Directory -Path $samplerClasses -Force | Out-Null
& (Join-Path $JdkPath 'bin/javac.exe') --release 25 -proc:none -encoding UTF-8 -classpath $samplerClasspath -d $samplerClasses $samplerSource 2>&1 | Tee-Object -FilePath (Join-Path $OutputDirectory 'javac.log')
if ($LASTEXITCODE -ne 0) { throw "Sampler test compilation failed: $LASTEXITCODE" }
$samplerRunArgs = @('--enable-native-access=ALL-UNNAMED', '-Djava.awt.headless=true', '-classpath', "$samplerClasses;$samplerClasspath", 'net.irisshaders.iris.vulkan.IrisVulkanSamplerCompatibilityTest', $OutputDirectory)
if ($DumpDirectory) { $samplerRunArgs += $DumpDirectory }
& (Join-Path $JdkPath 'bin/java.exe') @samplerRunArgs 2>&1 | Tee-Object -FilePath (Join-Path $OutputDirectory 'run.log')
$samplerExit = $LASTEXITCODE
$samplerInputs = @($samplerSource, (Join-Path $samplerProduction 'net/irisshaders/iris/vulkan/IrisVulkanShaderCompatibility.class'), (Join-Path $samplerProduction 'net/irisshaders/iris/vulkan/IrisVulkanShaderPruning.class'), (Join-Path $samplerProduction 'net/irisshaders/iris/vulkan/IrisVulkanGraphicsCompiler$Compiler.class'))
if ($DumpDirectory) { $samplerInputs += @(Get-ChildItem -LiteralPath $DumpDirectory -Filter '*.glsl' -File | Select-Object -ExpandProperty FullName) }
@($samplerInputs | Get-FileHash -Algorithm SHA256 | Select-Object Path,Hash) | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputDirectory 'input-sha256.json')
if ($samplerExit -ne 0) { throw "Sampler test execution failed: $samplerExit (see results.json and run.log)" }
