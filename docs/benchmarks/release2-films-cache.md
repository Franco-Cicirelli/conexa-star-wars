# Films cache measurement

Captured at: 2026-11-11T05:04:34.695021Z

Java: 21.0.13; OS: Mac OS X aarch64

Local authenticated HTTP requests, concurrency 1, 100 ms WireMock delay, 8 warmup
requests excluded, three rounds of 20 requests per scenario, alternating scenario
order. Four queries cycle across three pages and a name filter. All responses are
200 and identical between scenarios. Each reuse round starts cold. No public
SWAPI requests or telemetry exporters. The baseline invalidates before each
request to emulate repeated Release 1 loads; this is not a separate Release 1 binary.

| Scenario | Requests | SWAPI calls | Mean ms | p50 ms | p95 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Repeated cold loads | 60 | 60 | 116.102 | 115.307 | 121.613 |
| Cache cold (first request) | 3 | 3 | 115.795 | 116.148 | 119.035 |
| Cache warm | 57 | 0 | 3.235 | 3.151 | 4.671 |

Cache reuse totals: 57 hits, 3 misses. External calls decreased by 95% for this
workload. Warm mean latency decreased by approximately 97.2% (35.9 times faster).
Cold requests still pay the provider latency. These results apply to Films
collection queries; other resources and UID detail queries are not cached.

Reproduce:

```bash
source scripts/java-env.sh
./mvnw -B -Dcache.benchmark=true -Dit.test=FilmsCacheBenchmarkIT verify
```

The opt-in benchmark writes a fresh report to `target/films-cache-benchmark.md`.
It checks response equivalence, external call counts, and hit/miss counts;
latencies are observations rather than pass/fail thresholds.

These are local measurements with simulated provider latency, not production
performance guarantees. This test uses sequential requests within the five-minute
TTL; it does not measure throughput under concurrency or expiry over ten minutes.
