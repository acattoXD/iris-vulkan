"""Audit real Photon pack parsing/preflight and export preprocessed sources; no GPU/game/Gradle."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk',type=Path,required=True)
    parser.add_argument('--classpath-json',type=Path,required=True)
    parser.add_argument('--pack',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--screens',action='store_true',help='Attempt the real first-deferred screen path; preserve missing live-context blockers')
    args=parser.parse_args();port=Path(__file__).resolve().parents[2];out=args.output.resolve();out.mkdir(parents=True,exist_ok=True)
    classes=out/'classes';classes.mkdir(exist_ok=True)
    cp=[port/'common/build/classes/java'/s for s in ['main','api','vendored','headers']]
    cp += [Path(p) for p in json.loads(args.classpath_json.read_text()) if not Path(p).name.startswith('iris-')]
    cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
    for group,name,version in [('org.anarres','jcpp','1.4.14'),('org.antlr','antlr4-runtime','4.13.1'),('io.github.douira','glsl-transformer','3.0.0-pre3')]:
        cp.extend((cache/group/name/version).glob('*/*.jar'))
    cp=[p.resolve() for p in cp if p.exists()];sources=sorted(Path(__file__).parent.rglob('*.java'))
    compiled=subprocess.run([str(args.jdk/'bin/javac.exe'),'--release','25','-proc:none','-encoding','UTF-8','-cp',';'.join(map(str,cp)),'-d',str(classes),*map(str,sources)],capture_output=True,text=True)
    (out/'compile.log').write_text(compiled.stdout+compiled.stderr)
    if compiled.returncode:raise RuntimeError(compiled.stdout+compiled.stderr)
    command=[str(args.jdk/'bin/java.exe'),'--enable-native-access=ALL-UNNAMED','-Xmx3G','-cp',str(classes)+';'+';'.join(map(str,cp)),
             'net.irisshaders.iris.vulkan.PhotonScreenAudit' if args.screens else 'net.irisshaders.iris.vulkan.PhotonPackAudit',str(args.pack.resolve()),str(out)]
    log_name='screen-attempt.log' if args.screens else 'audit.log'
    with (out/log_name).open('w',encoding='utf-8') as log:
        process=subprocess.run(command,cwd=out,stdout=log,stderr=subprocess.STDOUT)
    monitored=['net/irisshaders/iris/shaderpack/ShaderPack.class','net/irisshaders/iris/shaderpack/programs/ProgramSet.class',
               'net/irisshaders/iris/vulkan/IrisVulkanPackCapabilities.class','net/irisshaders/iris/vulkan/IrisVulkanCustomTextures.class',
               'net/irisshaders/iris/vulkan/IrisVulkanColorImages.class','net/irisshaders/iris/vulkan/IrisVulkanComputeCompiler.class',
               'net/irisshaders/iris/vulkan/IrisVulkanScreenPassPlanner.class','net/irisshaders/iris/vulkan/IrisVulkanUniformSnapshot.class',
               'net/irisshaders/iris/uniforms/IrisExclusiveUniforms.class']
    (out/('screen-inputs.json' if args.screens else 'inputs.json')).write_text(json.dumps({'pack':str(args.pack.resolve()),'packSha256':sha(args.pack),
        'productionClasses':{n:sha(cp[0]/n) for n in monitored},'testSources':{str(p):sha(p) for p in sources},
        'exportedSourceSha256':{p.relative_to(out).as_posix():sha(p) for label in ['default','high'] for p in sorted((out/label).glob('*')) if p.suffix in {'.vsh','.fsh','.csh','.gsh','.tcs','.tes'}},
        'classpath':[str(p) for p in cp],'javaCommand':command,'completed':process.returncode==0,
        'scope':'CPU only; real ShaderPack/ProgramSet/capability inspector/texture parser; three explicit environment facades; no GPU/device/driver validation'},indent=2)+'\n')
    print((out/log_name).read_text(encoding='utf-8')[-9000:])
    if process.returncode:raise RuntimeError('Photon audit failed; see audit.log')

if __name__=='__main__':main()
