param(
    [Parameter(Mandatory = $true)][string]$JdkPath,
    [Parameter(Mandatory = $true)][string]$ShaderPack,
    [string]$RuntimeClassPath = 'build/isolated-runtime-classpath.txt'
)
$ErrorActionPreference = 'Stop'
$tagProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$tagOutput = Join-Path $tagProject 'build/nametag-tests'
$tagRuntime = if ([IO.Path]::IsPathRooted($RuntimeClassPath)) { $RuntimeClassPath } else { Join-Path $tagProject $RuntimeClassPath }
$tagEntries = @($tagOutput)
foreach ($relative in @('common/build/classes/java/main', 'common/build/classes/java/api', 'common/build/classes/java/vendored', 'common/build/classes/java/headers')) { $tagEntries += Join-Path $tagProject $relative }
$tagEntries += Get-Content -LiteralPath $tagRuntime
$tagCp = ($tagEntries | ForEach-Object { $_.Replace('\', '/') }) -join ';'
New-Item -ItemType Directory -Path $tagOutput -Force | Out-Null
$tagSources = @('tests/storage-2d/stubs/Iris.java', 'tests/nametags/IrisVulkanNameTagTest.java', 'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanEntityContext.java', 'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanWorldPipelineStates.java')
$tagCompile = @('-proc:none', '-encoding', 'UTF-8', '-classpath', ('"' + $tagCp + '"'), '-d', ('"' + $tagOutput.Replace('\', '/') + '"'))
$tagCompile += $tagSources | ForEach-Object { '"' + (Join-Path $tagProject $_).Replace('\', '/') + '"' }
$tagArgs = Join-Path $tagOutput 'compile.args'
[IO.File]::WriteAllLines($tagArgs, $tagCompile)
& (Join-Path $JdkPath 'bin/javac.exe') "@$tagArgs"
if ($LASTEXITCODE -ne 0) { throw 'Nametag regression compilation failed' }
$tagArgs = Join-Path $tagOutput 'run.args'
[IO.File]::WriteAllLines($tagArgs, @('--enable-native-access=ALL-UNNAMED', '-classpath', ('"' + $tagCp + '"'), 'net.irisshaders.iris.vulkan.IrisVulkanNameTagTest', ('"' + [IO.Path]::GetFullPath($ShaderPack).Replace('\', '/') + '"')))
& (Join-Path $JdkPath 'bin/java.exe') "@$tagArgs" | Tee-Object -FilePath (Join-Path $tagOutput 'result.txt')
if ($LASTEXITCODE -ne 0) { throw 'Nametag regression failed' }
