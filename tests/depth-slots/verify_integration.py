"""Run current compiled gbuffer COW integration and native frame-boundary regression headlessly."""
import hashlib,json,subprocess
from pathlib import Path
PORT=Path(__file__).resolve().parents[2];WORK=PORT.parents[1]
run=WORK/'build/port-26.3/custom-vulkan-17-opengl';args=[x[1:-1] for x in (run/'launch.args').read_text().splitlines() if x]
cp=[str(PORT/'common/build/classes/java/main'),str(PORT/'common/build/classes/java/api'),str(PORT/'common/build/classes/java/vendored')]+args[args.index('-classpath')+1].split(';')+[str(p) for p in (run/'mods').glob('*.jar')]
out=PORT/'build/alpha12-initial-depth-integration-tests';out.mkdir(parents=True,exist_ok=True)
focused=PORT/'build/alpha12-initial-depth-classes';focused.mkdir(parents=True,exist_ok=True)
production=[PORT/'common/src/main/java/net/irisshaders/iris/vulkan'/name for name in ('IrisVulkanDepthSlotPlan.java','IrisVulkanGbufferTargets.java')]
java='C:/Program Files/Java/jdk-25.0.3/bin/'
focused_compile=subprocess.run([java+'javac.exe','--release','25','-proc:none','-classpath',';'.join(cp),'-d',str(focused),*[str(p) for p in production]],capture_output=True,text=True)
(out/'focused-javac.log').write_text(focused_compile.stdout+focused_compile.stderr)
if focused_compile.returncode:raise RuntimeError(focused_compile.stdout+focused_compile.stderr)
cp=[str(focused)]+cp
sources=[Path(__file__).with_name('IrisVulkanDepthOwnershipTest.java'),PORT/'tests/native-world/IrisVulkanDeferredDepthRegression.java']
compile=subprocess.run([java+'javac.exe','--release','25','-proc:none','-classpath',';'.join(cp),'-d',str(out),*[str(s) for s in sources]],capture_output=True,text=True)
(out/'javac.log').write_text(compile.stdout+compile.stderr)
if compile.returncode:raise RuntimeError(compile.stdout+compile.stderr)
for test in ('IrisVulkanDepthOwnershipTest','IrisVulkanDeferredDepthRegression'):
    result=subprocess.run([java+'java.exe','-Djava.awt.headless=true','-classpath',str(out)+';'+';'.join(cp),'net.irisshaders.iris.vulkan.'+test],capture_output=True,text=True)
    (out/(test+'.log')).write_text(result.stdout+result.stderr);print(result.stdout+result.stderr)
    if result.returncode:raise SystemExit(result.returncode)
production += [PORT/'common/src/main/java/net/irisshaders/iris/vulkan'/name for name in ('IrisNativeVulkan.java','IrisVulkanDepthCopy.java')]
(out/'input-hashes.json').write_text(json.dumps({str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources+production},indent=2))
