"""Compile the compute candidate against immutable Alpha10; run CPU-only descriptor ABI contracts."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--classpath-json', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    out = root / 'build/alpha11-compute-contract'
    out.mkdir(parents=True, exist_ok=True)
    frozen = root / 'build/release/friends-alpha10/iris-vulkan-experimental-1.11.5-vulkan-alpha.10+mc26.3.jar'
    frozen_hash = hashlib.sha256(frozen.read_bytes()).hexdigest()
    assert frozen_hash == 'daf7494dcadada8ae93799ede0f92e0e3e991afa42e9df66bbfbe97c9e03ed06'
    dependencies = []
    for value in json.loads(args.classpath_json.read_text(encoding='utf-8')):
        path = Path(value)
        if path.suffix != '.jar' or path.name.startswith('iris-'):
            continue
        if '/mods/' in path.as_posix() and not path.name.startswith('sodium-'):
            continue
        dependencies.append(path)
    with zipfile.ZipFile(frozen) as archive:
        for name in archive.namelist():
            if name.startswith('META-INF/jars/') and name.endswith('.jar'):
                path = out / 'libraries' / Path(name).name
                path.parent.mkdir(exist_ok=True)
                path.write_bytes(archive.read(name))
                dependencies.append(path)
    classpath = ';'.join(str(path.resolve()) for path in [out, frozen, *dependencies])
    sources = [root / name for name in (
        'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanComputeExecutor.java',
        'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanComputeBindings.java',
        'tests/ultra-compute/ComputeBindingContract.java',
        'tests/ultra-compute/ComputeDispatchOwnershipContract.java',
        'tests/ultra-compute/IrisVulkanComputeContractTest.java',
    )]

    def run(executable, label, arguments):
        argfile = out / (label + '.args')
        argfile.write_text('\n'.join('"' + str(value).replace('\\', '/') + '"' for value in arguments), encoding='utf-8')
        result = subprocess.run([str(args.jdk / 'bin' / executable), '@' + str(argfile)], capture_output=True, text=True)
        text = result.stdout + result.stderr
        (out / (label + '.txt')).write_text(text, encoding='utf-8')
        print(text, end='')
        if result.returncode:
            raise SystemExit(result.returncode)

    run('javac.exe', 'compile', ['-proc:none', '-encoding', 'UTF-8', '-cp', classpath, '-d', out, *sources])
    for contract in ('ComputeBindingContract', 'ComputeDispatchOwnershipContract', 'IrisVulkanComputeContractTest'):
        run('java.exe', contract, ['--enable-native-access=ALL-UNNAMED', '-cp', classpath, 'net.irisshaders.iris.vulkan.' + contract])
    (out / 'manifest.json').write_text(json.dumps({'baseline': str(frozen), 'sha256': frozen_hash,
        'sources': {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources},
        'scope': 'CPU ABI + real shaderc/SPIR-V; no game or GPU launch'}, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
