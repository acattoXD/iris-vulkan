"""Replay real source/target/allocation paths with recorded GPU calls; never create a device/window."""
import argparse
import hashlib
import json
import subprocess
import zipfile
from pathlib import Path

PORT = Path(__file__).resolve().parents[2]
ROOT = PORT.parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--jdk', type=Path, default=Path('C:/Program Files/Java/jdk-25.0.3'))
p.add_argument('--fixtures', type=Path, default=PORT/'build/colortex8-liveness/comp-r5.9.1-high-ultra-02')
p.add_argument('--baseline', type=Path, default=PORT/'build/release/alpha12-reviewed-candidate/iris-vulkan-experimental-1.11.5-vulkan-alpha.12+mc26.3.jar')
a = p.parse_args()
out = a.output.resolve()
out.mkdir(parents=True, exist_ok=False)
classes = out/'classes'
classes.mkdir()
production = PORT/'common/build/classes/java/main'
cp = [str(classes), str(production), str(PORT/'common/build/classes/java/api'), str(PORT/'common/build/classes/java/vendored')]
cp += json.loads((ROOT/'build/optimization-26.3/uniform-tests/classpath.json').read_text())
dependencies = out/'dependencies'
dependencies.mkdir()
with zipfile.ZipFile(a.baseline) as archive:
    for entry in json.loads(archive.read('fabric.mod.json')).get('jars', []):
        name = entry['file']
        if not name.startswith('META-INF/jars/') or not name.endswith('.jar'):
            raise ValueError('Unexpected nested dependency path')
        destination = dependencies/Path(name).name
        with destination.open('xb') as file:
            file.write(archive.read(name))
        cp.append(str(destination))
sources = [PORT/'tests/ultra-audit/stubs/Iris.java', Path(__file__).parent/'stubs/IrisRenderSystem.java', Path(__file__).with_name('IrisVulkanTargetLivenessTest.java')]
command = [str(a.jdk/'bin/javac.exe'), '--release', '25', '-proc:none', '-classpath', ';'.join(cp), '-d', str(classes), *map(str, sources)]
r = subprocess.run(command, capture_output=True, text=True)
(out/'compile.log').write_text(r.stdout+r.stderr)
if r.returncode:
    raise RuntimeError(r.stdout+r.stderr)
inputs = [a.baseline, a.fixtures/'report.json', *sources]
inputs += list(dependencies.glob('*.jar'))
inputs += list((production/'net/irisshaders/iris/vulkan').glob('IrisVulkanTarget*.class'))
(out/'input.json').write_text(json.dumps({str(f): hashlib.sha256(f.read_bytes()).hexdigest() for f in inputs}, indent=2)+'\n')
command = [str(a.jdk/'bin/java.exe'), '-Djava.awt.headless=true', '-Xmx2G', '-classpath', ';'.join(cp),
           'net.irisshaders.iris.vulkan.IrisVulkanTargetLivenessTest', str(production), str(a.baseline.resolve()), str(a.fixtures.resolve()), str(out/'report.json')]
r = subprocess.run(command, capture_output=True, text=True)
(out/'runtime.log').write_text(r.stdout+r.stderr)
if r.returncode:
    print(r.stdout[-3000:]+r.stderr[-3000:])
    raise SystemExit(r.returncode)
print('\n'.join(line for line in r.stdout.splitlines() if line.startswith('PASS ')))
