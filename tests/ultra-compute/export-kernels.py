"""Fresh immutable CPU fixtures from the selected 26.3 instance. Never launches Minecraft or a GPU device."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import zipfile


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--classpath-json', type=Path, required=True)
    parser.add_argument('--instance', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[2]
    instance = args.instance.resolve()
    out = args.output.resolve()
    if out.exists():
        raise ValueError('Output already exists; choose a new immutable fixture directory')
    pack = instance / 'shaderpacks/ComplementaryUnbound_r5.9.1.zip'
    options = pack.with_name(pack.name + '.txt')
    iris, = (instance / 'mods').glob('iris-vulkan-experimental-*.jar')
    if '+mc26.3' not in iris.name or 'alpha.11' not in iris.name:
        raise ValueError('Expected the frozen Alpha11 Minecraft26.3 candidate in the benchmark instance')
    original_hashes = {str(path): digest(path) for path in (pack, options, iris, instance / 'options.txt')}
    out.mkdir(parents=True)
    inputs = out / 'inputs'
    inputs.mkdir()
    for path in (pack, options, iris, instance / 'options.txt'):
        shutil.copyfile(path, inputs / path.name)
    with zipfile.ZipFile(pack) as archive:
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            target = (out / 'pack' / entry.filename).resolve()
            if not target.is_relative_to(out / 'pack'):
                raise ValueError('Archive entry escapes the fixture pack directory')
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(entry))
    game_options = dict(line.split(':', 1) for line in (instance / 'options.txt').read_text().splitlines() if ':' in line)
    mods = sorted(path.name for path in (instance / 'mods').glob('*.jar'))
    environment = {'MC_VERSION': '260300', 'IRIS_VERSION': '11105', 'MC_MIPMAP_LEVEL': game_options['mipmapLevels'],
                   'MC_GL_VERSION': '460', 'MC_GLSL_VERSION': '460', 'MC_OS_WINDOWS': '',
                   'MC_GL_VENDOR_NVIDIA': '', 'MC_GL_RENDERER_OTHER': '', 'IS_IRIS': '', 'IRIS_VULKAN': '',
                   'IRIS_INLINE_GLINT': '', 'MAX_COLOR_BUFFERS': '32'}
    if any(name.startswith('continuity-') for name in mods):
        environment['IRIS_HAS_CONNECTED_TEXTURES'] = ''
    environment_file = out / 'environment-input.json'
    environment_file.write_text(json.dumps(environment, indent=2), encoding='utf-8')
    dependencies = []
    for value in json.loads(args.classpath_json.read_text(encoding='utf-8')):
        path = Path(value)
        if path.suffix != '.jar' or path.name.startswith('iris-'):
            continue
        if '/mods/' in path.as_posix() and not path.name.startswith('sodium-'):
            continue
        dependencies.append(path)
    frozen_iris = inputs / iris.name
    with zipfile.ZipFile(frozen_iris) as archive:
        for name in archive.namelist():
            if name.startswith('META-INF/jars/') and name.endswith('.jar'):
                target = out / 'libraries' / Path(name).name
                target.parent.mkdir(exist_ok=True)
                target.write_bytes(archive.read(name))
                dependencies.append(target)
    classes = out / 'classes'
    classes.mkdir()
    classpath = ';'.join(str(path.resolve()) for path in [classes, frozen_iris, *dependencies])
    sources = [project / name for name in ('tests/ultra-audit/stubs/Iris.java',
        'tests/ultra-audit/ComplementaryUltraAudit.java', 'tests/ultra-compute/ComplementaryKernelExport.java')]

    def run(executable, label, arguments):
        argfile = out / (label + '.args')
        argfile.write_text('\n'.join('"' + str(value).replace('\\', '/') + '"' for value in arguments), encoding='utf-8')
        result = subprocess.run([str(args.jdk / 'bin' / executable), '@' + str(argfile)], capture_output=True, text=True)
        output = result.stdout + result.stderr
        (out / (label + '.txt')).write_text(output, encoding='utf-8')
        print(output, end='')
        if result.returncode:
            raise SystemExit(result.returncode)

    run('javac.exe', 'compile', ['-proc:none', '-encoding', 'UTF-8', '-cp', classpath, '-d', classes, *sources])
    run('java.exe', 'preprocess', ['--enable-native-access=ALL-UNNAMED', '-cp', classpath,
        'net.irisshaders.iris.audit.ComplementaryUltraAudit', out / 'pack/shaders', out / 'expanded',
        inputs / options.name, environment_file, '--compute-only'])
    run('java.exe', 'export', ['--enable-native-access=ALL-UNNAMED', '-cp', classpath,
        'net.irisshaders.iris.vulkan.ComplementaryKernelExport', out])
    kernels = json.loads((out / 'kernels.json').read_text())
    noise_name = kernels['properties'].get('texture.noise')
    noise = out / 'pack/shaders' / noise_name if noise_name else None
    noise_info = None
    if noise:
        data = noise.read_bytes()
        if data[:8] != b'\x89PNG\r\n\x1a\n':
            raise ValueError('Expected recorded pack noise PNG')
        width, height = struct.unpack('>II', data[16:24])
        noise_info = {'path': str(noise.relative_to(out)).replace('\\', '/'), 'sha256': digest(noise),
                      'dimensions': [width, height], 'scope': 'Exact pack custom noisetex bytes; no substituted random texture'}
    manifest = {'schemaVersion': 1, 'scope': 'CPU fixture preparation only; no GPU or Minecraft process launched',
        'sourceInstance': str(instance), 'inputSha256': original_hashes, 'mods': mods,
        'targetEnvironment': 'Explicit Windows/NVIDIA Vulkan26.3/Alpha11 context from benchmark configuration; not a live ProgramSet capture',
        'environmentInput': 'environment-input.json', 'resolvedEnvironment': 'expanded/inventory.json',
        'compilerJar': str(frozen_iris.relative_to(out)).replace('\\', '/'), 'compilerJarSha256': digest(frozen_iris),
        'runnerSources': {str(path.relative_to(project)): digest(path) for path in sources},
        'kernels': 'kernels.json', 'noise': noise_info,
        'eligibility': {'workgroupSharedMemoryEnabled': False, 'activeSubgroupLocalWorkgroupDependencies': False,
            'localIdSourceDeclaration': 'Present but unused; optimized SPIR-V must remove it',
            'writePosition': 'Exactly gl_GlobalInvocationID, at most one write to the selected output image',
            'parity0': {'read': 'floodfill_img', 'write': 'floodfill_img_copy'},
            'parity1': {'read': 'floodfill_img_copy', 'write': 'floodfill_img'},
            'dispatchGlobalExtent': [256, 128, 256], 'paddingAllowed': False,
            'limitations': ['Pack-specific fixture, not a generic automatic retile policy',
                           'Previous-position direct fetches can be out of bounds at camera-shift edges; record robustImageAccess or exclude/report undefined boundary cells',
                           'GPU equality and timing remain untested by this exporter']},
        'fixtureRequirements': {'parities': [0, 1], 'cameraCellShifts': [[0,0,0],[1,0,0],[-1,0,0],[0,1,0],[0,-1,0],[0,0,1],[0,0,-1],[1,-1,1]],
            'voxelMaterials': {'air':0,'solid':1,'torch':2,'verdant_froglight':8,'pearlescent_froglight':9,'tinted_glass_first':200,'colorwheel_bit':32768},
            'materialSources': ['pack/shaders/lib/voxelization/lightVoxelization.glsl','pack/shaders/lib/colors/blocklightColors.glsl'],
            'branches': ['Behind-camera cells beyond Manhattan distance16', 'Half-rate spreading posM.z above/below0.5',
                         'Air/non-solid/tinted diffusion', 'Solid reset', 'Emission/colorwheel', 'Clamp to0..1000'],
            'negativeControl': 'negative-skip-x0 deliberately leaves the x=0 output plane unchanged; require expected mismatch'}}
    for path, expected in original_hashes.items():
        if digest(Path(path)) != expected:
            raise AssertionError('Input changed during export: ' + path)
    manifest['artifactSha256'] = {str(path.relative_to(out)).replace('\\', '/'): digest(path)
        for path in sorted(out.rglob('*')) if path.is_file() and path.suffix in ('.csh','.glsl','.spv','.json','.png')}
    (out / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    print('Immutable fixture ready:', out)


if __name__ == '__main__':
    main()
