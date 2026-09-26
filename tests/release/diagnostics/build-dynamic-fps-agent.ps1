param([Parameter(Mandatory = $true)][string]$JdkPath)
$ErrorActionPreference = 'Stop'
$fpsAgentProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$fpsAgentOutput = Join-Path $fpsAgentProject 'build/fps-cap-audit/agent'
$fpsAgentClasses = Join-Path $fpsAgentOutput 'classes'
New-Item -ItemType Directory -Path $fpsAgentClasses -Force | Out-Null
& (Join-Path $JdkPath 'bin/javac.exe') --add-modules jdk.attach -d $fpsAgentClasses (Join-Path $PSScriptRoot 'DynamicFpsReadOnlyAgent.java') (Join-Path $PSScriptRoot 'AttachReadOnlyAgent.java')
if ($LASTEXITCODE -ne 0) { throw 'Read-only FPS agent compilation failed' }
$fpsAgentManifest = Join-Path $fpsAgentOutput 'MANIFEST.MF'
[IO.File]::WriteAllLines($fpsAgentManifest, @('Manifest-Version: 1.0', 'Agent-Class: iris.diagnostics.DynamicFpsReadOnlyAgent', 'Can-Redefine-Classes: false', 'Can-Retransform-Classes: false', ''), [Text.Encoding]::ASCII)
$fpsAgentJar = Join-Path $fpsAgentOutput 'dynamic-fps-read-only-agent.jar'
& (Join-Path $JdkPath 'bin/jar.exe') --create --file $fpsAgentJar --manifest $fpsAgentManifest -C $fpsAgentClasses iris/diagnostics
if ($LASTEXITCODE -ne 0) { throw 'Read-only FPS agent packaging failed' }
Write-Output $fpsAgentJar
