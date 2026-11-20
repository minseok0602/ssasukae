"""Offline Java 17 build using existing Gradle dependency cache; no production build edits."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

HERE = Path(__file__).resolve().parent
BACKEND = HERE.parent.parent
ROOT = BACKEND.parent.parent
DOCKER = os.environ.get('CARD_EXPERIMENT_DOCKER', '/mnt/c/Program Files/Docker/Docker/resources/bin/docker.exe')


def windows_path(path):
    path = str(Path(path).resolve())
    if path.startswith('/mnt/') and len(path) > 6 and path[6] == '/':
        return path[5].upper() + ':' + path[6:]
    return path


def version_key(version):
    return tuple(int(v) for v in re.findall(r'\d+', version))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cache', type=Path, default=Path('/mnt/c/Users/SSAFY/.gradle/caches/modules-2/files-2.1'))
    parser.add_argument('--incremental', action='store_true', help='Recompile experiment and card tests against existing production classes')
    args = parser.parse_args()
    build = HERE / 'build'
    lib = build / 'lib'
    lib.mkdir(parents=True, exist_ok=True)
    selected = {}
    for jar in args.cache.glob('*/*/*/*/*.jar'):
        group, artifact, version = jar.relative_to(args.cache).parts[:3]
        # Source/javadoc and native transports for other OSes are unnecessary.
        if jar.name.endswith(('-sources.jar', '-javadoc.jar')):
            continue
        key = (group, artifact)
        if key not in selected or version_key(version) > version_key(selected[key][0]):
            selected[key] = (version, jar)
    if not any(k[1] == 'spring-boot' for k in selected):
        raise SystemExit('Spring Boot jars not found in --cache')
    dependencies = []
    for (group, artifact), (version, jar) in sorted(selected.items()):
        target = lib / jar.name
        if not target.exists():
            shutil.copyfile(jar, target)
        dependencies.append({'group': group, 'artifact': artifact, 'version': version,
                             'sha256': hashlib.sha256(jar.read_bytes()).hexdigest()})
    sources = sorted((BACKEND / 'src/main/java').rglob('*.java')) + sorted((HERE / 'java').rglob('*.java'))
    sources.append(BACKEND / 'src/test/java/com/ssafy/ssasukae/domain/card/service/CardServiceTest.java')
    source_entries = [str(p.relative_to(ROOT)) for p in sources]
    compile_sources = ['/repo/' + str(p.relative_to(ROOT)) for p in sources
                       if not args.incremental or '/src/main/java/' not in str(p)]
    (build / 'sources.args').write_text('\n'.join(compile_sources) + '\n')
    (build / 'classes').mkdir(exist_ok=True)
    command = [DOCKER, 'run', '--rm', '--network', 'none',
               '-v', windows_path(ROOT) + ':/repo:ro',
               '-v', windows_path(HERE) + ':/experiment',
               '-w', '/experiment', 'eclipse-temurin:17-jdk-jammy',
               'javac', '-parameters', '-encoding', 'UTF-8', '-cp', 'build/classes:build/lib/*',
               '-processorpath', 'build/lib/' + next(p.name for p in lib.glob('lombok-*.jar')),
               '-d', 'build/classes', '@build/sources.args']
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (HERE / 'results/compile.txt').write_text(result.stdout)
    metadata = {'gitSha': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                'java': 'eclipse-temurin:17-jdk-jammy', 'dependencies': dependencies,
                'sourceSha256': {entry: hashlib.sha256(path.read_bytes()).hexdigest()
                                 for entry, path in zip(source_entries, sources)},
                'compileExitCode': result.returncode}
    (HERE / 'results/build-metadata.json').write_text(json.dumps(metadata, indent=2))
    print(result.stdout[-10000:])
    print(f'Compiled {len(compile_sources)} sources; {len(dependencies)} cached dependencies; exit={result.returncode}')
    raise SystemExit(result.returncode)


if __name__ == '__main__':
    main()
