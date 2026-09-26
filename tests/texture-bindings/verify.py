"""Run compiled 26.3 texture-binding selectors without a launcher, game, or GPU."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--classpath-json', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    port = Path(__file__).resolve().parents[2]
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    classes = out / 'classes'
    classes.mkdir(exist_ok=True)
    production = port / 'common/build/classes/java/main'
    binding = production / 'net/irisshaders/iris/vulkan/IrisVulkanRenderPassBindings.class'
    test = Path(__file__).with_name('TextureBindingContract.java')
    libraries = [Path(p) for p in json.loads(args.classpath_json.read_text())
                 if not Path(p).name.startswith('iris-')]
    entries = [production, port / 'common/build/classes/java/api',
               port / 'common/build/classes/java/vendored', port / 'common/build/classes/java/headers', *libraries]
    cp = ';'.join(str(p.resolve()) for p in entries if p.exists())
    compiled = subprocess.run([str(args.jdk / 'bin/javac.exe'), '-proc:none', '-encoding', 'UTF-8',
                               '-cp', cp, '-d', str(classes), str(test)], capture_output=True, text=True)
    (out / 'compile.log').write_text(compiled.stdout + compiled.stderr)
    if compiled.returncode:
        raise RuntimeError(compiled.stdout + compiled.stderr)
    service = classes / 'META-INF/services/net.irisshaders.iris.platform.IrisPlatformHelpers'
    service.parent.mkdir(parents=True, exist_ok=True)
    service.write_text('net.irisshaders.iris.vulkan.TextureBindingContract$CpuPlatform\n')
    result = subprocess.run([str(args.jdk / 'bin/java.exe'), '--enable-native-access=ALL-UNNAMED',
                             '-cp', str(classes) + ';' + cp,
                             'net.irisshaders.iris.vulkan.TextureBindingContract', str(production)],
                            cwd=out, capture_output=True, text=True)
    (out / 'result.txt').write_text(result.stdout + result.stderr)
    (out / 'inputs.json').write_text(json.dumps({
        'productionClass': str(binding), 'productionClassSha256': sha(binding),
        'testSha256': sha(test), 'runnerSha256': sha(Path(__file__)),
        'passed': result.returncode == 0,
        'scope': 'Actual compiled private selectors, fake RenderPearl resources, replayed map, isolated CPU platform provider; no production stubs or GPU/game execution',
        'limitations': 'Does not execute WorldRenderPass or validate GPU pixels, terrain visibility, resource uploads, sampler behavior on GPU, FPS, or server interactions',
    }, indent=2) + '\n')
    print(result.stdout)
    if result.returncode:
        raise RuntimeError(result.stderr)


if __name__ == '__main__':
    main()
