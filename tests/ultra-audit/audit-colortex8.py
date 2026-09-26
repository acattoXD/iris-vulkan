"""Fresh full-program High/Ultra colortex8 source inventory. No GPU or game initialization."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import zipfile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def occurrences(text, pattern):
    return [{'line': text.count('\n', 0, match.start()) + 1,
             'text': text.splitlines()[text.count('\n', 0, match.start())].strip()}
            for match in re.finditer(pattern, text, re.MULTILINE)]


def source_inventory(path):
    raw = path.read_text(encoding='utf-8')
    # Preserve line numbers while removing comments and standalone uniform declarations.
    code = re.sub(r'/\*.*?\*/|//[^\n]*', lambda match: '\n' * match.group().count('\n'), raw, flags=re.DOTALL)
    declarations = []
    # Only remove unambiguous, standalone declarations of the two names being
    # audited. A repeated generic word/whitespace group backtracked exponentially
    # through large preprocessed sources; horizontal spacing also avoids crossing
    # blank lines. Arrays, multiple declarators and unfamiliar forms remain uses.
    qualifier = r'(?:(?:readonly|writeonly|coherent|volatile|restrict|highp|mediump|lowp)[ \t]+)*'
    pattern = (r'(?m)^[ \t]*(?:layout[ \t]*\([^\r\n)]*\)[ \t]*)?' + qualifier
               + r'uniform[ \t]+' + qualifier + r'[iu]?(?:sampler|image)\w*[ \t]+'
               + r'(?:colortex8|colorimg8)[ \t]*;')
    def remove_uniform(match):
        if re.search(r'\b(?:colortex8|colorimg8)\b', match.group()):
            declarations.append({'line': code.count('\n', 0, match.start()) + 1, 'text': match.group().strip()})
        return '\n' * match.group().count('\n')
    executable = re.sub(pattern, remove_uniform, code)
    reads = occurrences(executable, r'\b(?:colortex8|colorimg8)\b')
    metadata = occurrences(code, r'\b(?:colortex8|colorimg8)(?:Format|ClearColor|Clear|MipmapEnabled)\b')
    targets = []
    for match in re.finditer(r'/\*\s*(DRAWBUFFERS|RENDERTARGETS)\s*:\s*([^*]+)\*/', raw):
        kind, values = match.groups()
        try:
            parsed = [int(char) for char in values.strip()] if kind == 'DRAWBUFFERS' else [int(value.strip()) for value in values.split(',')]
        except ValueError:
            raise AssertionError('Unparsed target directive: ' + match.group())
        if 8 in parsed:
            targets.append({'line': raw.count('\n', 0, match.start()) + 1, 'kind': kind, 'targets': parsed,
                            'fragmentOutputIndices': [i for i, target in enumerate(parsed) if target == 8]})
    explicit = occurrences(code, r'\bgl_FragData\s*\[\s*8\s*\]|layout\s*\(\s*location\s*=\s*8\s*\)\s*out\b')
    return {'standaloneUniformDeclarations': declarations, 'nonDeclarationNamedUses': reads,
            'targetDirectives': targets, 'formatClearMipmapMetadata': metadata,
            'explicitOutput8': explicit, 'sha256': sha(path)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--classpath-json', type=Path, required=True)
    parser.add_argument('--high', type=Path, required=True)
    parser.add_argument('--ultra', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    out = args.output.resolve()
    if out.exists():
        raise ValueError('Refusing to overwrite an existing immutable inventory')
    out.mkdir(parents=True)
    base_dependencies = []
    for value in json.loads(args.classpath_json.read_text(encoding='utf-8')):
        path = Path(value)
        if path.suffix == '.jar' and not path.name.startswith('iris-') and ('/mods/' not in path.as_posix() or path.name.startswith('sodium-')):
            base_dependencies.append(path)
    sources = [root / 'tests/ultra-audit/stubs/Iris.java', root / 'tests/ultra-audit/ComplementaryUltraAudit.java']
    report = {'scope': 'Production-preprocessed source inventory only; no GPU or shader-performance proof', 'profiles': {},
              'toolSources': {str(path.relative_to(root)): sha(path) for path in [*sources, Path(__file__)]}}
    for label, instance in [('HIGH', args.high.resolve()), ('ULTRA', args.ultra.resolve())]:
        dest = out / label
        dest.mkdir()
        pack = instance / 'shaderpacks/ComplementaryUnbound_r5.9.1.zip'
        options = pack.with_name(pack.name + '.txt')
        iris, = (instance / 'mods').glob('iris-vulkan-experimental-*.jar')
        before = {str(path): sha(path) for path in [pack, iris, instance / 'options.txt', *([options] if options.exists() else [])]}
        options_present = options.exists()
        shutil.copyfile(pack, dest / pack.name)
        shutil.copyfile(iris, dest / iris.name)
        if options_present:
            shutil.copyfile(options, dest / 'saved-options.properties')
        else:
            (dest / 'saved-options.properties').write_text('# No source sidecar exists: exact pack defaults\n', encoding='utf-8')
        dormant = {}
        with zipfile.ZipFile(pack) as archive:
            for entry in archive.infolist():
                if entry.is_dir(): continue
                target = (dest / 'pack' / entry.filename).resolve()
                if not target.is_relative_to(dest / 'pack'): raise ValueError('Archive entry escapes destination')
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(entry))
                if entry.filename.endswith('.properties') and entry.filename != 'shaders/shaders.properties':
                    mentions = occurrences(archive.read(entry).decode('utf-8-sig'), r'\b(?:colortex8|colorimg8)\b')
                    if mentions: dormant[entry.filename] = mentions
        game_options = dict(line.split(':',1) for line in (instance/'options.txt').read_text().splitlines() if ':' in line)
        mods = sorted(path.name for path in (instance/'mods').glob('*.jar'))
        environment = {'MC_VERSION':'260300','IRIS_VERSION':'11105','MAX_COLOR_BUFFERS':'32',
                       'MC_MIPMAP_LEVEL':game_options['mipmapLevels'],'IRIS_INLINE_GLINT':''}
        if any(name.startswith('continuity-') for name in mods): environment['IRIS_HAS_CONNECTED_TEXTURES']=''
        (dest/'environment.json').write_text(json.dumps(environment,indent=2),encoding='utf-8')
        dependencies = list(base_dependencies)
        with zipfile.ZipFile(iris) as archive:
            for name in archive.namelist():
                if name.startswith('META-INF/jars/') and name.endswith('.jar'):
                    target = dest/'libraries'/Path(name).name
                    target.parent.mkdir(exist_ok=True)
                    target.write_bytes(archive.read(name));dependencies.append(target)
        classes = dest/'classes';classes.mkdir()
        cp = ';'.join(str(path.resolve()) for path in [classes,dest/iris.name,*dependencies])
        def run(executable, name, arguments):
            argfile = dest/(name+'.args')
            argfile.write_text('\n'.join('"'+str(value).replace('\\','/')+'"' for value in arguments),encoding='utf-8')
            result = subprocess.run([str(args.jdk/'bin'/executable),'@'+str(argfile)],text=True,capture_output=True)
            (dest/(name+'.txt')).write_text(result.stdout+result.stderr,encoding='utf-8')
            print(result.stdout+result.stderr,end='')
            if result.returncode: raise SystemExit(result.returncode)
        run('javac.exe','compile',['-proc:none','-encoding','UTF-8','-cp',cp,'-d',classes,*sources])
        run('java.exe','export',['--enable-native-access=ALL-UNNAMED','-cp',cp,
            'net.irisshaders.iris.audit.ComplementaryUltraAudit',dest/'pack/shaders',dest/'expanded',
            dest/'saved-options.properties',dest/'environment.json'])
        inventory = json.loads((dest/'expanded/inventory.json').read_text())
        files = {name:source_inventory(dest/'expanded'/name) for name in inventory['sources']}
        properties = (dest/'expanded/preprocessed.properties').read_text()
        uses = {name:value for name,value in files.items() if value['nonDeclarationNamedUses'] or value['targetDirectives'] or value['explicitOutput8']}
        profile = {'instance':str(instance),'inputSha256':before,'sourceSidecarPresent':options_present,
                   'sourceOptions':'saved-options.properties','sourceCount':len(files),
                   'countsByExtension':{ext:sum(name.endswith(ext) for name in files) for ext in ['.vsh','.fsh','.gsh','.tcs','.tes','.csh']},
                   'selectedOptions':inventory['selectedOptions'],'disabledPrograms':inventory['disabledPrograms'],
                   'meaningfulUseSources':uses,'allSources':files,
                   'preprocessedPropertyMentions':occurrences(properties,r'\b(?:colortex8|colorimg8)\b'),
                   'otherPackPropertyFileMentions':dormant,
                   'environment':'expanded/inventory.json','resourcePacks':game_options.get('resourcePacks')}
        for path, expected in before.items():
            if sha(Path(path)) != expected: raise AssertionError('Source input changed during audit')
        if not options_present and options.exists(): raise AssertionError('Absent sidecar was created in source instance')
        report['profiles'][label]=profile
    report['conclusion'] = {'highHasMeaningfulUse':bool(report['profiles']['HIGH']['meaningfulUseSources']),
                            'ultraHasMeaningfulUse':bool(report['profiles']['ULTRA']['meaningfulUseSources']),
                            'qualification':'Standalone sampler declarations and blend/format metadata do not alone prove a reader or writer. A general target-liveness optimization must also preserve all program/directive/custom-resource routes.'}
    (out/'report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps(report['conclusion'],indent=2))


if __name__=='__main__':main()
