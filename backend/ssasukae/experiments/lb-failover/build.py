"""Compile only the LB experiment against the previously tested, immutable candidate image."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

HERE=Path(__file__).resolve().parent
DOCKER=os.environ.get('CARD_EXPERIMENT_DOCKER','/mnt/c/Program Files/Docker/Docker/resources/bin/docker.exe')
def windows_path(path):
    value=str(Path(path).resolve())
    return value[5].upper()+':'+value[6:] if value.startswith('/mnt/') else value

def main():
    sources=sorted((HERE/'java').rglob('*.java'))
    (HERE/'build/classes').mkdir(parents=True,exist_ok=True)
    args='\n'.join('/experiment/'+str(p.relative_to(HERE)) for p in sources)+'\n'
    (HERE/'build/sources.args').write_text(args)
    command=[DOCKER,'run','--rm','--network','none','-v',windows_path(HERE)+':/experiment',
        '-w','/experiment','ssasukae-card-experiment:candidate','javac','-parameters','-encoding','UTF-8',
        '-cp','/runtime/classes:/runtime/lib/*','-d','build/classes','@build/sources.args']
    result=subprocess.run(command,capture_output=True,text=True)
    (HERE/'results/compile.txt').write_text(result.stdout+result.stderr)
    print(result.stdout+result.stderr,flush=True)
    metadata={'sources':{str(p.relative_to(HERE)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
        'compileExitCode':result.returncode,'baseImage':'ssasukae-card-experiment:candidate'}
    (HERE/'results/build-metadata.json').write_text(json.dumps(metadata,indent=2))
    if result.returncode: raise SystemExit(result.returncode)
    subprocess.run([DOCKER,'build','-t','ssasukae-lb-experiment:local',windows_path(HERE)],check=True)

if __name__=='__main__':main()
