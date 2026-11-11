# Release history

## v1.1.0 — Release 2: Films cache and traffic quotas

### Step 12: traffic quotas

- Added Resilience4j RateLimiter 2.3.0 with explicit `limitForPeriod`,
  `limitRefreshPeriod`, and `timeoutDuration=0`.
- Login has independent five-attempt-per-minute quotas for the normalized
  username and direct connection IP, checked before password verification.
- Catalog users share 60 requests per minute across the four resources.
  Authentication and authorization precede quota acquisition, which precedes
  cache access. Cache hits consume quota; quota failures do not call SWAPI.
- Local quota failures return ProblemDetail 429 with `RATE_LIMIT_EXCEEDED`
  and a conservative full-period `Retry-After: 60`. Provider quota errors
  remain 503. Generic invalid-credential errors remain 401.
- Each identity registry is capped at 10,000 entries. Access-driven cleanup
  removes entries inactive for a full period. Saturation rejects new identities
  without evicting active quotas. Arbitrary proxy headers are ignored.
- Quotas are per process and reset on restart. Periodic refresh allows bursts
  at cycle boundaries and does not enforce an evenly spaced/sliding-window rate.
- Added tests for exhaustion, refresh, identity separation, bounded cleanup,
  security before quotas, quota before hashing, and quota consumption on cached
  Films responses. Benchmark rounds reset quota state to isolate cache measurement.
- Set the Maven application version to 1.1.0; updated the documented JAR path
  and benchmark launcher.

Validation: `./mvnw -B test`, `./mvnw -B verify`, and
`./mvnw -B -Dcache.benchmark=true clean verify` completed successfully.
The final run passed 123 tests (25 unit tests and 98 integration tests,
including the optional cache benchmark), with no failures or skips.

### Step 11: Films cache

Repeated Films collection requests now reuse a validated collection instead of
fetching it again for every page or name filter.

### Changes

- Added Caffeine 3.2.2 with a stable `films:complete` key, a maximum of one entry,
  and expiration five minutes after a successful write.
- Coordinated simultaneous requests through `cache.get(key, loader)` so one
  caller loads the collection while other callers reuse the result.
- Kept filtering, numeric UID ordering, and pagination in the application layer.
  Every request calculates its own matches and totals from the cached collection.
- Kept collection validation before insertion. Loader failures propagate as
  catalog errors and are not stored as successful cache entries.
- Registered cache statistics with Micrometer and isolated cache state between
  integration tests.
- Added controlled-ticker expiry and concurrent-loading tests, plus an opt-in
  authenticated HTTP benchmark with WireMock fixtures.

### Measured results

The local benchmark uses three rounds of 20 requests per scenario, one concurrent
request, a 100 ms simulated provider delay, and eight excluded warmup requests.
It cycles through three Films pages and a name filter and checks that response
bodies remain identical. Each cache-reuse round starts cold.

| Scenario | Requests | External calls | Mean latency | p95 latency |
| --- | ---: | ---: | ---: | ---: |
| Cache invalidated before every request | 60 | 60 | 116.102 ms | 121.613 ms |
| First request with cold cache | 3 | 3 | 115.795 ms | 119.035 ms |
| Warm cache | 57 | 0 | 3.235 ms | 4.671 ms |

Cache reuse recorded 57 hits and three misses, reducing external calls by 95%.
These are local observations with a simulated provider. The baseline emulates
Release 1 repeated loads by invalidating the cache before each request; it does
not run a separate Release 1 binary. See the [measurement report](docs/benchmarks/release2-films-cache.md).

### Validation

```bash
./mvnw -B test
./mvnw -B verify
# Opt in to the measured HTTP workload:
./mvnw -B -Dcache.benchmark=true -Dit.test=FilmsCacheBenchmarkIT verify
```

The benchmark writes a fresh report to `target/films-cache-benchmark.md`. Its
assertions cover response equivalence, external calls, and hits/misses, without
using timing thresholds as correctness checks.

The full local suite with `-Dcache.benchmark=true verify` passed 116 tests:
21 unit tests and 95 integration tests, including the opt-in benchmark.

### Operational scope

The cache is local to one application instance and empties on restart. Values
can remain unchanged for up to five minutes. People, Starships, Vehicles, and
UID detail lookups continue to call the provider directly. Expanding the cache
requires keys that account for every input affecting a result and a decision
about consistency across instances.

## Release 1 — Initial API

- Established Java 21, Spring Boot 4.1.2, and Maven Wrapper with feature-based
  authentication, catalog, and telemetry modules.
- Integrated People, Films, Starships, and Vehicles with pagination, UID/name
  filters, validated SWAPI envelopes, and stable ProblemDetail error codes.
- Added in-memory registration and login, Argon2id password hashing, one
  successful registration per connection IP, and authentication attempt limits.
- Issued RS256 JWTs and protected catalog routes with `catalog:read`, validating
  signature, issuer, audience, and required time claims.
- Added structured logs, Micrometer metrics, OpenTelemetry traces, privacy
  sanitization, and an optional local observability stack.
- Supplied real SWAPI fixtures, reproducible measurements, local setup tooling,
  English documentation, unit/integration tests, and GitHub Actions CI.
- Prepared the Maven cache before Trivy scans and upgraded Bouncy Castle to 1.85
  in response to the findings reported by CI. A clean security scan must be
  confirmed from the corresponding GitHub Actions run.

Accounts are instance-local and disappear on restart. JWT keys are supplied
through environment variables; `.env` and `.secrets/` are excluded from Git.
