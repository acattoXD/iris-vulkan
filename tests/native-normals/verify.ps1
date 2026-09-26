param([Parameter(Mandatory = $true)][string]$JdkPath,[string]$RuntimeClassPath='build/isolated-runtime-classpath.txt')
$ErrorActionPreference='Stop'
$normalProject=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$normalOutput=Join-Path $normalProject 'build/native-normal-tests'
New-Item -ItemType Directory -Path $normalOutput -Force | Out-Null
$normalEntries=@($normalOutput)
foreach($relative in @('common/build/classes/java/main','common/build/classes/java/api','common/build/classes/java/vendored')){$normalEntries+=Join-Path $normalProject $relative}
$normalEntries+=Get-Content -LiteralPath (Join-Path $normalProject $RuntimeClassPath)
$normalClassPath=($normalEntries|ForEach-Object{$_.Replace('\','/')})-join ';'
$normalSources=@('tests/native-normals/stubs/Iris.java','common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanVertexFormats.java','common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanShaderResources.java','common/src/main/java/net/irisshaders/iris/mixin/vulkan/VKOnly_MixinRenderType_Normals.java','common/src/main/java/net/irisshaders/iris/mixin/vulkan/VKOnly_MixinBufferBuilder_Normals.java','common/src/main/java/net/irisshaders/iris/mixin/vulkan/VKOnly_ByteBufferBuilderNormalAccess.java','tests/native-normals/IrisVulkanGlyphNormalTest.java','tests/native-world/IrisVulkanMovingBlockContractTest.java')
$normalCompile=@('-proc:none','-classpath',('"'+$normalClassPath+'"'),'-d',('"'+$normalOutput.Replace('\','/')+'"'))
$normalCompile+=$normalSources|ForEach-Object{'"'+(Join-Path $normalProject $_).Replace('\','/')+'"'}
$normalArgsFile=Join-Path $normalOutput 'compile.args';[IO.File]::WriteAllLines($normalArgsFile,$normalCompile)
& (Join-Path $JdkPath 'bin/javac.exe') "@$normalArgsFile"
if($LASTEXITCODE-ne 0){throw 'Native normal regression compilation failed'}
foreach($normalTest in @('IrisVulkanGlyphNormalTest','IrisVulkanMovingBlockContractTest')){
 $normalRunFile=Join-Path $normalOutput ($normalTest+'.args')
 [IO.File]::WriteAllLines($normalRunFile,@('--enable-native-access=ALL-UNNAMED','--sun-misc-unsafe-memory-access=allow','-classpath',('"'+$normalClassPath+'"'),('net.irisshaders.iris.vulkan.'+$normalTest)))
 $normalPrevious=$ErrorActionPreference
 try{$ErrorActionPreference='Continue';& (Join-Path $JdkPath 'bin/java.exe') "@$normalRunFile" 2>&1|Tee-Object -FilePath (Join-Path $normalOutput ($normalTest+'.txt'));$normalCode=$LASTEXITCODE}finally{$ErrorActionPreference=$normalPrevious}
 if($normalCode-ne 0){throw "Native normal regression failed: $normalTest"}
}
