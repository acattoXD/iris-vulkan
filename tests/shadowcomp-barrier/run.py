"""Headless recording proof for actual compiled shadowcomp barrier selection."""
import hashlib,json,subprocess
from pathlib import Path
PORT=Path(__file__).resolve().parents[2];WORK=PORT.parents[1]
run=WORK/'build/port-26.3/custom-vulkan-17-opengl';args=[x[1:-1] for x in (run/'launch.args').read_text().splitlines() if x]
cp=[str(PORT/'common/build/classes/java/main'),str(PORT/'common/build/classes/java/api'),str(PORT/'common/build/classes/java/vendored')]+args[args.index('-classpath')+1].split(';')+[str(p) for p in (run/'mods').glob('*.jar')]
out=PORT/'build/shadowcomp-barrier-tests';out.mkdir(parents=True,exist_ok=True)
source=Path(__file__).with_name('IrisVulkanAfterShadowsBarrierTest.java');java='C:/Program Files/Java/jdk-25.0.3/bin/'
compile=subprocess.run([java+'javac.exe','--release','25','-proc:none','-classpath',';'.join(cp),'-d',str(out),str(source)],capture_output=True,text=True)
(out/'javac.log').write_text(compile.stdout+compile.stderr)
if compile.returncode:raise RuntimeError(compile.stdout+compile.stderr)
result=subprocess.run([java+'java.exe','-Djava.awt.headless=true','-classpath',str(out)+';'+';'.join(cp),'net.irisshaders.iris.vulkan.IrisVulkanAfterShadowsBarrierTest'],capture_output=True,text=True)
(out/'result.txt').write_text(result.stdout+result.stderr);print(result.stdout+result.stderr)
inputs=[source]+[PORT/'common/src/main/java/net/irisshaders/iris/vulkan'/name for name in ('IrisVulkanFinalPassRenderer.java','IrisVulkanComputeExecutor.java','IrisVulkanStorageResources.java')]
(out/'input-hashes.json').write_text(json.dumps({str(path):hashlib.sha256(path.read_bytes()).hexdigest() for path in inputs},indent=2))
if result.returncode:raise SystemExit(result.returncode)
