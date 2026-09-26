"""CPU-only alpha9/candidate uniform serialization comparison; does not launch Minecraft."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--classpath-json', type=Path, required=True, help='JSON list of resolved Minecraft/Sodium/library JAR paths')
    parser.add_argument('--baseline-sha256', default='369e83b533c93818a29f0280862d1fd848fe5b1115cbb3f8a169bd5344505315')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[3]
    out = (args.output or root / 'build/alpha10-serialization').resolve()
    out.mkdir(parents=True, exist_ok=True)
    baseline = args.baseline.resolve()
    baseline_hash = hashlib.sha256(baseline.read_bytes()).hexdigest()
    if baseline_hash != args.baseline_sha256:
        raise ValueError('Frozen baseline JAR hash mismatch')
    dependencies = []
    for value in json.loads(args.classpath_json.read_text(encoding='utf-8')):
        path = Path(value)
        # Never let another Iris or diagnostic mod silently supply baseline classes.
        if path.suffix != '.jar' or path.name.startswith('iris-'):
            continue
        if '/mods/' in path.as_posix() and not path.name.startswith('sodium-'):
            continue
        dependencies.append(path)
    with zipfile.ZipFile(baseline) as archive:
        for name in archive.namelist():
            if name.startswith('META-INF/jars/') and name.endswith('.jar'):
                path = out / 'libraries' / Path(name).name
                path.parent.mkdir(exist_ok=True)
                path.write_bytes(archive.read(name))
                dependencies.append(path)
            if name.startswith('net/irisshaders/iris/vulkan/IrisVulkanUniformSnapshot') and name.endswith('.class'):
                path = out / 'baseline-raw' / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(archive.read(name))
    for directory in ['fixture', 'candidate', 'baseline', 'candidate-raw']:
        (out / directory).mkdir(exist_ok=True)

    def classpath(paths):
        return ';'.join(str(path.resolve()) for path in paths)

    def run(executable, label, arguments):
        argfile = out / (label + '.args')
        argfile.write_text('\n'.join('"' + str(value).replace('\\', '/') + '"' for value in arguments), encoding='utf-8')
        result = subprocess.run([str(args.jdk / 'bin' / executable), '@' + str(argfile)], capture_output=True, text=True)
        output = result.stdout + result.stderr
        (out / (label + '.txt')).write_text(output, encoding='utf-8')
        print(label, result.returncode, result.stdout.strip())
        if result.returncode:
            raise RuntimeError(output)
        return output

    fixtures = [root / ('tests/optimization/serialization/' + file) for file in
                ['NativeSerializationFixture.java', 'InstrumentSnapshot.java', 'NativeSerializationBenchmark.java']]
    fixtures += [root / 'tests/native-world/mineek-stubs/Iris.java']
    cp = classpath([baseline, *dependencies])
    run('javac.exe', 'fixture-compile', ['-proc:none', '-encoding', 'UTF-8', '-cp', cp, '-d', out / 'fixture', *fixtures])
    production = [root / 'common/src/main/java/net/irisshaders/iris/uniforms/custom/CustomUniforms.java',
                  root / 'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanUniformSnapshot.java']
    run('javac.exe', 'candidate-compile', ['-proc:none', '-encoding', 'UTF-8', '-cp', cp, '-d', out / 'candidate-raw', *production])
    contracts = [root / 'tests/optimization/serialization/CachedStd140Contract.java',
                 root / 'tests/uniform-layout/IrisVulkanLayoutCacheProviderRegression.java',
                 root / 'tests/native-world/IrisVulkanFrameHistoryRegression.java',
                 root / 'tests/uniform-layout/CustomUniformUpdatePlanTest.java']
    run('javac.exe', 'contracts-compile', ['-proc:none', '-encoding', 'UTF-8', '-cp', classpath([out / 'candidate-raw', baseline, *dependencies]), '-d', out / 'fixture', *contracts])
    class_name = 'net/irisshaders/iris/vulkan/IrisVulkanUniformSnapshot.class'
    benchmark = {}
    for variant in ['baseline', 'candidate']:
        run('java.exe', variant + '-instrument', ['-cp', classpath([out / 'fixture', *dependencies]), 'InstrumentSnapshot',
                                                out / (variant + '-raw') / class_name, out / variant / class_name])
        variant_cp = classpath([out / variant, out / 'fixture', out / (variant + '-raw'), baseline, *dependencies])
        run('java.exe', variant + '-fixture', ['--enable-native-access=ALL-UNNAMED', '-cp', variant_cp,
                                             'net.irisshaders.iris.vulkan.NativeSerializationFixture', out / (variant + '-evidence')])
        text = run('java.exe', variant + '-allocation', ['--enable-native-access=ALL-UNNAMED', '-cp', variant_cp,
                                                       'net.irisshaders.iris.vulkan.NativeSerializationBenchmark'])
        benchmark[variant] = json.loads(next(line for line in text.splitlines() if line.startswith('{')))
    matched = {}
    for name in ['captures.bin', 'supplier-calls.txt']:
        before = (out / 'baseline-evidence' / name).read_bytes()
        after = (out / 'candidate-evidence' / name).read_bytes()
        if before != after:
            raise AssertionError(name + ' changed')
        matched[name] = {'bytes': len(before), 'sha256': hashlib.sha256(before).hexdigest()}
    if benchmark['candidate']['allocatedBytes'] >= benchmark['baseline']['allocatedBytes'] / 10:
        raise AssertionError('Expected substantial reduction in isolated per-field allocations')
    candidate_cp = classpath([out / 'candidate', out / 'fixture', out / 'candidate-raw', baseline, *dependencies])
    for label, test in [('cached-std140', 'net.irisshaders.iris.uniforms.custom.CachedStd140Contract'),
                        ('existing-768', 'net.irisshaders.iris.vulkan.IrisVulkanLayoutCacheProviderRegression'),
                        ('frame-history', 'net.irisshaders.iris.vulkan.IrisVulkanFrameHistoryRegression'),
                        ('dependency-plans', 'net.irisshaders.iris.uniforms.custom.CustomUniformUpdatePlanTest')]:
        run('java.exe', label, ['--enable-native-access=ALL-UNNAMED', '-cp', candidate_cp, test])
    report = {'baseline_sha256': baseline_hash, 'matched': matched, 'allocation_microbenchmark': benchmark,
              'scope': 'CPU serialization only; identical source-accessor substitutions for model-view, fog, and render phase; no GPU or FPS claim',
              'production_source_sha256': {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in production}}
    (out / 'report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
