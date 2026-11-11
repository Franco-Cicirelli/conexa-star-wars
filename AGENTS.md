# AGENTS.md

## Project

REST API for the SWAPI challenge. Lists People, Films, Starships, and Vehicles with
pagination and UID/name filters. Catalog data comes from SWAPI.tech. In Release 1,
accounts are registered through the API, stored in memory, and lost on restart.

The baseline is **Java 21, Spring Boot 4.1.2, and Maven Wrapper**. Use Spring MVC,
`RestClient`, Spring Security, and OAuth2 Resource Server. Review existing code and
configuration before editing. For a new repository, first implement People end to
end with a test of the external call.

Write source code, comments, documentation, application messages, and dashboard
labels in English. Preserve public identifiers and error codes. Keep original
upstream fixtures and historical measurements unchanged as evidence; do not
translate captured third-party data or alter recorded measurements.

## Component boundaries

- `catalog.api` receives/validates HTTP and returns public-contract DTOs.
- `catalog.application` selects operations, applies filtering rules, and builds pages.
- `catalog.infrastructure.swapi` encapsulates RestClient, external DTOs, mapping, and provider errors.
- `auth.controller` receives HTTP, maps exceptions through its handlers, and uses public DTOs in `auth.controller.dto`.
- `auth.service` contains use cases, the `AuthenticatedUser` model, and the `AccessTokenIssuer` port; it must not depend on controllers or the JWT implementation.
- `auth.jwt` implements RS256 issuance/validation and key configuration.
- `auth.security` contains Spring Security configuration and attempt limiting.
- `auth.exception` contains registration exceptions only; HTTP handlers belong to `auth.controller`.
- `telemetry` contains conventions, logs, metrics, and observability configuration.
- `ratelimit` owns bounded login/catalog identity registries and local quota errors.
- `catalog.infrastructure.swapi` also owns the Films cache and shared provider circuit; keep security/quota checks ahead of cache access.

Use constructor injection and records for immutable DTOs where appropriate. Reuse
the catalog flow across resources while keeping SWAPI differences explicit. Limit
each change to the requested behavior.

## Catalog contract

Protect GET `/api/v1/people`, `/api/v1/films`, `/api/v1/starships`, and
`/api/v1/vehicles` with the `catalog:read` permission.

- `page` starts at 1 and defaults to 1. `size` accepts 1–10 and defaults to 10.
- `id` is a positive numeric resource UID. Public JSON returns it as a string. Film `episode_id` is the episode number.
- `name` is optional. Trim it when supplied and validate 1–80 characters. Use case-insensitive substring matching. Combine `id` and `name` with AND.
- Return `items`, `page`, `size`, `totalItems`, and `totalPages`. Compute totals over the filtered set. A valid search with no matches returns 200 with empty items. Out-of-range local pages retain totals and return empty items.

Apply these rules in table order:

| Query | Operation |
| --- | --- |
| Any resource with id | Make one detail call by UID, read `result.properties`, apply name if supplied, and paginate zero or one result locally. |
| Films without id | Read the `result` collection, map title to name, filter, sort by numeric UID, and paginate locally. |
| People, Starships, or Vehicles with name only | Use provider search if its contract has been verified. Paginate the complete match set, or delegate pagination/totals when SWAPI applies the same criterion. |
| People, Starships, or Vehicles without filters | Send page and limit=size, read `results`, `total_records`, and `total_pages`, and preserve upstream order. |

When local filtering requires a resource scan, collect all required pages in the
adapter within page and time limits. Calculate totals only from complete results.
Use long for local offsets. A simple listing uses one external call and that page's data.

## Integration and errors

- Configure `swapi.base-url` and connection/read timeouts. Use WireMock in tests. Build paths and parameters with a builder and an enum of allowed resources.
- Validate required envelope fields and map data to the application's contract. Treat only verified provider responses as absence for that operation.
- Use ProblemDetail with stable catalog error codes. Return 400 for invalid input, 502 for incompatible upstream responses, 503 for provider unavailability/quota, and 504 for timeouts. Preserve external failures as errors.

## Security and configuration

- POST `/api/v1/auth/login` authenticates through Spring AuthenticationManager and DaoAuthenticationProvider with InMemoryUserDetailsManager. Start with an empty store and no configured account.
- POST `/api/v1/auth/register` is public. Validate username (1–80 ASCII letters/digits/period/hyphen/underscore, starting with a letter or digit) and password (12–256 characters), hash with Argon2id, and grant catalog:read. Atomically reject case-insensitive duplicates with 409.
- Allow one successful registration per connection IP during the instance lifetime. A second user from that IP receives 409 and AUTH_REGISTRATION_IP_USED. Failed attempts do not consume the quota. Do not trust arbitrary client-supplied IP headers.
- Include Bouncy Castle for Spring's password encoder. Login returns accessToken, tokenType, and expiresIn in seconds, with Cache-Control: no-store. No database is required for Release 1.
- Accounts are instance-local and lost on restart. Persistent registration/password changes require revisiting storage or an identity provider. Password changes do not automatically revoke issued JWTs.
- Issue RS256 JWTs lasting 15 minutes. Validate signature, issuer, audience, and expiration. Use stateless Bearer-header authentication and require SCOPE_catalog:read. Missing/invalid tokens return 401; valid tokens without the permission return 403.
- Disable CSRF only for the API chain using Bearer headers exclusively. Revisit CSRF protection if session cookies are introduced.
- Publicly allow registration/login POSTs, health GET without details, and GETs for `/swagger-ui/**`, `/swagger-ui.html`, and `/v3/api-docs/**`. Public demos need separate registration/login attempt limits and generic invalid-credential errors.
- Load JWT_PRIVATE_KEY_B64, JWT_PUBLIC_KEY_B64, JWT_ISSUER, and JWT_AUDIENCE from the environment under app.jwt properties. Use different key pairs per environment. Private keys use PKCS#8; public keys use X.509 SubjectPublicKeyInfo. Version placeholders in .env.example and ignore .env and .secrets/. Keep passwords, keys, and tokens out of commits, fixtures, logs, and public documentation.
- Do not add JDBC, Flyway, or PostgreSQL solely for this release's in-memory login. Demo credentials are not required in the environment; JWT keys, issuer, and audience are configuration.

## Development and tests

| Command | Purpose |
| --- | --- |
| `./mvnw -v` | Confirm Maven uses Java 21. |
| `./mvnw spring-boot:run` | Start locally with environment variables exported. |
| `./mvnw -B test` | Run *Test unit tests. |
| `./mvnw -B verify` | Run the full suite, including Failsafe *IT integration tests. No Docker or database required. |

- Test filtering/pagination with JUnit. Use small fixtures under src/test/resources/swapi/. Simulate SWAPI with WireMock and exercise the real AuthenticationManager for in-memory login integration. Generate test credentials and keys in the test environment.
- Verify page/size → page/limit mapping, upstream order, filtered totals, and one-call UID-plus-name queries.
- Test Skywalker with page=2 and size=1: Anakin only, totalItems=3. Cover confirmed absence, invalid envelopes, and simulated timeouts returning 504.
- Test login and catalog with signed tokens, including expired tokens, incorrect audience, and missing scope. Ensure invalid/unauthorized queries are rejected before calling SWAPI.

## GitHub and Heroku

- Keep CI on Java 21 with `./mvnw -B verify`. Document startup, authentication, routes, filters, and tests in README.md. Update OpenAPI when its contract changes.
- Heroku uses the Java buildpack on Cedar. Set java.runtime.version=21 in system.properties, finalName=swapi-challenge in the POM, and `web: java -jar target/swapi-challenge.jar` in Procfile. Listen on server.port=${PORT:8080} and load JWT keys from environment variables.
- When deployment is requested, verify the commit that passed CI. Check HTTPS, health, login, and a protected query. Record the Swagger URL and deployed commit in README.md. Share any demo credentials privately.

At the end of a change, report what was verified, command outcomes, and any test
blocked by an environment requirement.

## Observability — Release 1 / Step 08

- Use logstash structured logs with event, normalized route, status, duration, and traceId/spanId. Do not duplicate IDs between MDC and JSON key/value fields.
- Expose only health by default. Metrics/Prometheus belong to the observability profile on a separate management listener bound to 127.0.0.1. Do not activate that profile publicly.
- Retain Boot's RestClient.Builder and Micrometer/OpenTelemetry instrumentation. Never record request/response bodies, credentials, sensitive headers, queries, or URLs containing UIDs.
- Keep HTTP tags bounded; never include user, IP, name, UID, token, or traceId. Sanitize observation exceptions before export without changing functional errors.
- Exporters are optional and disabled by default. Functional tests use WireMock and in-memory spans without depending on Docker.
- Keep liveness independent of SWAPI. Use local stubs and documented load/configuration for baselines; never load-test the public provider.
