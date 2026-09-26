"""Reproduce the rejected MRT experiment's headless correctness checks."""
import hashlib,json,subprocess
from pathlib import Path
PORT=Path(__file__).resolve().parents[2];WORK=PORT.parents[1]
run=WORK/'build/port-26.3/custom-vulkan-17-opengl'
args=[x[1:-1] for x in (run/'launch.args').read_text().splitlines() if x]
runtime=args[args.index('-classpath')+1].split(';')+[str(p) for p in (run/'mods').glob('*.jar')]
out=PORT/'build/depth-mrt-rejected-tests';out.mkdir(parents=True,exist_ok=True)
main=PORT/'common/build/classes/java/main'
cp=[str(main),str(PORT/'common/build/classes/java/api'),str(PORT/'common/build/classes/java/vendored')]+runtime
fixtures=Path(__file__).with_name('fixtures')
production=[fixtures/n for n in ('IrisVulkanDepthCopy.java','IrisVulkanGbufferTargets.java','IrisNativeVulkan.java')]
sources=[Path(__file__).with_name('IrisVulkanOpaqueDepthRecordingTest.java'),fixtures/'IrisVulkanDeferredDepthRegression.java']+production
java='C:/Program Files/Java/jdk-25.0.3/bin/'
compile=subprocess.run([java+'javac.exe','--release','25','-proc:none','-classpath',';'.join(cp),'-d',str(out),*[str(s) for s in sources]],capture_output=True,text=True)
(out/'javac.log').write_text(compile.stdout+compile.stderr)
if compile.returncode:raise RuntimeError(compile.stdout+compile.stderr)
reports=[]
for test in ('IrisVulkanOpaqueDepthRecordingTest','IrisVulkanDeferredDepthRegression'):
    result=subprocess.run([java+'java.exe','--enable-native-access=ALL-UNNAMED','-Djava.awt.headless=true','-classpath',str(out)+';'+';'.join(cp),'net.irisshaders.iris.vulkan.'+test,str(out)],capture_output=True,text=True)
    (out/(test+'.log')).write_text(result.stdout+result.stderr);print(result.stdout+result.stderr)
    if result.returncode:raise SystemExit(result.returncode)
    reports.append({'test':test,'passed':True})
(out/'input-hashes.json').write_text(json.dumps({'tests':reports,'source_sha256':{str(s):hashlib.sha256(s.read_bytes()).hexdigest() for s in sources+production}},indent=2))
