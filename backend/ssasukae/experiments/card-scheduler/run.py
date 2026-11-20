"""Real SIGKILL/restart experiment. Python stdlib only; dedicated Compose project only."""
import argparse
from collections import Counter
import csv
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import queue
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request

from build import DOCKER, HERE, windows_path

RESULTS = HERE / 'results'
BASE = 'http://127.0.0.1:18084'


def utc():
    return datetime.now(timezone.utc).isoformat()


def epoch(value):
    if isinstance(value, (int, float)):
        return float(value)
    return datetime.fromisoformat(value.replace('Z', '+00:00')).timestamp()


def append_json(path, value):
    with path.open('a') as stream:
        stream.write(json.dumps(value, ensure_ascii=False) + '\n')


def compose(*args, check=True):
    started = utc()
    # Windows executables do not inherit arbitrary WSL environment changes without WSLENV.
    # Pass only the explicit experiment settings through a local Compose env file instead.
    settings = {name: os.environ.get(name, default) for name, default in {
        'CARD_EXPERIMENT_IMAGE': 'ssasukae-card-experiment:local',
        'EXPERIMENT_RECOVERY_ENABLED': 'false',
        'EXPERIMENT_RECOVERY_POLICY': '',
        'EXPERIMENT_RECOVERY_SCAN_DELAY_MS': '100'}.items()}
    env_file = HERE / 'build/compose-experiment.env'
    env_file.write_text(''.join(f'{name}={value}\n' for name, value in settings.items()))
    command = [DOCKER, 'compose', '--env-file', windows_path(env_file),
               '-f', windows_path(HERE / 'compose.yaml'), *args]
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    append_json(RESULTS / 'process-log.jsonl', {'startedAt': started, 'finishedAt': utc(),
                'args': list(args), 'settings': settings, 'exitCode': result.returncode, 'output': result.stdout})
    if check and result.returncode:
        raise RuntimeError(f'Compose {args} failed: {result.stdout}')
    return result.stdout


def process_state():
    result = subprocess.run([DOCKER, 'inspect', '--format', '{{json .State}}',
        'ssasukae-card-scheduler-experiment-app-1'], text=True, capture_output=True, check=True)
    return json.loads(result.stdout)


def verify_configuration(policy):
    result = subprocess.run([DOCKER, 'inspect', 'ssasukae-card-scheduler-experiment-app-1'],
                            text=True, capture_output=True, check=True)
    config = json.loads(result.stdout)[0]['Config']
    values = dict(item.split('=',1) for item in config['Env'] if '=' in item)
    expected = 'false' if policy=='none' else 'true'
    if values.get('EXPERIMENT_RECOVERY_ENABLED') != expected:
        raise RuntimeError('Actual recovery setting differs from requested policy')
    expected_image = 'ssasukae-card-experiment:local' if policy=='none' else 'ssasukae-card-experiment:candidate'
    if config['Image'] != expected_image:
        raise RuntimeError('Actual image differs from requested experiment variant')
    if policy != 'none' and values.get('EXPERIMENT_RECOVERY_POLICY') != policy:
        raise RuntimeError('Actual recovery policy differs from requested policy')
    append_json(RESULTS/'verified-configurations.jsonl', {'at':utc(),'image':config['Image'],
                'recoveryEnabled':expected,'policy':values.get('EXPERIMENT_RECOVERY_POLICY')})


def http(path, method='GET', base=BASE):
    req = urllib.request.Request(base + '/experiment/' + path, method=method,
                                 data=b'' if method == 'POST' else None)
    with urllib.request.urlopen(req, timeout=5) as response:
        data = response.read()
        return json.loads(data) if data else None


def ready(timeout=120, base=BASE):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        try:
            if http('ready', base=base)['ready']:
                return utc()
        except (OSError, ValueError):
            pass
        time.sleep(.05)
    raise TimeoutError('App readiness timeout')


class Redis:
    """Tiny RESP client, restricted to our dedicated Redis at 127.0.0.1:16384."""
    def __init__(self, database=0): self.database = database
    def command(self, *args):
        with socket.create_connection(('127.0.0.1', 16384), 3) as sock:
            sock.settimeout(3)
            def encode(parts):
                return b'*%d\r\n' % len(parts) + b''.join(
                    b'$%d\r\n' % len(str(arg).encode()) + str(arg).encode() + b'\r\n' for arg in parts)
            if self.database: sock.sendall(encode(('SELECT', self.database)))
            sock.sendall(encode(args))
            stream = sock.makefile('rb')
            def read():
                kind = stream.read(1)
                line = stream.readline().rstrip(b'\r\n')
                if kind == b'$':
                    length = int(line)
                    if length < 0: return None
                    value = stream.read(length); stream.read(2)
                    return value.decode()
                if kind == b'*': return [read() for _ in range(int(line))]
                if kind == b':': return int(line)
                if kind == b'-': raise RuntimeError(line.decode())
                if kind == b'+': return line.decode()
                raise RuntimeError('Redis connection ended')
            if self.database: read()
            return read()


REDIS = Redis()


def snapshot(fixture, stage):
    room = fixture['roomId']; perf = fixture['performanceId']
    key = f'room:{room}:active-card'
    raw = REDIS.command('GET', key)
    assigned = REDIS.command('HGETALL', f'room:{room}:performance:{perf}:cards')
    result = {'at': utc(), 'stage': stage, 'roomId': room, 'performanceId': perf,
              'card': json.loads(raw) if raw else None, 'ttlMs': REDIS.command('PTTL', key),
              'assignments': {assigned[i]: json.loads(assigned[i+1]) for i in range(0, len(assigned), 2)}}
    performance = REDIS.command('GET', f'performance:session:{perf}')
    result['performance'] = json.loads(performance) if performance else None
    result['activePerformanceId'] = REDIS.command('GET', f'performance:room:{room}:active')
    return result


def state(fixture):
    value = REDIS.command('GET', f"room:{fixture['roomId']}:active-card")
    return json.loads(value) if value else None


def wait_state(fixture, expected, timeout=8):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        value = state(fixture)
        if (value or {}).get('status', 'NONE') == expected: return value
        time.sleep(.02)
    raise TimeoutError(f'Expected {expected}, got {value}')


class Probe:
    def __init__(self):
        self.events = []; self.messages = queue.Queue()
        self.proc = subprocess.Popen(['java', '-cp', str(HERE / 'build/classes') + ':'
            + str(HERE / 'build/lib/*'), 'experiment.StompProbe'], stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1)
        def read():
            for line in self.proc.stdout:
                if line.startswith('EXPERIMENT:'):
                    item = json.loads(line[len('EXPERIMENT:'):])
                    if item['kind'] == 'event': self.events.append(item)
                    self.messages.put(item)
        self.reader = threading.Thread(target=read, daemon=True); self.reader.start()
        self.errors = []
        def read_errors():
            for line in self.proc.stderr: self.errors.append(line)
        threading.Thread(target=read_errors, daemon=True).start()

    def send(self, op, **kwargs):
        self.proc.stdin.write(json.dumps({'op': op, **kwargs}) + '\n'); self.proc.stdin.flush()

    def wait(self, kind, timeout=45):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            try:
                item = self.messages.get(timeout=.1)
                if item['kind'] == kind: return item
            except queue.Empty:
                if self.proc.poll() is not None:
                    raise RuntimeError('STOMP probe exited: ' + ''.join(self.errors)[-4000:])
        raise TimeoutError(f'No STOMP {kind}: ' + ''.join(self.errors)[-2000:])

    def connect(self, fixture, url='ws://127.0.0.1:18084/ws'):
        self.send('connect', token=fixture['token'], roomId=fixture['roomId'], url=url)
        self.wait('connected')

    def close(self):
        if self.proc.poll() is None:
            try:
                self.send('exit'); self.proc.wait(timeout=3)
            except (OSError, subprocess.TimeoutExpired):
                self.proc.kill(); self.proc.wait()
        self.reader.join(timeout=1)


def server_events(room_id):
    path = RESULTS / 'server-events.jsonl'
    if not path.exists(): return []
    return [json.loads(line) for line in path.read_text().splitlines()
            if json.loads(line)['event']['roomId'] == room_id]


def pause_until(deadline):
    while time.time() < deadline:
        time.sleep(min(.1, deadline-time.time()))


FIELDS = ['phase', 'testId', 'iteration', 'roomId', 'performanceId', 'cardId', 'participantId',
          'killRequestedAt', 'killCompletedAt', 'restartRequestedAt', 'restartReadyAt', 'activationDeadline',
          'endDeadline', 'initialState', 'finalState', 'timingValid', 'outcome', 'stuckAtJudgement',
          'restartDurationMs', 'recoveryDelayMs', 'serverStartAttempts', 'serverEndAttempts',
          'clientStarts', 'clientEnds', 'duplicateServerEvents', 'judgedAt', 'notes']


def run_case(case, iteration, phase, duration, probe):
    for attempt in range(5):
        try:
            fixture = http(f'fixture?duration={duration}', 'POST')
            break
        except urllib.error.HTTPError as error:
            if error.code != 500 or attempt == 4: raise
            detail = compose('logs', '--no-color', '--since', '10s', 'app')
            if 'Duplicate entry' not in detail or 'rooms.' not in detail: raise
            append_json(RESULTS/'fixture-retries.jsonl', {'at':utc(),'phase':phase,'testId':case,
                'iteration':iteration,'reason':'Six-character fixture invite-code collision; no card activated'})
    snapshots = []
    probe.events.clear()
    row = dict.fromkeys(FIELDS, '')
    row.update(phase=phase, testId=case, iteration=iteration, **{k: fixture[k]
        for k in ['roomId', 'performanceId', 'cardId', 'participantId']})
    try:
        probe.connect(fixture)
        snapshots.append(snapshot(fixture, 'before_activation'))
        probe.send('activate', roomId=fixture['roomId'], performanceId=fixture['performanceId'])
        probe.wait('sent')
        pending = wait_state(fixture, 'PENDING')
        row['activationDeadline'] = pending['activateAt']
        before = pending
        if case in ['S-03', 'S-04', 'S-05A', 'S-06', 'S-07-before', 'S-07-after', 'S-08']:
            before = wait_state(fixture, 'ACTIVE')
        row['initialState'] = before['status']
        snapshots.append(snapshot(fixture, 'before_kill' if case != 'CONTROL' else 'pending_control'))
        if before.get('endsAt'): row['endDeadline'] = before['endsAt']
        if case == 'CONTROL':
            active = wait_state(fixture, 'ACTIVE')
            row['endDeadline'] = active['endsAt']
            wait_state(fixture, 'NONE', duration + 5)
            row['timingValid'] = True
        else:
            if case.startswith('S-07'):
                pause_until(epoch(before['endsAt']) + (-.7 if case.endswith('before') else .15))
            if case == 'S-08':
                compose('stop', '-t', '1', 'redis')
                time.sleep(2)
                compose('up', '-d', '--no-deps', 'redis')
                until = time.monotonic() + 10
                while True:
                    try:
                        REDIS.command('PING'); break
                    except (OSError, RuntimeError):
                        if time.monotonic() > until: raise
                        time.sleep(.1)
            row['killRequestedAt'] = utc()
            compose('kill', '-s', 'SIGKILL', 'app')
            row['killCompletedAt'] = utc()
            killed = snapshot(fixture, 'after_kill')
            killed['processState'] = process_state()
            if killed['processState']['ExitCode'] != 137:
                raise RuntimeError('App did not exit from SIGKILL')
            snapshots.append(killed)
            if case == 'S-01-extended':
                changed = dict(pending)
                changed['activateAt'] = datetime.fromtimestamp(time.time()+12, timezone.utc).isoformat()
                REDIS.command('SET', f"room:{fixture['roomId']}:active-card", json.dumps(changed), 'XX', 'KEEPTTL')
                pending = changed
                row['activationDeadline'] = changed['activateAt']
                snapshots.append(snapshot(fixture, 'explicit_extended_deadline_fixture'))
                row['notes'] = 'Supplement only: activation deadline extended to kill+12s; production countdown remains 3s.'
            if case in ['S-05P', 'S-05A']:
                if case == 'S-05P':
                    REDIS.command('DEL', f"performance:room:{fixture['roomId']}:active")
                    row['notes'] = 'Active performance pointer removed while card app was down.'
                else:
                    key = f"performance:session:{fixture['performanceId']}"
                    ended = json.loads(REDIS.command('GET', key))
                    ended.update(status='FINISHED', playbackFinishedAt=utc())
                    REDIS.command('SET', key, json.dumps(ended), 'XX', 'KEEPTTL')
                    row['notes'] = 'Performance marked FINISHED with stale active pointer while card app was down.'
                snapshots.append(snapshot(fixture, 'performance_changed_while_app_down'))
            if case == 'S-02': pause_until(epoch(pending['activateAt']) + .15)
            if case == 'S-04': pause_until(epoch(before['endsAt']) + .15)
            row['restartRequestedAt'] = utc()
            if case == 'S-06': os.environ['EXPERIMENT_RECOVERY_SCAN_DELAY_MS'] = '3600000'
            compose('up', '-d', '--no-deps', *(['app', 'app2'] if case == 'S-06' else ['app']))
            row['restartReadyAt'] = ready()
            if case == 'S-06': race_ready_at = ready(base='http://127.0.0.1:18085')
            row['restartDurationMs'] = round((epoch(row['restartReadyAt']) - epoch(row['restartRequestedAt']))*1000)
            snapshots.append(snapshot(fixture, 'restart_ready'))
            probe.connect(fixture)
            deadline = epoch(pending['activateAt']) if case in ['S-01', 'S-01-extended', 'S-02', 'S-05P'] else epoch(before['endsAt'])
            if case in ['S-01', 'S-01-extended', 'S-03']:
                row['timingValid'] = epoch(row['restartReadyAt']) < deadline
            elif case in ['S-02', 'S-04']:
                row['timingValid'] = epoch(row['restartReadyAt']) > deadline
            else:
                row['timingValid'] = True
            if case == 'S-06':
                row['timingValid'] = epoch(row['restartReadyAt']) < deadline and epoch(race_ready_at) < deadline
            if case.startswith('S-07'):
                effective = epoch(killed['processState']['FinishedAt'])
                row['timingValid'] = effective < deadline if case.endswith('before') else effective >= deadline
                row['notes'] = 'Container SIGKILL effective at ' + killed['processState']['FinishedAt']
            if not row['timingValid']:
                row['notes'] = 'Cold restart missed required timing window; exclude from case success denominator.'
            # Fixed judgement window: both the expected normal finish and restart + 2s have passed.
            target = max(epoch(row['restartReadyAt']) + 2, deadline + 1)
            if case in ['S-01', 'S-01-extended', 'S-02']: target = max(target, deadline + duration + 1)
            recovered = state(fixture)
            if recovered and recovered.get('endsAt'):
                target = max(target, epoch(recovered['endsAt']) + 1)
                row['endDeadline'] = recovered['endsAt']
            if case == 'S-06':
                pause_until(epoch(before['endsAt']) + .15)
                barrier = threading.Barrier(2)
                failures = []
                def race_recover(base):
                    try:
                        barrier.wait(timeout=5)
                        http('recover', 'POST', base=base)
                    except BaseException as e: failures.append(e)
                racers = [threading.Thread(target=race_recover, args=(base,))
                          for base in [BASE, 'http://127.0.0.1:18085']]
                for racer in racers: racer.start()
                for racer in racers: racer.join(timeout=10)
                if failures or any(r.is_alive() for r in racers):
                    raise RuntimeError('Concurrent recovery failed: ' + repr(failures))
                row['notes'] = 'Both JVM workers invoked concurrently at endsAt+150ms; periodic scans held at 1h.'
            pause_until(target)
        snapshots.append(snapshot(fixture, 'final'))
        final = snapshots[-1]['card']
        row['finalState'] = (final or {}).get('status', 'NONE')
        row['judgedAt'] = utc()
        row['stuckAtJudgement'] = final is not None
        row['outcome'] = ('TIMING_INVALID' if not row['timingValid'] else
                          'STUCK' if final else 'CONVERGED')
        attempts = server_events(fixture['roomId'])
        counts = Counter(e['event']['eventType'] for e in attempts)
        row['serverStartAttempts'] = counts['CARD_EFFECT_STARTED']
        row['serverEndAttempts'] = counts['CARD_EFFECT_ENDED']
        row['duplicateServerEvents'] = sum(max(0, n-1) for n in counts.values())
        policy = os.environ.get('EXPERIMENT_RECOVERY_POLICY', '')
        if row['outcome'] == 'CONVERGED':
            expected_start = 0 if case == 'S-05P' or (case in ['S-01', 'S-02'] and policy == 'cancel') else 1
            expected_end = expected_start
            expected_cancel = 1 if expected_start == 0 else 0
            if (counts['CARD_EFFECT_STARTED'], counts['CARD_EFFECT_ENDED'],
                counts['CARD_ACTIVATION_CANCELLED'], row['duplicateServerEvents']) != (
                    expected_start, expected_end, expected_cancel, 0):
                row['outcome'] = 'INVALID_TRANSITION'
                row['notes'] += ' Unexpected server event counts: ' + repr(dict(counts))
            assignment_states = {a['status'] for a in snapshots[-1]['assignments'].values()}
            expected_assignment = 'USED' if expected_start else 'ASSIGNED'
            if assignment_states != {expected_assignment}:
                row['outcome'] = 'INVALID_ASSIGNMENT'
                row['notes'] += ' Unexpected assignment states: ' + repr(assignment_states)
        if row['restartRequestedAt']:
            anchor = epoch(row['restartRequestedAt'])
            wanted = (['CARD_EFFECT_STARTED', 'CARD_ACTIVATION_CANCELLED'] if row['initialState']=='PENDING'
                      else ['CARD_EFFECT_ENDED'])
            transitions = [e for e in attempts if e['event']['eventType'] in wanted
                           and epoch(e['observedAt']) >= epoch(row['restartRequestedAt'])]
            if case not in ['S-05P', 'S-05A']:
                deadline = row['activationDeadline'] if row['initialState']=='PENDING' else row['endDeadline']
                if deadline: anchor = max(anchor, epoch(deadline))
            if transitions:
                row['recoveryDelayMs'] = round((epoch(transitions[0]['observedAt']) - anchor)*1000)
        received = Counter(e['event']['eventType'] for e in probe.events)
        row['clientStarts'] = received['CARD_EFFECT_STARTED']; row['clientEnds'] = received['CARD_EFFECT_ENDED']
        append_json(RESULTS / 'client-events.jsonl', {'phase': phase, 'testId': case,
                    'iteration': iteration, 'roomId': fixture['roomId'], 'events': probe.events})
        append_json(RESULTS / 'redis_state_before_after.jsonl', {'phase': phase, 'testId': case,
                    'iteration': iteration, 'snapshots': snapshots})
        return row
    finally:
        if case == 'S-06':
            compose('stop', '-t', '1', 'app2', check=False)
            os.environ.pop('EXPERIMENT_RECOVERY_SCAN_DELAY_MS', None)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cases', nargs='+', default=['CONTROL', 'S-01', 'S-02', 'S-03', 'S-04'])
    parser.add_argument('--repetitions', type=int, default=20)
    parser.add_argument('--duration', type=int, default=8)
    parser.add_argument('--phase', default='baseline')
    parser.add_argument('--keep', action='store_true')
    parser.add_argument('--policy', choices=['none', 'cancel', 'bounded-start'], default='none')
    args = parser.parse_args()
    selection = RESULTS/'selected-policy.json'
    if args.phase == 'after-bounded-start' and selection.exists():
        selected = json.loads(selection.read_text())
        if selected.get('policy') == 'cancel':
            append_json(RESULTS/'phase-skips.jsonl', {'at':utc(),'phase':args.phase,
                'reason':'User selected cancellation; alternate delayed-start comparison omitted'})
            print('SKIPPED alternate delayed-start phase: user selected cancellation.', flush=True)
            return
    os.environ['EXPERIMENT_RECOVERY_ENABLED'] = 'false' if args.policy == 'none' else 'true'
    os.environ['EXPERIMENT_RECOVERY_POLICY'] = '' if args.policy == 'none' else args.policy
    if args.policy != 'none': os.environ['CARD_EXPERIMENT_IMAGE'] = 'ssasukae-card-experiment:candidate'
    RESULTS.mkdir(exist_ok=True)
    matrix = RESULTS / 'fault-matrix.csv'
    if not matrix.exists():
        with matrix.open('w', newline='') as stream: csv.DictWriter(stream, FIELDS).writeheader()
    probe = None
    try:
        compose('up', '-d', 'mysql', 'redis', 'app')
        ready()
        verify_configuration(args.policy)
        probe = Probe()
        info = REDIS.command('INFO', 'server')
        (RESULTS / 'redis-version.txt').write_text(info)
        for case in args.cases:
            for iteration in range(1, (1 if case == 'CONTROL' else args.repetitions) + 1):
                row = run_case(case, iteration, args.phase, args.duration, probe)
                with matrix.open('a', newline='') as stream: csv.DictWriter(stream, FIELDS).writerow(row)
                if case in ['S-06', 'S-08']:
                    detail = compose('logs', '--no-color', 'app', 'app2', check=False)
                    (RESULTS / f'{args.phase}-{case}-{iteration}-app.txt').write_text(detail)
                print(json.dumps({k: row[k] for k in ['testId', 'iteration', 'outcome', 'finalState',
                    'restartDurationMs', 'serverStartAttempts', 'serverEndAttempts']}), flush=True)
    except BaseException as error:
        append_json(RESULTS / 'errors.jsonl', {'at': utc(), 'type': type(error).__name__, 'message': str(error)})
        raise
    finally:
        if probe: probe.close()
        logs = compose('logs', '--no-color', 'app', check=False)
        (RESULTS / f'{args.phase}-app.txt').write_text(logs)
        if not args.keep: compose('--profile', 'race', 'down', check=False)
        snapshots = RESULTS / 'redis_state_before_after.jsonl'
        if snapshots.exists():
            (RESULTS / 'redis_state_before_after.json').write_text(
                json.dumps([json.loads(line) for line in snapshots.read_text().splitlines()], indent=2))


if __name__ == '__main__':
    main()
