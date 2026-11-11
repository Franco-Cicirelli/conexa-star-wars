#!/usr/bin/env python3
"""Reproducible, local-only HTTP baseline. SWAPI is a fixture server, not the public API."""
import argparse
import concurrent.futures
import datetime
import http.server
import hashlib
import json
import math
import os
import pathlib
import platform
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[1]
FIXTURES = ROOT / 'src/test/resources/swapi'


def free_port():
    with socket.socket() as server:
        server.bind(('127.0.0.1', 0))
        return server.getsockname()[1]


def percentile(values, ratio):
    return sorted(values)[max(0, math.ceil(len(values) * ratio) - 1)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--requests', type=int, default=120)
    parser.add_argument('--concurrency', type=int, default=4)
    parser.add_argument('--warmup', type=int, default=20)
    parser.add_argument('--delay-ms', type=int, default=100)
    parser.add_argument('--output', type=pathlib.Path, default=ROOT / 'docs/benchmarks/release1-initial.json')
    parser.add_argument('--rpm', type=float, help='Fixed request starts per minute; excludes setup and warmup')
    parser.add_argument('--duration-minutes', type=float, help='Measured fixed-rate window; derives the request count')
    parser.add_argument('--export-telemetry', action='store_true', help='Send telemetry to the optional local collector')
    args = parser.parse_args()
    if args.rpm is not None and (not math.isfinite(args.rpm) or args.rpm <= 0):
        parser.error('--rpm must be a finite positive number')
    if args.duration_minutes is not None:
        if args.rpm is None or not math.isfinite(args.duration_minutes) or args.duration_minutes <= 0:
            parser.error('--duration-minutes requires positive --rpm and duration')
        count = args.rpm * args.duration_minutes
        if not count.is_integer() or count < 1:
            parser.error('rpm * duration must be a positive whole number of requests')
        args.requests = int(count)
    if args.requests < 1 or args.concurrency < 1 or args.warmup < 0 or args.delay_ms < 0:
        parser.error('Invalid load parameters')
    for key in ('JWT_PRIVATE_KEY_B64', 'JWT_PUBLIC_KEY_B64'):
        if not os.environ.get(key):
            parser.error('Export .env before running; missing ' + key)
    jar = ROOT / 'target/swapi-challenge.jar'
    if not jar.exists():
        parser.error('Build the application JAR first with ./mvnw verify')
    calls = []
    calls_lock = threading.Lock()

    class Stub(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            started = time.perf_counter()
            parsed = urllib.parse.urlsplit(self.path)
            parts = parsed.path.strip('/').split('/')
            resource = parts[1] if len(parts) > 1 else 'people'
            query = urllib.parse.parse_qs(parsed.query)
            status = 200
            if query.get('name') == ['benchmark-unavailable']:
                status, payload = 503, {'message': 'Simulated unavailable provider'}
            elif len(parts) == 3:
                payload = json.loads((FIXTURES / (resource + '-detail.json')).read_text())
            else:
                suffix = 'name' if 'name' in query else 'page-2'
                payload = json.loads((FIXTURES / (resource + '-' + suffix + '.json')).read_text())
            time.sleep(args.delay_ms / 1000)
            body = json.dumps(payload).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            with calls_lock:
                calls.append({'status': status, 'durationMs': (time.perf_counter() - started) * 1000})

        def log_message(self, *_):
            pass

    stub = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Stub)
    threading.Thread(target=stub.serve_forever, daemon=True).start()
    port, management_port = free_port(), free_port()
    env = dict(os.environ, PORT=str(port), MANAGEMENT_PORT=str(management_port),
               SPRING_PROFILES_ACTIVE='observability', OTEL_TRACES_EXPORT_ENABLED=str(args.export_telemetry).lower(),
               OTEL_METRICS_EXPORT_ENABLED=str(args.export_telemetry).lower(), SWAPI_BASE_URL=f'http://127.0.0.1:{stub.server_port}/api')
    base = f'http://127.0.0.1:{port}'
    log_path = pathlib.Path(tempfile.gettempdir()) / ('conexa-' + args.output.stem + '.jsonl')
    process = None
    try:
        with log_path.open('w') as log:
            process = subprocess.Popen(['java', '-jar', str(jar)], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 45
            while True:
                if process.poll() is not None:
                    raise RuntimeError('Application exited; inspect ' + str(log_path))
                try:
                    with urllib.request.urlopen(base + '/livez', timeout=1) as response:
                        if response.status == 200:
                            break
                except (urllib.error.URLError, TimeoutError):
                    if time.monotonic() >= deadline:
                        raise RuntimeError('Application did not start; inspect ' + str(log_path))
                    time.sleep(0.1)

            credentials = json.dumps({'username': 'baseline-' + str(uuid.uuid4()), 'password': str(uuid.uuid4())}).encode()
            for route in ('register', 'login'):
                with urllib.request.urlopen(urllib.request.Request(base + '/api/v1/auth/' + route, data=credentials,
                        headers={'Content-Type': 'application/json'}), timeout=5) as response:
                    result = json.load(response)
            token = result['accessToken']
            scenarios = [
                ('people.list', '/people?page=2&size=2', 200),
                ('people.detail', '/people?id=1', 200),
                ('people.search', '/people?name=skywalker&page=2&size=1', 200),
                ('films.list', '/films?page=2&size=2', 200),
                ('starships.search', '/starships?name=wing&page=2&size=1', 200),
                ('vehicles.search', '/vehicles?name=speeder&page=2&size=1', 200),
                ('provider.unavailable', '/people?name=benchmark-unavailable', 503),
                ('unauthorized', '/people?page=2&size=2', 401),
            ]

            def execute(index):
                label, path, expected = scenarios[index % len(scenarios)]
                headers = {} if label == 'unauthorized' else {'Authorization': 'Bearer ' + token}
                request_started = time.perf_counter()
                try:
                    with urllib.request.urlopen(urllib.request.Request(base + '/api/v1' + path, headers=headers), timeout=15) as response:
                        response.read()
                        status = response.status
                except urllib.error.HTTPError as error:
                    error.read()
                    status = error.code
                return {'scenario': label, 'status': status, 'expected': expected,
                        'durationMs': (time.perf_counter() - request_started) * 1000,
                        'startedOffsetSeconds': request_started - load_started if load_started is not None else None}

            load_started = None
            for index in range(args.warmup):
                execute(index)
            def client_meter():
                try:
                    with urllib.request.urlopen(f'http://127.0.0.1:{management_port}/actuator/metrics/http.client.requests', timeout=5) as response:
                        payload = json.load(response)
                        return dict({row['statistic']: row['value'] for row in payload['measurements']}, BASE_UNIT=payload.get('baseUnit'))
                except urllib.error.HTTPError as error:
                    if error.code == 404:
                        return {'COUNT': 0, 'TOTAL_TIME': 0}
                    raise
            before_meter = client_meter()
            with calls_lock:
                calls.clear()
            load_started = time.perf_counter()
            load_started_at = datetime.datetime.now(datetime.timezone.utc).isoformat()
            print(f'Load started: {args.requests} requests, rpm={args.rpm}, durationMinutes={args.duration_minutes}', flush=True)
            with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
                if args.rpm is None:
                    results = list(pool.map(execute, range(args.requests)))
                else:
                    interval = 60 / args.rpm
                    pending = []
                    for index in range(args.requests):
                        remaining = load_started + index * interval - time.perf_counter()
                        if remaining > 0:
                            time.sleep(remaining)
                        pending.append(pool.submit(execute, index))
                        if (index + 1) % 10 == 0:
                            completed = [item.result() for item in pending if item.done()]
                            unexpected = sum(row['status'] != row['expected'] for row in completed)
                            print(f'Progress: {index + 1}/{args.requests} started, {len(completed)} completed, {unexpected} unexpected', flush=True)
                    results = [item.result() for item in pending]
                    if args.duration_minutes is not None:
                        remaining = load_started + args.duration_minutes * 60 - time.perf_counter()
                        if remaining > 0:
                            time.sleep(remaining)
            elapsed = time.perf_counter() - load_started
            after_meter = client_meter()
            with calls_lock:
                upstream = list(calls)
            groups = {}
            for row in results:
                groups.setdefault(row['scenario'], []).append(row)
            summary = {}
            for label, rows in groups.items():
                values = [row['durationMs'] for row in rows]
                summary[label] = {'requests': len(rows), 'statuses': {str(code): sum(row['status'] == code for row in rows)
                    for code in sorted({row['status'] for row in rows})},
                    'p50Ms': round(percentile(values, .5), 3), 'p95Ms': round(percentile(values, .95), 3),
                    'maxMs': round(max(values), 3)}
            source_hash = hashlib.sha256()
            for path in sorted(list((ROOT / 'src/main').rglob('*.java')) + list((ROOT / 'src/main').rglob('*.properties'))
                               + list(FIXTURES.glob('*.json')) + [ROOT / 'pom.xml', pathlib.Path(__file__).resolve()]):
                source_hash.update(str(path.relative_to(ROOT)).encode())
                source_hash.update(path.read_bytes())
            measured_client_calls = after_meter.get('COUNT', 0) - before_meter.get('COUNT', 0)
            client_time_unit = after_meter.get('BASE_UNIT')
            seconds_factor = {'seconds': 1, 'milliseconds': .001, 'microseconds': .000001, 'nanoseconds': .000000001}
            if client_time_unit not in seconds_factor:
                raise RuntimeError('Unsupported HTTP timer base unit: ' + str(client_time_unit))
            measured_client_raw_time = after_meter.get('TOTAL_TIME', 0) - before_meter.get('TOTAL_TIME', 0)
            measured_client_seconds = measured_client_raw_time * seconds_factor[client_time_unit]
            report = {'sourceSha256': source_hash.hexdigest(), 'capturedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                'environment': {'os': platform.system(), 'release': platform.release(), 'architecture': platform.machine(),
                    'cpuCount': os.cpu_count(), 'java': subprocess.check_output(['java', '-version'], stderr=subprocess.STDOUT, text=True).splitlines()[0]},
                'configuration': {'profile': 'observability', 'source': 'local SWAPI fixture stub',
                    'requests': args.requests, 'concurrency': args.concurrency, 'warmup': args.warmup,
                    'stubDelayMs': args.delay_ms, 'samplingProbability': 1.0, 'exportersEnabled': args.export_telemetry,
                    'targetRpm': args.rpm, 'durationMinutes': args.duration_minutes,
                    'connectTimeoutSeconds': 1, 'readTimeoutSeconds': 10, 'httpLogs': 'logstash JSON',
                    'loadModel': 'fixed-rate starts, rotating scenarios' if args.rpm is not None else 'closed loop, no think time, equal rotating scenarios', 'fixtures': 'src/test/resources/swapi', 'jvmOptions': 'default JVM settings; no custom heap limit',
                    'requestMix': [{'scenario': label, 'expectedStatus': status} for label, _, status in scenarios]},
                'results': {'loadStartedAt': load_started_at, 'completedRequests': len(results),
                    'actualRpm': round(len(results) * 60 / elapsed, 6),
                    'requestsPerMinute': {str(minute + 1): sum(int(row['startedOffsetSeconds'] // 60) == minute for row in results)
                        for minute in range(max(1, math.ceil(args.duration_minutes if args.duration_minutes is not None else elapsed / 60)))},
                    'maxSchedulingDelayMs': round(max(max(0, row['startedOffsetSeconds'] - index * 60 / args.rpm) for index, row in enumerate(results)) * 1000, 3) if args.rpm is not None else None,
                    'elapsedSeconds': round(elapsed, 3), 'requestsPerSecond': round(len(results) / elapsed, 3),
                    'unexpectedStatuses': sum(row['status'] != row['expected'] for row in results),
                    'swapiCalls': len(upstream), 'httpClientMetricCount': measured_client_calls,
                    'httpClientMetricBaseUnit': client_time_unit, 'httpClientMetricRawTotal': measured_client_raw_time,
                    'httpClientMetricTotalSeconds': round(measured_client_seconds, 6),
                    'httpClientMetricMeanMs': round(measured_client_seconds * 1000 / measured_client_calls, 3) if measured_client_calls else None,
                    'scenarios': summary}}
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(report, indent=2) + '\n')
            print('Baseline saved:', args.output, '| unexpected statuses:', report['results']['unexpectedStatuses'])
            if report['results']['unexpectedStatuses'] or measured_client_calls != len(upstream):
                raise SystemExit(1)
    finally:
        if process is not None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        stub.shutdown()
        stub.server_close()


if __name__ == '__main__':
    main()
