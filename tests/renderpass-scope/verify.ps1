param(
    [Parameter(Mandatory = $true)][string]$JdkPath,
    [string]$RuntimeClassPath = 'build/isolated-runtime-classpath.txt'
)
$ErrorActionPreference = 'Stop'
$scopeProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$scopeOutput = Join-Path $scopeProject 'build/renderpass-scope-tests'
$scopeActual = (Join-Path $scopeOutput 'actual').Replace('\', '/')
$scopeStubs = (Join-Path $scopeOutput 'stubs').Replace('\', '/')
$scopeRuntime = if ([IO.Path]::IsPathRooted($RuntimeClassPath)) { $RuntimeClassPath } else { Join-Path $scopeProject $RuntimeClassPath }
$scopeEntries = @()
foreach ($relative in @('common/build/classes/java/main', 'common/build/classes/java/api', 'common/build/classes/java/vendored', 'common/build/classes/java/headers')) {
    $scopeEntries += Join-Path $scopeProject $relative
}
$scopeEntries += Get-Content -LiteralPath $scopeRuntime
$scopeDependencies = ($scopeEntries | ForEach-Object { $_.Replace('\', '/') }) -join ';'
New-Item -ItemType Directory -Path $scopeActual,$scopeStubs -Force | Out-Null
$scopeArgs = Join-Path $scopeOutput 'actual.args'
[IO.File]::WriteAllLines($scopeArgs, @('-proc:none', '-encoding', 'UTF-8', '-classpath', ('"' + $scopeDependencies + '"'), '-d', ('"' + $scopeActual + '"'), ('"' + (Join-Path $scopeProject 'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanRenderPassBindings.java').Replace('\', '/') + '"')))
& (Join-Path $JdkPath 'bin/javac.exe') "@$scopeArgs"
if ($LASTEXITCODE -ne 0) { throw 'Production binder compilation failed' }
$scopeCp = $scopeStubs + ';' + $scopeActual + ';' + $scopeDependencies
$scopeCompile = @('-proc:none', '-encoding', 'UTF-8', '-classpath', ('"' + $scopeCp + '"'), '-d', ('"' + $scopeStubs + '"'))
$scopeCompile += @(& rg --files $PSScriptRoot -g '*.java' | ForEach-Object { '"' + $_.Replace('\', '/') + '"' })
$scopeArgs = Join-Path $scopeOutput 'test.args'
[IO.File]::WriteAllLines($scopeArgs, $scopeCompile)
& (Join-Path $JdkPath 'bin/javac.exe') "@$scopeArgs"
if ($LASTEXITCODE -ne 0) { throw 'Ownership fixture compilation failed' }
$scopeArgs = Join-Path $scopeOutput 'run.args'
[IO.File]::WriteAllLines($scopeArgs, @('--enable-native-access=ALL-UNNAMED', '-classpath', ('"' + $scopeCp + '"'), 'net.irisshaders.iris.vulkan.IrisVulkanRenderPassScopeTest'))
& (Join-Path $JdkPath 'bin/java.exe') "@$scopeArgs"
if ($LASTEXITCODE -ne 0) { throw 'Ownership regression failed' }
