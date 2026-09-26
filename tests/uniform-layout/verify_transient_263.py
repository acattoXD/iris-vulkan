"""Verify actual26.3 transient upload packing and ownership without a game/window."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--classpath-json', type=Path, required=True)
    parser.add_argument('--iris', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    port = Path(__file__).resolve().parents[2]
    out = args.output.resolve()
    if out.exists():
        raise ValueError('Use a fresh output directory to retain earlier evidence')
    out.mkdir(parents=True)
    libraries = [Path(p) for p in json.loads(args.classpath_json.read_text()) if not Path(p).name.startswith('iris-')]
    cp = ';'.join(str(p.resolve()) for p in [args.iris, *libraries])
    source = port / 'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanUniformSnapshot.java'
    test = Path(__file__).with_name('IrisVulkanTransientUniformTest.java')
    compile = subprocess.run([str(args.jdk / 'bin/javac.exe'), '-proc:none', '-cp', cp, '-d', str(out), str(source), str(test)], capture_output=True, text=True)
    (out / 'compile.log').write_text(compile.stdout + compile.stderr)
    if compile.returncode:
        raise RuntimeError(compile.stdout + compile.stderr)
    result = subprocess.run([str(args.jdk / 'bin/java.exe'), '--enable-native-access=ALL-UNNAMED', '-cp', str(out) + ';' + cp,
                             'net.irisshaders.iris.vulkan.IrisVulkanTransientUniformTest'], capture_output=True, text=True)
    (out / 'result.txt').write_text(result.stdout + result.stderr)
    (out / 'inputs.json').write_text(json.dumps({
        'sources': {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in [source, test]},
        'irisSha256': hashlib.sha256(args.iris.read_bytes()).hexdigest(),
        'passed': result.returncode == 0,
        'scope': 'Actual serializer plus synchronous-copy recorder and actual26.3 engine bytecode ownership contract; no GPU/window/FPS claim',
    }, indent=2) + '\n')
    print(result.stdout)
    if result.returncode:
        raise RuntimeError(result.stderr)


if __name__ == '__main__':
    main()
