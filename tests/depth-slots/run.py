"""Run pure depth-slot value/lifetime checks without graphics or a game process."""
import hashlib,json,subprocess
from pathlib import Path
PORT=Path(__file__).resolve().parents[2]
out=PORT/'build/alpha12-initial-depth-plan-tests';out.mkdir(parents=True,exist_ok=True)
sources=[PORT/'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanDepthSlotPlan.java',Path(__file__).with_name('IrisVulkanDepthSlotPlanTest.java')]
java='C:/Program Files/Java/jdk-25.0.3/bin/'
compile=subprocess.run([java+'javac.exe','--release','25','-proc:none','-d',str(out),*[str(s) for s in sources]],capture_output=True,text=True)
(out/'javac.log').write_text(compile.stdout+compile.stderr)
if compile.returncode:raise RuntimeError(compile.stdout+compile.stderr)
result=subprocess.run([java+'java.exe','-classpath',str(out),'net.irisshaders.iris.vulkan.IrisVulkanDepthSlotPlanTest'],capture_output=True,text=True)
(out/'result.txt').write_text(result.stdout+result.stderr);print(result.stdout+result.stderr)
(out/'input-hashes.json').write_text(json.dumps({str(s):hashlib.sha256(s.read_bytes()).hexdigest() for s in sources},indent=2))
if result.returncode:raise SystemExit(result.returncode)
