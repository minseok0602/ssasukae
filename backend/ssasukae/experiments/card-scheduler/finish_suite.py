"""Continue the measured suite after an already-running baseline; not a scheduled automation."""
import argparse
import csv
import subprocess
import sys
import time
from pathlib import Path

from build import HERE
from run import REDIS, RESULTS, append_json, utc


def rows():
    with (RESULTS / 'fault-matrix.csv').open() as stream:
        return list(csv.DictReader(stream))


def execute(phase, cases, repetitions, policy='cancel', duration=10, keep=True):
    command = [sys.executable, str(HERE / 'run.py'), '--phase', phase, '--cases', *cases,
               '--repetitions', str(repetitions), '--duration', str(duration), '--policy', policy]
    if keep: command.append('--keep')
    print('START PHASE ' + phase, flush=True)
    subprocess.run(command, check=True)
    current = [r for r in rows() if r['phase'] == phase]
    failures = [r for r in current if r['timingValid'] == 'True'
                and r['outcome'] != ('STUCK' if policy == 'none' else 'CONVERGED')]
    if failures: raise RuntimeError('Unexpected outcomes in ' + phase + ': ' + repr(failures))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--wait-baseline', action='store_true')
    parser.add_argument('--after-only', action='store_true')
    parser.add_argument('--extensions-only', action='store_true')
    args = parser.parse_args()
    if args.wait_baseline:
        print('Waiting for baseline S-02/03/04 x20 and completed baseline process logs.', flush=True)
        while True:
            recorded = rows()
            complete = all(sum(r['phase']=='baseline' and r['testId']==case for r in recorded) >= 20
                           for case in ['S-02','S-03','S-04'])
            if complete and (RESULTS / 'baseline-app.txt').exists(): break
            time.sleep(5)
    if not args.after_only and not args.extensions_only:
        execute('baseline-supplement', ['S-01-extended'], 1, policy='none')
    # This Redis belongs solely to this Compose experiment. All measured states are already snapshotted.
    if not args.extensions_only:
        count = REDIS.command('DBSIZE')
        REDIS.command('FLUSHDB')
        append_json(RESULTS / 'phase-resets.jsonl', {'at': utc(), 'beforePhase': 'after-smoke',
                    'isolatedRedisKeysCleared': count, 'reason': 'Remove completed baseline fixtures after saving evidence'})
        execute('after-smoke', ['CONTROL','S-02','S-03','S-04'], 1)
        execute('after-cancel', ['CONTROL','S-02','S-03','S-04'], 20)
        execute('after-bounded-start', ['S-02'], 20, policy='bounded-start')
    execute('after-supplement', ['S-01','S-01-extended'], 1)
    execute('after-extended', ['S-05P','S-05A','S-07-before','S-07-after','S-08'], 5)
    execute('after-race', ['S-06'], 5, duration=20, keep=False)
    subprocess.run([sys.executable, str(HERE / 'summarize.py')], check=True)
    print('SUITE COMPLETE', flush=True)


if __name__ == '__main__':
    main()
