"""Compile actual feedback planner and execute its CPU-only semantic checks."""
import argparse,hashlib,json,subprocess
from pathlib import Path
PORT=Path(__file__).resolve().parents[2]
WORK=PORT.parents[1]
run=WORK/'build/port-26.3/custom-vulkan-17-opengl'
args=[x[1:-1] for x in (run/'launch.args').read_text().splitlines() if x]
cp=args[args.index('-classpath')+1].split(';')+[str(p) for p in (run/'mods').glob('*.jar')]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output',type=Path,default=PORT/'build/feedback-snapshot-tests')
out=parser.parse_args().output.resolve();out.mkdir(parents=True,exist_ok=True)
sources=[PORT/'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanFeedbackPlan.java',
    PORT/'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanGbufferTargets.java',
    PORT/'common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanDepthCopy.java',
    Path(__file__).with_name('IrisVulkanFeedbackPlanTest.java')]
java='C:/Program Files/Java/jdk-25.0.3/bin/'
compile=subprocess.run([java+'javac.exe','--release','25','-proc:none','-classpath',';'.join(cp),'-d',str(out),*[str(s) for s in sources]],capture_output=True,text=True)
(out/'javac.log').write_text(compile.stdout+compile.stderr)
if compile.returncode:raise RuntimeError(compile.stdout+compile.stderr)
result=subprocess.run([java+'java.exe','-classpath',str(out)+';'+';'.join(cp),'net.irisshaders.iris.vulkan.IrisVulkanFeedbackPlanTest'],capture_output=True,text=True)
(out/'result.txt').write_text(result.stdout+result.stderr)
(out/'input-hashes.json').write_text(json.dumps({str(s):hashlib.sha256(s.read_bytes()).hexdigest() for s in sources},indent=2))
print(result.stdout+result.stderr)
if result.returncode:raise SystemExit(result.returncode)
