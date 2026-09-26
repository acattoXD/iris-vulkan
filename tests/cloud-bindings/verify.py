"""Replay actual cloud uniform binding using Alpha20 as a failing control; no GPU."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--jdk', type=Path, required=True)
p.add_argument('--control-jar', type=Path, required=True)
p.add_argument('--classpath-json', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--textures', action='store_true', help='Replay the complete cloud resource path using Alpha21 as control')
a = p.parse_args()
out = a.output.resolve()
out.mkdir(parents=True, exist_ok=False)
source = Path(__file__).resolve().parent
port = source.parents[1]
dependencies = [a.control_jar.resolve()]
dependencies.extend(Path(s) for s in json.loads(a.classpath_json.read_text())
                    if Path(s).is_file() and not Path(s).name.startswith('iris-'))
deps = out / 'dependencies'
deps.mkdir()
with zipfile.ZipFile(a.control_jar) as archive:
    for member in archive.namelist():
        if member.startswith('META-INF/jars/') and member.endswith('.jar'):
            dep = deps / Path(member).name
            dep.write_bytes(archive.read(member))
            dependencies.append(dep)
classes = out / 'classes'
patched = out / 'patched'
classes.mkdir()
patched.mkdir()
cp = os.pathsep.join(map(str, dependencies))
testcp = str(classes) + os.pathsep + cp
exe = '.exe' if os.name == 'nt' else ''

def run(name, arguments):
    result = subprocess.run(arguments, cwd=out, capture_output=True, text=True, encoding='utf-8', errors='replace')
    (out / (name + '.log')).write_text(result.stdout + result.stderr, encoding='utf-8')
    print(result.stdout + result.stderr)
    if result.returncode:
        raise RuntimeError(name + ' failed')

javac = str(a.jdk.resolve() / 'bin' / ('javac' + exe))
java = str(a.jdk.resolve() / 'bin' / ('java' + exe))
flags = ['-proc:none', '-encoding', 'UTF-8', '--release', '25']
test = source / ('CloudTextureContract.java' if a.textures else 'CloudBindingContract.java')
tests = [test]
if a.textures:
    tests.append(port / 'tests/texture-bindings/TextureBindingContract.java')
    service = classes / 'META-INF/services/net.irisshaders.iris.platform.IrisPlatformHelpers'
    service.parent.mkdir(parents=True)
    service.write_text('net.irisshaders.iris.vulkan.TextureBindingContract$CpuPlatform\n')
run('compile-test', [javac, *flags, '-cp', cp, '-d', str(classes), *map(str, tests)])
main = 'net.irisshaders.iris.vulkan.' + test.stem
run('original-control', [java, '--enable-native-access=ALL-UNNAMED', '-cp', testcp, main, '--original'])
production = port / 'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanRenderPassBindings.java'
run('compile-production', [javac, *flags, '-cp', testcp, '-d', str(patched), str(production)])
run('patched', [java, '--enable-native-access=ALL-UNNAMED', '-cp', str(patched) + os.pathsep + testcp, main])
(out / 'result.json').write_text(json.dumps({
    'passed': True, 'originalMissingAliasReproduced': True,
    'controlSha256': hashlib.sha256(a.control_jar.read_bytes()).hexdigest(),
    'productionSourceSha256': hashlib.sha256(production.read_bytes()).hexdigest(),
    'scope': 'Actual binder and Minecraft cloud pipeline/producer ABI; recording pass/resources; no game/GPU.',
    'textures': a.textures
}, indent=2) + '\n')
