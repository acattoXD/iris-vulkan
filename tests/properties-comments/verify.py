"""Real JCPP regression and original-fails control; never launches a game."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--jdk', type=Path, required=True)
p.add_argument('--iris', type=Path, required=True)
p.add_argument('--classpath-json', type=Path, required=True)
p.add_argument('--pack', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
out = a.output.resolve()
out.mkdir(parents=True, exist_ok=False)
source = Path(__file__).resolve().parent
port = source.parents[1]
cp = [a.iris.resolve()]
cp.extend(Path(s) for s in json.loads(a.classpath_json.read_text()) if Path(s).is_file() and not Path(s).name.startswith('iris-'))
deps = out / 'dependencies'
deps.mkdir()
with zipfile.ZipFile(a.iris) as archive:
    for member in archive.namelist():
        if member.startswith('META-INF/jars/') and member.endswith('.jar'):
            dep = deps / Path(member).name
            dep.write_bytes(archive.read(member))
            cp.append(dep)
classes = out / 'classes'
classes.mkdir()
patched = out / 'patched'
patched.mkdir()
base = os.pathsep.join(map(str, cp))
testcp = str(classes) + os.pathsep + base
exe = '.exe' if os.name == 'nt' else ''

def run(name, arguments):
    result = subprocess.run(arguments, cwd=out, text=True, capture_output=True, encoding='utf-8', errors='replace')
    (out / (name + '.log')).write_text(result.stdout + result.stderr, encoding='utf-8')
    print(result.stdout + result.stderr)
    if result.returncode:
        raise RuntimeError(name + ' failed')

javac = str(a.jdk.resolve() / 'bin' / ('javac' + exe))
java = str(a.jdk.resolve() / 'bin' / ('java' + exe))
common = ['-proc:none', '-encoding', 'UTF-8', '--release', '25']
run('compile-test', [javac, *common, '-cp', base, '-d', str(classes), *map(str, source.glob('*.java'))])
main = 'net.irisshaders.iris.test.IrisPropertiesCommentTest'
run('original-control', [java, '-cp', testcp, main, '--original'])
production = port / 'common/src/main/java/net/irisshaders/iris/shaderpack/preprocessor/PropertiesPreprocessor.java'
run('compile-production', [javac, *common, '-cp', testcp, '-d', str(patched), str(production)])
run('patched', [java, '-cp', str(patched) + os.pathsep + testcp, main, str(a.pack.resolve())])
(out / 'result.json').write_text(json.dumps({'passed': True, 'originalTruncationReproduced': True,
    'packSha256': hashlib.sha256(a.pack.read_bytes()).hexdigest(),
    'productionSourceSha256': hashlib.sha256(production.read_bytes()).hexdigest(),
    'scope': 'Actual properties parser/JCPP, synthetic line endings/macros plus actual pack item mappings. No GPU/game.'}, indent=2) + '\n')
