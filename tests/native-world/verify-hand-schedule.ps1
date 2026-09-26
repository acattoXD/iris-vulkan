param(
    [Parameter(Mandatory = $true)][string]$JdkPath,
    [Parameter(Mandatory = $true)][string]$RuntimeClassPath
)
$ErrorActionPreference = 'Stop'
$handProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$handOutput = Join-Path $handProject 'build/hand-schedule-tests'
$handRuntime = if ([IO.Path]::IsPathRooted($RuntimeClassPath)) { $RuntimeClassPath } else { Join-Path $handProject $RuntimeClassPath }
New-Item -ItemType Directory -Path $handOutput -Force | Out-Null
$handEntries = @($handOutput)
foreach ($relative in @('common/build/classes/java/main', 'common/build/classes/java/api', 'common/build/classes/java/vendored', 'common/build/classes/java/headers')) {
    $handEntries += Join-Path $handProject $relative
}
$handEntries += if ([IO.Path]::GetExtension($handRuntime) -eq '.json') {
    Get-Content -LiteralPath $handRuntime -Raw | ConvertFrom-Json
} else { Get-Content -LiteralPath $handRuntime }
$handClassPath = ($handEntries | ForEach-Object { $_.Replace('\', '/') }) -join ';'
$handTests = @('IrisVulkanHandScheduleTest', 'IrisVulkanDeferredDepthRegression', 'IrisVulkanMixinContractTest')
$handArguments = @('-proc:none', '-encoding', 'UTF-8', '-classpath', ('"' + $handClassPath + '"'), '-d', ('"' + $handOutput.Replace('\', '/') + '"'))
$handArguments += $handTests | ForEach-Object { '"' + (Join-Path $PSScriptRoot ($_ + '.java')).Replace('\', '/') + '"' }
$handCompileFile = Join-Path $handOutput 'compile.args'
[IO.File]::WriteAllLines($handCompileFile, $handArguments)
& (Join-Path $JdkPath 'bin/javac.exe') "@$handCompileFile"
if ($LASTEXITCODE -ne 0) { throw 'Hand schedule regression compilation failed' }
foreach ($handTest in $handTests) {
    $handRunFile = Join-Path $handOutput ($handTest + '.args')
    [IO.File]::WriteAllLines($handRunFile, @('-classpath', ('"' + $handClassPath + '"'), ('net.irisshaders.iris.vulkan.' + $handTest),
        ('"' + (Join-Path $handProject 'common/src/main/java/net/irisshaders/iris/mixin/vulkan').Replace('\', '/') + '"')))
    & (Join-Path $JdkPath 'bin/java.exe') "@$handRunFile"
    if ($LASTEXITCODE -ne 0) { throw "Hand schedule regression failed: $handTest" }
}
