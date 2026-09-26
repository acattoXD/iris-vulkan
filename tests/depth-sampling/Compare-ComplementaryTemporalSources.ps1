param(
    [string]$GlDirectory = 'fabric/run-opengl-world-reference/patched_shaders',
    [string]$NativeDirectory = 'fabric/run-vulkan-pack-audit/active-programs/screen-prepared',
    [string]$Output = 'build/complementary-temporal-source-audit.json'
)

function Get-FunctionSources([string]$Code, [string]$Function) {
    $codeOnly = [regex]::Replace($Code, '/\*.*?\*/|//[^\r\n]*', '', [Text.RegularExpressions.RegexOptions]::Singleline)
    $declaration = [regex]::new('\b(?:float|bool|void|vec[234])\s+' + [regex]::Escape($Function) + '\s*\([^;]*?\)\s*\{')
    $result = @()
    foreach ($match in $declaration.Matches($codeOnly)) {
        $depth = 1
        $end = $match.Index + $match.Length
        while ($end -lt $codeOnly.Length -and $depth -gt 0) {
            if ($codeOnly[$end] -eq '{') { $depth++ }
            elseif ($codeOnly[$end] -eq '}') { $depth-- }
            $end++
        }
        if ($depth -ne 0) { throw "Unclosed $Function" }
        $body = $codeOnly.Substring($match.Index, $end - $match.Index)
        # Compare the pack algorithm, treating the old named depth adaptation as transport.
        foreach ($sampler in @('depthtex0','depthtex1','depthtex2','gdepthtex')) {
            foreach ($operation in @('texture','texelFetch','textureGather')) {
                $body = $body.Replace(('iris_vulkan_' + $operation + '_' + $sampler + '('), ($operation + '(' + $sampler + ','))
            }
        }
        foreach ($sampler in @('shadowtex0','shadowtex1')) {
            $body = $body.Replace(('iris_vulkan_shadow_compare_' + $sampler + '('), ('texture(' + $sampler + ','))
            $body = $body.Replace(('iris_vulkan_shadow_compare_' + $sampler + '_lod('), ('textureLod(' + $sampler + ','))
        }
        $result += [regex]::Replace($body, '\s+', '')
    }
    return ,$result
}

$checks = @(
    @{gl='001_deferred1.fsh'; native='deferred1.fsh'; functions=@('GetVolumetricClouds','GetClouds','GetCloudNoise','GetShadowOnCloud','GetAmbientOcclusion')},
    @{gl='007_composite6.fsh'; native='composite6.fsh'; functions=@('Reprojection','DoTAA','NeighbourhoodClamping','textureCatmullRom','ClipAABB')}
)
$rows = @()
foreach ($check in $checks) {
    $glPath = Join-Path $GlDirectory $check.gl
    $nativePath = Join-Path $NativeDirectory $check.native
    $gl = Get-Content -LiteralPath $glPath -Raw
    $native = Get-Content -LiteralPath $nativePath -Raw
    foreach ($function in $check.functions) {
        $a = Get-FunctionSources $gl $function
        $b = Get-FunctionSources $native $function
        $same = $a.Count -gt 0 -and $a.Count -eq $b.Count -and (($a -join "`n") -ceq ($b -join "`n"))
        $rows += [pscustomobject]@{function=$function; gl=$glPath; native=$nativePath; glOverloads=$a.Count; nativeOverloads=$b.Count; identicalAfterSamplerTransportNormalization=$same}
    }
}
$report = [pscustomobject]@{
    scope='Existing HIGH profile GL/native dumps: exact function-body comparison after removing whitespace/comments and normalizing named depth/shadow wrappers. This does not verify current live uniform values/history textures or prove wrapper equivalence.'
    checks=$rows
}
$report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $Output
$rows | Format-Table function,glOverloads,nativeOverloads,identicalAfterSamplerTransportNormalization -AutoSize
