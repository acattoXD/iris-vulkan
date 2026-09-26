param(
    [string]$JdkPath = 'C:/Program Files/Java/jdk-25.0.3',
    [string]$OutputDirectory,
    [string]$DumpDirectory,
    [string]$ClasspathFile
)
$ErrorActionPreference = 'Stop'
$linePort = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$lineWorkspace = (Resolve-Path (Join-Path $linePort '../..')).Path
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $lineWorkspace 'build/optimization-26.3/photon-line-input-01' }
if (-not $DumpDirectory) { $DumpDirectory = Join-Path $lineWorkspace 'build/port-26.3/motion-alpha17-photon-01/iris-vulkan-dumps' }
if (-not $ClasspathFile) { $ClasspathFile = Join-Path $lineWorkspace 'build/optimization-26.3/uniform-tests/classpath.json' }
$lineClasses = Join-Path $OutputDirectory 'classes'
$lineProduction = Join-Path $linePort 'common/build/classes/java/main'
$lineSource = Join-Path $PSScriptRoot 'IrisVulkanPhotonLineInputTest.java'
$lineDependencies = Get-Content -Raw -LiteralPath $ClasspathFile | ConvertFrom-Json
$lineClasspath = (@($lineProduction) + @($lineDependencies)) -join ';'
New-Item -ItemType Directory -Path $lineClasses -Force | Out-Null
& (Join-Path $JdkPath 'bin/javac.exe') --release 25 -proc:none -encoding UTF-8 -classpath $lineClasspath -d $lineClasses $lineSource 2>&1 | Tee-Object -FilePath (Join-Path $OutputDirectory 'javac.log')
if ($LASTEXITCODE -ne 0) { throw "Photon line test compilation failed: $LASTEXITCODE" }
& (Join-Path $JdkPath 'bin/java.exe') '--enable-native-access=ALL-UNNAMED' '-Djava.awt.headless=true' -classpath "$lineClasses;$lineClasspath" net.irisshaders.iris.vulkan.IrisVulkanPhotonLineInputTest $DumpDirectory $OutputDirectory 2>&1 | Tee-Object -FilePath (Join-Path $OutputDirectory 'run.log')
$lineExit = $LASTEXITCODE
$lineInputs = @($lineSource)
foreach ($lineClass in @('IrisVulkanShaderPruning', 'IrisVulkanShaderResources', 'IrisVulkanVertexFormats', 'IrisVulkanGraphicsCompiler', 'IrisVulkanGraphicsCompiler$Compiler')) { $lineInputs += Join-Path $lineProduction "net/irisshaders/iris/vulkan/$lineClass.class" }
$lineInputs += @(Get-ChildItem -LiteralPath $DumpDirectory -Filter 'iris_lines_minecraft_pipeline_lines_translucent.*.glsl' -File | Select-Object -ExpandProperty FullName)
@($lineInputs | Get-FileHash -Algorithm SHA256 | Select-Object Path,Hash) | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputDirectory 'input-sha256.json')
if ($lineExit -ne 0) { throw "Photon line test execution failed: $lineExit" }
