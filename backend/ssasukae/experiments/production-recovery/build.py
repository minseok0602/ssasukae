"""Compile current production sources and verification helpers using the cached Java 17 runtime."""
import hashlib
import json
import os
from pathlib import Path
import subprocess

HERE=Path(__file__).resolve().parent
BACKEND=HERE.parents[1]
ROOT=BACKEND.parents[1]
DOCKER=os.environ.get('CARD_EXPERIMENT_DOCKER','/mnt/c/Program Files/Docker/Docker/resources/bin/docker.exe')
def windows_path(path):
    text=str(Path(path).resolve())
    return text[5].upper()+':'+text[6:] if text.startswith('/mnt/') else text

def main():
    sources=sorted((BACKEND/'src/main/java').rglob('*.java'))+sorted((HERE/'java').rglob('*.java'))
    for folder in ['domain/card','global/websocket']:
        sources+=sorted((BACKEND/'src/test/java/com/ssafy/ssasukae'/folder).rglob('*.java'))
    (HERE/'build/classes').mkdir(parents=True,exist_ok=True)
    (HERE/'build/sources.args').write_text('\n'.join('/repo/'+str(p.relative_to(ROOT)) for p in sources)+'\n')
    cmd=[DOCKER,'run','--rm','--network','none','-v',windows_path(ROOT)+':/repo:ro',
        '-v',windows_path(HERE)+':/experiment','-w','/experiment','ssasukae-lb-experiment:local',
        'javac','-parameters','-encoding','UTF-8','-cp','/runtime/classes:/runtime/lib/*',
        '-processorpath','/runtime/lib/lombok-1.18.46.jar','-d','build/classes','@build/sources.args']
    result=subprocess.run(cmd,capture_output=True,text=True)
    output=result.stdout+result.stderr
    (HERE/'results/compile.txt').write_text(output)
    (HERE/'results/build-metadata.json').write_text(json.dumps({'compileExitCode':result.returncode,
        'sources':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
        'sourcesNormalizedLf':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes().replace(b'\r\n',b'\n')).hexdigest() for p in sources}},indent=2))
    print(output,flush=True)
    if result.returncode:raise SystemExit(result.returncode)
    subprocess.run([DOCKER,'build','-t','ssasukae-production-verification:local',windows_path(HERE)],check=True)

if __name__=='__main__':main()
