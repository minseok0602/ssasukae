"""JUnit tests use a dedicated Redis, isolated from running recovery workers."""
import subprocess
from build import HERE,DOCKER,windows_path

NAME='ssasukae-production-checks-redis'
NETWORK='ssasukae-production-recovery_default'

def main():
    subprocess.run([DOCKER,'run','-d','--name',NAME,'--network',NETWORK,'redis:7-alpine'],check=True)
    try:
        result=subprocess.run([DOCKER,'run','--rm','--network',NETWORK,
            '-e','REDIS_INTEGRATION_HOST='+NAME,'-v',windows_path(HERE)+':/experiment','-w','/experiment',
            'ssasukae-production-verification:local','java','-Dio.netty.eventLoopThreads=2',
            '-javaagent:/runtime/lib/mockito-core-5.23.0.jar','-cp','/runtime/classes:/runtime/lib/*',
            'experiment.VerificationChecks'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
        (HERE/'results/redis-test-console.txt').write_text(result.stdout)
        print((HERE/'results/redis-tests.txt').read_text())
        raise SystemExit(result.returncode)
    finally:
        subprocess.run([DOCKER,'rm','-f',NAME],check=True)

if __name__=='__main__':main()
