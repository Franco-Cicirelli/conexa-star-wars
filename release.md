# Release history

## v1.2.3 — Documentation and Postman collection

- Added configuration, API usage, architecture, deployment/troubleshooting, and
  documentation-audit guides, with a navigable documentation map in the README.
- Corrected stale Swagger availability, deployment metadata, package structure,
  origin/router-address explanations, and time-based test descriptions.
- Added focused Javadoc for complete candidate sets, pagination totals,
  registration concurrency, cache loader contracts, circuit permit accounting,
  and bounded quota cleanup.
- Corrected generated OpenAPI response documentation: registration 201,
  authentication/catalog rejection statuses, provider errors, and Retry-After
  for login/catalog quotas. Added generated-document contract assertions.
- Added a Postman v2.1 collection containing 30 requests in seven folders, local
  and Heroku environment templates, automatic login token capture, inherited
  catalog Bearer authentication, and import/runner instructions. Exported
  credentials and tokens are empty.
- Preserved application routes, response bodies, business logic, security,
  cache/quota/circuit policies, provider fixtures, and historical raw measurements.
- Set the Maven application version to 1.2.3. The Heroku executable path remains
  `target/swapi-challenge.jar`; this release preparation does not deploy the app.

Verification covers the full Java 21 Maven suite, generated OpenAPI contracts,
Markdown links/shell syntax, the official Postman collection schema, environment
structure, script syntax, and offline token/credential handling. No Postman HTTP
requests were executed against Heroku as part of asset validation.

Release preparation: Java 21 `./mvnw -B -Dcache.benchmark=true clean verify`
passed 138 tests (30 unit and 108 integration tests), with no failures or skips,
and generated `target/swapi-challenge.jar`. Markdown checks passed for 12 files,
45 local links/anchors, and 21 shell blocks.

## v1.2.2 — Swagger UI and OpenAPI

- Added springdoc-openapi-starter-webmvc-ui 3.2.0 for Spring Boot 4.1.x.
- Exposed public Swagger UI at `/swagger-ui/index.html`, its assets, and the
  OpenAPI document at `/v3/api-docs` using existing Spring Security allow rules.
- Documented HTTP Bearer JWT authentication on catalog operations only. Login,
  registration, and API information remain public; catalog security is unchanged.
- Configured a relative OpenAPI server URL so interactive calls use the current
  origin and HTTPS behind Heroku. Tokens are not persisted and external schema
  validation is disabled.
- Documented catalog resource values, request behavior, login/registration, and
  password fields. Added integration tests for public UI/assets/configuration,
  relative server URL, security requirements, and catalog rejection without JWT.
- Bumped the Maven version to 1.2.2; the executable JAR remains
  `target/swapi-challenge.jar`.

Validation: `./mvnw -B -Dcache.benchmark=true clean verify` on Java 21 passed
137 tests (30 unit and 107 integration tests), with no failures or skips.

## v1.2.1 — Heroku deployment preparation

- Added `system.properties` with `java.runtime.version=21` and the extensionless
  `Procfile` with `web: java -jar target/swapi-challenge.jar`.
- Aligned Maven's final artifact name, the local benchmark launcher, and the
  documented startup command to `swapi-challenge.jar`.
- Kept `server.port=${PORT:8080}` so Heroku supplies the listening port, and
  made the provider URL configurable with `SWAPI_BASE_URL` while retaining
  https://www.swapi.tech/api as the default.
- Migrated JWT Spring properties from `auth.jwt.*` to `app.jwt.*` in production
  code and test overrides. Both RSA keys, issuer, and audience are required
  environment variables; the local startup script validates all four.
- Preserved health-only public Actuator exposure without details.
- Renamed WelcomeController/WelcomeResponse to ApiInfoController/ApiInfoResponse
  while preserving GET `/api/v1` and its response JSON.
- Bumped the application version to 1.2.1. The circuit breaker, Films cache,
  and traffic quotas from previous releases remain included.

Migration: custom Spring property overrides must use `app.jwt.*`. Set
`JWT_PRIVATE_KEY_B64`, `JWT_PUBLIC_KEY_B64`, `JWT_ISSUER`, and `JWT_AUDIENCE` in
the deployment environment. Local `.env` files are not uploaded to Heroku.

This release prepares the application for deployment; publishing it does not
deploy a Heroku app or configure its environment variables.

Validation: Maven used Temurin Java 21.0.13; `./mvnw -B clean verify` completed
with 133 tests passed and the optional benchmark skipped. The build generated
`target/swapi-challenge.jar`, matching the Procfile startup command.

## v1.2.0 — Release 3: SWAPI circuit breaker

- Added one shared Resilience4j circuit around physical SWAPI calls across People,
  Films, Starships, and Vehicles, including paginated fallback reads.
- Configured a ten-call count-based window, minimum five calls, 50% failure
  threshold, ten-second OPEN wait, and two HALF_OPEN probe permits.
- Recorded connection failures, timeouts, provider 429, and provider 5xx. Confirmed
  absence, incompatible responses, invalid requests, and local security/quota
  rejections do not influence the failure ratio.
- Validated envelopes before recording successful probes. Ignored outcomes
  release permits without recovering the circuit.
- Returned 503 with `SWAPI_CIRCUIT_OPEN` on circuit rejection without contacting
  SWAPI. Existing timeouts and external failure mappings remain in place.
- Kept current Films cache hits available during an outage; hits do not update
  the circuit. Cold or expired cache loads use the protected call.
- Bound circuit metrics to Micrometer and logged structured state transitions
  without provider payloads or identity labels. Public metric access is unchanged.
- Added unit and WireMock tests covering thresholds, rolling windows, shared
  rejection across resources, successful recovery, failed probes, two-permit
  HALF_OPEN admission, ignored results, local rejections, cached reads, timeout/
  connection errors, metrics, and logs. Tests advance a controlled clock through
  the configured ten-second wait and reset circuit state between test cases.
- Set the application/JAR version to 1.2.0 and updated the benchmark launcher.

Operational scope: the circuit is per process and resets on restart. After the
OPEN wait, a new request triggers HALF_OPEN; no background probe runs. Two
completed probes decide recovery, and ignored absence/errors do not count as
completed probes. A valid cache entry is used only within its existing TTL.

Validation command: `./mvnw -B -Dcache.benchmark=true clean verify`.
The local run passed 134 tests (30 unit and 104 integration tests), including
the opt-in cache benchmark, with no failures or skips.

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
