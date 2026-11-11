# Release 1 / Step 08: logs and telemetry

## Signals and instrumentation

Actuator and Micrometer produce `http.server.requests` for the API and
`http.client.requests` for SWAPI. `spring-boot-starter-opentelemetry` provides
Micrometer Tracing, the OpenTelemetry bridge, and OTLP with Boot 4.1.2 dependency
management. `micrometer-registry-prometheus` provides local metrics access.

SWAPI retains Boot's RestClient.Builder and timeouts. A customizer explicitly
attaches the registry and safe convention. No manually created RestClient or
second instrumentation is added. Incoming and outgoing requests are spans in
the same trace, with W3C traceparent propagation to the provider.

| Signal | Implementation | Purpose |
| --- | --- | --- |
| Logs | One http.server.completed event and one swapi.client.completed event for each corresponding call | Normalized route, method, status, outcome, duration in ms, traceId/spanId. |
| API | Count, total time, and histograms for http.server.requests | Traffic, 4xx/5xx errors, and p95 by route. |
| SWAPI | Count, total time, and histograms for http.client.requests | Provider time/failures, distinct from validation, 401/403, and local work. |
| Traces | SERVER span and child CLIENT span | Locate request time and correlate logs. |

Default sampling is 10%; it affects traces, not metric counts. The local profile
uses 100% for inspection. Histograms include 50/100/250/500 ms and 1/3/10 s boundaries,
as well as Micrometer's buckets.

## Default public configuration

```properties
logging.structured.format.console=logstash
management.endpoints.web.exposure.include=health
management.endpoint.health.show-details=never
management.endpoint.health.show-components=never
```

Public health and probes do not query SWAPI. Liveness includes only livenessState,
and no external health indicator was added. SWAPI outages/timeouts do not trigger
liveness restarts or make login unavailable.

OTLP trace, metric, and log exporters are disabled by default. Metrics, spans, and
logs are still created without a collector. Info, metrics, prometheus, env,
configprops, and other diagnostics are not publicly exposed. Catalog authorization
rules remain unchanged.

## Privacy and cardinality

- Never log request/response bodies, passwords, JWTs, cookies, Authorization, or
  other headers. Events use an allowlist of fields.
- Replace observation errors with a generic exception without a cause/stacktrace
  before the tracing handler. This prevents exporting errors containing upstream
  bodies or rejected values without changing functional exception handling or HTTP statuses.
- Conventions remove high-cardinality attributes such as the full URL. External
  details use /people/{id}, never /people/43. Search queries are excluded from
  labels, span names, and logs.
- Routes come from a closed set; unrecognized paths use UNKNOWN. HTTP tags include
  normalized route, method, status, outcome, generic exception, and the configured
  client host. Never tag UID, searched name, username, IP, token, or traceId.
  traceId/spanId belong to logs and trace context, not metric tags.
- Internal Spring Security observations are disabled to avoid attaching identities
  to spans. HTTP 401/403 responses remain part of server metrics.
- IDs are added through MDC once, without duplicate JSON fields, including while
  a client span is active.

Small real examples without credentials: [log](benchmarks/log-example.json) and
[two-span trace](benchmarks/trace-example.json). Logs remain on stdout; this delivery
does not configure log shipping to Loki.

## Local profile and metrics/trace view

The functional suite **does not require Docker or this stack**. To use it manually:

```bash
# Once, if .env does not exist yet:
./scripts/setup-local.sh

# Requires running Docker Desktop; pinned image with a large initial download.
docker compose -f observability/compose.yaml up -d

# Once http://127.0.0.1:3000/api/health is ready:
python3 observability/provision-dashboard.py

# Start the application on the host computer:
SPRING_PROFILES_ACTIVE=observability \
OTEL_TRACES_EXPORT_ENABLED=true \
OTEL_METRICS_EXPORT_ENABLED=true \
./scripts/run-local.sh
```

`grafana/otel-lgtm:0.40.0` includes OpenTelemetry Collector, Tempo, Prometheus, and
Grafana. This is a local development tool, not a public deployment configuration.
Allow approximately 2 GB of RAM for the stack and disk space for the image.
The application runs outside the container.

| Address | Purpose and access |
| --- | --- |
| 127.0.0.1:3000 | Local Grafana. Dashboard **Conexa: API and SWAPI**, UID conexa-release1. |
| 127.0.0.1:4318 | OTLP HTTP collector for traces and metrics. |
| 127.0.0.1:9090 | Local Prometheus backend. |
| 127.0.0.1:9091/actuator/prometheus | Private app scrape/diagnostics; observability profile only. |
| 127.0.0.1:9091/actuator/metrics | Private JSON view of application metrics. |
| API port /livez and /readyz | Additional public probes when management uses a separate port. |

The management port is independent and bound to 127.0.0.1; MANAGEMENT_PORT overrides
9091. Metrics are not available on the API port. Diagnostic GETs are permitted on
that local listener without granting operational access to catalog JWTs. Do not
activate this profile or publish these ports in a public deployment; use a private
network or separate operational authentication there.

To run the private profile **without a collector**, omit both export flags:

```bash
SPRING_PROFILES_ACTIVE=observability ./scripts/run-local.sh
```

Profile OTLP endpoints are configurable: OTEL_TRACES_ENDPOINT defaults to
http://127.0.0.1:4318/v1/traces; OTEL_METRICS_ENDPOINT defaults to
http://127.0.0.1:4318/v1/metrics. Metrics export every 5s, and traces export in
batches, so data can take a few seconds to appear. A collector is not a request
dependency, although an enabled exporter can report delivery failures.

The dashboard shows API/SWAPI traffic, errors, p95, and catalog requests per minute.
Micrometer OTLP uses milliseconds in the backend: http_*_requests_milliseconds_*.
Direct Prometheus scrapes use seconds: http_*_requests_seconds_*. Do not mix the
units. The provisioner resolves the local Prometheus datasource; dashboard.json
can also be imported manually into Grafana.

After registering and logging in, send requests using the Bearer token. In
Grafana **Explore → Tempo**, search for service conexa-star-wars or a traceId from
the logs. Authorized queries show an incoming span and a child SWAPI call;
401/403 requests have no external call. Tests verify this relationship.

```bash
# Stop the stack while preserving local data:
docker compose -f observability/compose.yaml down
```

## Reproducible initial measurement

The benchmark never load-tests public SWAPI. It starts a loopback fixture stub
and an isolated JAR on free ports, registers a temporary account, and executes a
closed-loop workload without think time. It stops its processes on exit and does
not save credentials. It does not depend on Docker; OTLP export is off, while
JSON logs and 100% sampling remain on to compare equally instrumented versions.

```bash
source scripts/java-env.sh
./mvnw -B verify
set -a
source .env
set +a
python3 scripts/benchmark.py \
  --requests 120 --concurrency 4 --warmup 20 --delay-ms 100 \
  --output docs/benchmarks/release1-initial.json
```

The report records UTC time, OS, CPU, JDK, configuration, a SHA-256 fingerprint of
sources/configuration/fixtures, statuses, p50/p95/maximum latency, throughput, and
actual Micrometer client counters. Use a different output file and identical
parameters for a later release. This is neither a production capacity test nor
a measurement of real Internet/SWAPI latency.

Initial result: **38.037 requests/s**, 3.155s measured load, **0 unexpected statuses**.
Both the stub and Micrometer observed **105 SWAPI calls**. The 15 rejected 401s
made no external calls. Instrumented client mean: **109.266 ms** with a simulated
100 ms upstream delay.

| Scenario | Requests | Expected status | p50 ms | p95 ms |
| --- | --- | --- | --- | --- |
| people.list | 15 | 200 | 115.838 | 121.252 |
| people.detail | 15 | 200 | 115.45 | 121.654 |
| people.search | 15 | 200 | 117.075 | 120.925 |
| films.list | 15 | 200 | 116.916 | 121.825 |
| starships.search | 15 | 200 | 116.145 | 121.598 |
| vehicles.search | 15 | 200 | 116.974 | 120.705 |
| provider.unavailable | 15 | 503 | 115.861 | 120.962 |
| unauthorized | 15 | 401 | 3.39 | 4.303 |

The 503 responses are injected and expected, so they are not unexpected statuses;
metrics still count them as errors. This baseline does not guarantee performance
on other machines, catalog sizes, or concurrent workloads.

## Verification and future signals

`./mvnw -B clean verify` passed with **113 tests**: 19 unit and 94 integration tests.
Docker and a collector are not suite requirements. Tests verify functional behavior and:

- SERVER/CLIENT spans share traceId; the CLIENT is a child and propagates traceparent.
- JSON logs have IDs and status, without duplicate fields or secrets.
- Changing UID/name does not add value-specific HTTP tags.
- Liveness/readiness remain available with SWAPI down and do not query it.
- Metrics are available through management loopback, not the public port.

Tests use in-memory span exporters and WireMock rather than a real collector.
Real LGTM metric reception and a two-span trace were also checked manually, and
the local dashboard was installed.

| Future release | Proposed signal | Bounded tags |
| --- | --- | --- |
| Cache | Hits/misses, size, load duration | Resource and hit/miss/error outcome. |
| Quota | Accepted/rejected operations and wait time | Operation and outcome; never IP/username. |
| Circuit breaker | State and rejections | Resource and closed/open/half-open state. |
| Concurrent permits | Occupied permits, limit, wait time | Fixed pool name and outcome. |

Do not emit fake metrics for mechanisms that do not exist yet. When adding each
mechanism, preserve HTTP metric names and repeat the baseline to compare outgoing
traffic, latency, and failures. Future changes must not introduce UID, username,
IP, or searched names as metric tags.

### Fixed-rate load: 10 RPM for 10 minutes

With the JAR built, Java configured, and .env exported:

```bash
python3 scripts/benchmark.py \
  --rpm 10 --duration-minutes 10 --concurrency 1 --warmup 0 --delay-ms 100 \
  --export-telemetry \
  --output docs/benchmarks/release1-10rpm-10minutes.json
```

This generates **100 requests**, scheduling one start every 6 seconds. Startup,
registration/login, and private diagnostics are outside the 10-minute window.
`--export-telemetry` uses the local stack to display the load in Grafana; omit it
to run without a collector. The source remains a local SWAPI stub with 100 ms
delay, not the public provider.

The original eight-scenario rotation is preserved: six valid operations, one
simulated upstream 503, and one unauthenticated 401. Thus the load contains 76
200s and 12 each of the expected 503/401 statuses. The 401s do not query SWAPI,
so 88 upstream calls are expected during the measured window.

The report records actual RPM, starts per minute, maximum scheduling delay,
statuses/latencies per scenario, and stub/Micrometer count agreement. Preserve
source, mix, delay, instrumentation, and hardware for controlled comparisons.

Recorded result: **100 requests in 600.006 s**, with **10 starts in each of the ten
minutes**. Actual RPM: **9.999894**; maximum scheduling delay: **6.984 ms**. There
were 76 responses with 200, 12 simulated 503s, and 12 expected 401s, with **0
unexpected statuses**. The stub and Micrometer agreed on **88 external calls**.

Client latency reconstructed from 88 structured events with explicit durationMs
units was **112.273 ms mean** and **119.153 ms p95**. The original raw Actuator
TOTAL_TIME remains in the report without an assigned unit: baseUnit was not yet
captured, and multiple registries make assuming seconds unsafe. The incorrect
conversion was removed; the generator now captures/normalizes the unit for future
runs. Request timing, rate, statuses, and counts are unaffected.

This run exports telemetry, while the initial baseline did not. Do not attribute
latency differences solely to request rate without matching exporter configuration.
Report: [release1-10rpm-10minutes.json](benchmarks/release1-10rpm-10minutes.json).
