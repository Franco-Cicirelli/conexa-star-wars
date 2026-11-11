# Conexa Star Wars API — Release 2 (v1.1.0)

Java 21, Spring Boot 4.1.2, Spring MVC, Spring Security, and RS256 JWTs.
The catalog is fetched from SWAPI.tech. Accounts are registered in memory through
the API and are lost on restart. PostgreSQL and Docker are not required.
Source code, comments, documentation, and application messages are written in English.

## Run locally

From the project root on macOS:

```bash
./scripts/setup-local.sh
./scripts/run-local.sh
```

`run-local.sh` loads `.env`, validates the required variables, and configures Java
before starting Maven. Spring Boot does not load `.env` automatically. To run
Maven directly, export the variables in the same terminal:

```bash
set -a
source .env
set +a
source scripts/java-env.sh
./mvnw spring-boot:run
```

Setup generates a 2048-bit RSA key pair and stores the keys in `.env` and `.secrets/`,
with private permissions and Git ignore rules. It does not overwrite an existing
environment. Do not share these files. The application starts without users;
create accounts through registration. Only JWT keys are required: `DEMO_USERNAME`,
`DEMO_PASSWORD_HASH`, and a preconfigured password are not needed. Any legacy demo
variables still present do not create accounts.

`java-env.sh` uses the configured JDK or the JDK bundled with IntelliJ IDEA.
The API listens at http://localhost:8080; stop it with `Ctrl+C`. If the port is
occupied, use `PORT=8081 ./scripts/run-local.sh`. Initial Maven and dependency
downloads require Internet access; Maven Wrapper is included.

Restart the application after changing configuration variables. In IntelliJ,
open `pom.xml`, use Maven Wrapper, configure JDK 21 or later, and load `.env`
variables into the run configuration. Run
`com.conexa.starwars.ConexaStarWarsApplication`.

## In-memory registration and login

`POST /api/v1/auth/register` is public and accepts JSON:

```json
{
  "username": "alex",
  "password": "ChooseYourOwnPassword123!"
}
```

Choose your own password; this example is not a preconfigured account.
In Postman: **POST** `http://localhost:8080/api/v1/auth/register`,
**Authorization → No Auth**, **Body → raw → JSON**.

Registration returns **201 Created** with `{"username":"alex"}`. It does not issue
a token. Send the same credentials to **POST** `http://localhost:8080/api/v1/auth/login`.
Login returns `accessToken`, `tokenType: "Bearer"`, and `expiresIn: 900`, with
`Cache-Control: no-store`. Use the token as a Bearer token when querying the catalog.

| Registration rule | Behavior |
| --- | --- |
| username | 1–80 characters: ASCII letters, digits, periods, hyphens, or underscores; starts with a letter or digit; no spaces. |
| password | 12–256 characters, not all whitespace. Preserved exactly without trimming. |
| Duplicate name, including different capitalization | 409 with `code: AUTH_USERNAME_EXISTS`; never overwrites the password. |
| Another user from an IP that already registered an account | 409 with `code: AUTH_REGISTRATION_IP_USED`; the account is not created. |
| Invalid JSON or fields | 400 with `code: AUTH_INVALID_REGISTRATION`. |
| Permissions | Always `catalog:read`; clients cannot choose permissions. |

`RegistrationService` hashes the password with Argon2id and stores only the hash
through `InMemoryUserDetailsManager`. Duplicate checks, insertion, and the IP quota
are atomic for concurrent registrations. Spring's `AuthenticationManager` and
`DaoAuthenticationProvider` verify login through the Spring password encoder and
Bouncy Castle. Passwords are not compared manually, and no database is queried.

Unknown users and incorrect passwords return the same generic 401 error.
Only **one successful registration per IP is allowed during the instance lifetime**.
Invalid attempts and duplicate usernames do not consume a new IP's registration.
Even simultaneous requests from the same IP create at most one account. Existing
accounts can continue logging in normally.

The address comes from the connection's `getRemoteAddr()`, not the JSON body or
client-supplied `Forwarded` or `X-Forwarded-For` headers. Users sharing an outgoing
IP share this quota. Restarting clears both accounts and registered IP addresses.
Before deployment behind a proxy, explicitly define which proxy is trusted and
how the client IP is resolved.

Login allows five attempts per minute per normalized identity and independently
per connection IP. Both checks run before authentication/password hashing.
Registration retains its independent limit of ten attempts per minute per IP.
All limits are instance-local.

### Catalog and login quotas

The catalog shares a quota of 60 requests per minute per authenticated JWT subject
across People, Films, Starships, and Vehicles. Authentication and scope checks run
first; authorized requests consume quota before cache lookup or SWAPI access.
Films cache hits therefore count against the same quota. Missing/invalid tokens
return 401 and insufficient scope returns 403 without consuming catalog permits.

Login and catalog use Resilience4j with an immediate acquisition timeout of zero.
Each refresh cycle permits a burst of five login attempts or 60 catalog queries.
Two bursts on opposite sides of a cycle boundary can occur close together: this
is a periodic quota, not a sliding-window limit or evenly paced traffic.

Exhaustion returns 429 with `application/problem+json`, title `Request quota exceeded`,
code `RATE_LIMIT_EXCEEDED`, and `Retry-After: 60`. Retry-After is a conservative full
refresh period in whole seconds, not an exact countdown. No caller waits for a new
window. Provider-side 429 responses still map to 503 through the catalog adapter.

Each of the three registries (login identities, login origins, catalog subjects)
holds at most 10,000 entries. Cleanup runs on access after a period or when full,
removing only identities inactive for at least a complete period. Full registries
reject new identities with 429 instead of evicting active quotas. Entries and
permits reset on restart. Identity values are not used as metric labels or logs.

Configuration: `quota.login.permits=5`, `quota.catalog.permits=60`,
`quota.refresh-period=1m`, and `quota.maximum-identities=10000`.
`server.forward-headers-strategy=none` prevents arbitrary forwarded headers from
changing the connection origin. A global quota across instances requires shared
coordination; trusted proxy handling must be explicitly designed before enabling it.

Implementation reference: [Resilience4j RateLimiter](https://resilience4j.readme.io/docs/ratelimiter).

To reuse the token in Postman, add this to login's **Scripts → Post-response**:

```javascript
if (pm.response.code === 200) {
    pm.collectionVariables.set("accessToken", pm.response.json().accessToken);
} else {
    pm.collectionVariables.unset("accessToken");
}
```

In the collection: **Authorization → Bearer Token → `{{accessToken}}`**.
For catalog requests: **Inherit auth from parent**.
For registration and login: **No Auth**. Log in again when the token expires.

### Tokens and routes

Tokens are signed with RS256 and expire after 15 minutes. They contain `sub`,
`iss`, `aud`, `iat`, `exp`, and `scope`. Scope comes from the authenticated user's
authorities, not the login body, and excludes Spring's internal authentication
factors. The decoder requires an RS256 signature, the expected issuer and audience,
and mandatory claims; it validates timestamps and expiration without additional clock skew.

| Request | Access |
| --- | --- |
| POST `/api/v1/auth/register` and `/api/v1/auth/login` | Public. |
| GET `/actuator/health` | Public, without internal components or details. |
| GET `/swagger-ui/**`, `/swagger-ui.html`, `/v3/api-docs/**` | Publicly permitted; Swagger UI and OpenAPI are not installed in this delivery yet. |
| GET `/api/v1/people`, `/films`, `/starships`, `/vehicles` | Valid Bearer token and `SCOPE_catalog:read`. |
| GET `/api/v1` | Public welcome endpoint. |

Authentication is stateless and uses only the Bearer header; no session cookies
are used. CSRF is disabled only for the `/api/**` chain. Other routes retain CSRF
protection. There is no login form or HTTP Basic authentication.

| Rejection | Result |
| --- | --- |
| Missing/malformed token, incorrect signature/algorithm/issuer/audience, or invalid/missing expiration | 401 without calling SWAPI. |
| Valid JWT without `catalog:read` | 403 without calling SWAPI. |
| Valid JWT with the scope | Validate parameters and execute the catalog query. |

### In-memory account limitations

The application starts with no accounts. **All registered users are lost on
restart** and must register again. Accounts belong to that instance only.
Persistent accounts, shared users across instances, or persistent password changes
require revisiting storage or integrating an identity provider. There is no
password-change endpoint in this delivery.

**Changing a password does not automatically revoke previously issued JWTs.**
They remain valid until expiration as long as their signature, issuer, and audience
remain valid. Tests demonstrate this behavior. Immediate revocation requires an
explicit mechanism; rotating the signing key invalidates tokens signed with the old key.

`JWT_PRIVATE_KEY_B64` is base64-encoded PKCS#8 DER; `JWT_PUBLIC_KEY_B64` is
base64-encoded X.509 SubjectPublicKeyInfo DER. The application requires a matching
RSA pair of at least 2048 bits. Generate separate keys for each environment.
`.env.example` contains placeholders only.

## Catalog

All four routes share the same contract: `items` (string id and name), `page`,
`size`, `totalItems`, and `totalPages`.

| Parameter | Rule |
| --- | --- |
| page | Integer >= 1; default 1. |
| size | Integer from 1 to 10; default 10. |
| id | Optional positive UID; leading zeros are normalized. |
| name | Optional; leading/trailing whitespace is removed; 1–80 characters; literal case-insensitive substring matching. |

Apply these rules in order:

1. With id: one detail call, plus an AND name filter when supplied.
2. Films without id: fetch the full collection, map title to name, sort by numeric
   UID, then filter and paginate locally.
3. Other resources with name: use the verified complete search result, then filter
   and paginate locally. Regex metacharacters require reading the full resource first.
4. Other resources without filters: one call with page and limit=size, preserving
   upstream order and totals.

Local totals are calculated before pagination. The offset uses long; out-of-range
local pages retain totals and return empty items. A missing UID confirmed by a 404
and the resource-specific detail envelope returns an empty page with total 0;
an unrecognized 404 returns 502.

```bash
curl -H "Authorization: Bearer $TOKEN" \
  'http://localhost:8080/api/v1/people?name=skywalker&page=2&size=1'
# Anakin only (UID 11), totalItems: 3, totalPages: 3.
curl -H "Authorization: Bearer $TOKEN" \
  'http://localhost:8080/api/v1/films?id=1&name=empire'
# items: [], totalItems: 0, totalPages: 0.
```

SWAPI interprets names as regular expressions. For regex metacharacters, the adapter
reads pages of 10 with a limit of 20 pages and a 30-second budget, checked before
and after each call. It does not return partial totals or snapshots with inconsistent
metadata or duplicate UIDs. In-flight calls retain their own timeout.

Films use a local Caffeine cache for the complete validated collection. The cache
uses the stable key `films:complete`, expires entries five minutes after writing,
and has a maximum size of one. Each request still applies its own name filter and
page locally. Cache hits, misses, load failures, and evictions are registered in
Micrometer. The cache is instance-local and is empty after a restart.

The base URL is `https://www.swapi.tech/api`; requests cannot choose the upstream
host. Boot's `RestClient.Builder` uses a 1s connection timeout and a 10s read timeout.
To increase the read timeout for one run:
`SPRING_HTTP_CLIENTS_READ_TIMEOUT=15s ./mvnw spring-boot:run`.
Errors use `application/problem+json` and stable codes: invalid input 400,
incompatible response 502, unavailability/quota 503, and timeout 504.
Human-readable messages are in English; machine-readable codes remain unchanged.

The [SWAPI response matrix](docs/swapi-response-matrix.md) and fixtures in
`src/test/resources/swapi/` retain real bodies, headers, URLs, and status codes.
To recapture them: `python3 scripts/probe-swapi.py` (requires Internet access).

## Quality and tests

```bash
source scripts/java-env.sh
./mvnw spotless:apply   # Java AOSP formatting, 4 spaces.
./mvnw -B test          # Unit tests.
./mvnw -B clean verify  # Unit tests, integration tests, and formatting checks.
java -jar target/conexa-star-wars-1.1.0.jar  # Export the environment first.
```

Tests do not require Docker, a database, or the development `.env`. Test RSA keys,
passwords, and hashes are generated in memory; WireMock replaces SWAPI.
The Release 2 suite includes quota exhaustion, renewal, identity separation,
bounded-registry cleanup, security ordering, and Films cache-hit accounting.
The HTTP cache benchmark is opt-in with `-Dcache.benchmark=true`.
Compilation targets Java 21.

| Component | Responsibility |
| --- | --- |
| `catalog.api` | HTTP validation and public errors. |
| `CatalogService` / `CatalogGateway` | Query rules and external port. |
| `SwapiClient` / `SwapiResponseMapper` | HTTP and envelope translation. |
| `CompleteResourceReader` / `CatalogPage.slice` | Bounded complete reads and pagination. |
| `UserAuthenticationConfiguration` | Initially empty user store, provider, and Spring AuthenticationManager. |
| `RegistrationService` | In-memory registration, Argon2id hashing, username uniqueness, and IP quota. |
| `LoginService` / `AccessTokenIssuer` | Authenticate first, then issue a token for the verified principal. |
| `JwtAccessTokenIssuer` / `JwtConfiguration` | RS256 issuance, keys, and token validation. |

Dependencies use constructor injection. DTOs are records, pages copy results
immutably, and test clocks verify limits and expiration without artificial waits.
Authentication tests are separate from catalog tests and explicitly verify zero
SWAPI calls for 401/403 rejections.

## Logs and telemetry — Release 1 / Step 08

Logstash JSON logs include event, normalized route, status, duration, and
traceId/spanId. Actuator/Micrometer measure the API and RestClient; OpenTelemetry
links incoming and outgoing spans. Only `health` is exposed by default, with
OTLP exporters disabled. The `observability` profile enables metrics only on a
loopback management listener.

The [observability guide](docs/observability.md) covers the optional local stack,
dashboard, privacy, verification, and [initial baseline](docs/benchmarks/release1-initial.json).
The functional suite still runs without Docker or a collector.

## Package architecture

```text
com.conexa.starwars
├── ConexaStarWarsApplication
├── api
│   └── WelcomeController
├── auth
│   ├── controller
│   │   ├── LoginController
│   │   ├── RegistrationController
│   │   ├── RegistrationErrorHandler
│   │   └── dto
│   │       └── LoginResponse
│   ├── service
│   │   ├── LoginService
│   │   ├── RegistrationService
│   │   ├── AccessTokenIssuer
│   │   ├── AuthenticatedUser
│   │   └── IssuedToken
│   ├── jwt
│   │   ├── JwtAccessTokenIssuer
│   │   └── JwtConfiguration
│   ├── security
│   │   ├── SecurityConfiguration
│   │   ├── UserAuthenticationConfiguration
│   │   └── AuthRateLimiter
│   └── exception
│       ├── RegistrationIpAlreadyUsedException
│       └── UsernameAlreadyExistsException
├── catalog
│   ├── api
│   ├── application
│   └── infrastructure
│       └── swapi
│           ├── SwapiCatalogAdapter
│           ├── SwapiClient
│           ├── SwapiResponseMapper
│           ├── SwapiResponses
│           └── CompleteResourceReader
└── telemetry
```

Controllers adapt HTTP to services. `AccessTokenIssuer` returns `IssuedToken`,
an internal service-layer result; `LoginController` maps it to the HTTP DTO
`LoginResponse`. Use cases and JWT implementation therefore do not depend on the
presentation package. The public JSON retains `accessToken`, `tokenType`, and `expiresIn`.

The catalog keeps its `CatalogGateway` port and use cases in `catalog.application`;
the external adapter lives in `catalog.infrastructure.swapi`. Internal client types
remain package-private. Unit tests mirror production packages to access internal
collaborators; authentication HTTP integration tests live in `auth.controller`.

`AuthenticatedUser` belongs to `auth.service`. `RegistrationErrorHandler` belongs
to `auth.controller`, leaving only domain exceptions in `auth.exception`.

This organization preserves endpoints, validation, JWTs, permissions, registration
by IP, and telemetry. Captured baselines remain historical evidence of the measured
code and retain their original fingerprints. Package reorganization was verified
with `./mvnw -B clean verify`: 113 passing tests and a successful Spotless check.
