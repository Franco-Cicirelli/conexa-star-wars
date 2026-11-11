# Configuration reference

## Runtime prerequisites

Use JDK 21 and the committed Maven Wrapper. Confirm the actual Maven JVM using
`./mvnw -v`; a project language level alone does not select Maven's runtime.
The local key-generation helper requires OpenSSL and a POSIX shell.

On macOS with JDK 21 installed:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
chmod +x mvnw
./mvnw -v
```

`scripts/java-env.sh` accepts an existing JAVA_HOME, otherwise discovers an
installed JDK or IntelliJ's bundled JDK. It does not enforce major version 21;
select Java 21 explicitly when reproducing CI or a release build.

## Environment variables

These names are explicitly referenced by application properties. Configuration
must be present in the process running Spring Boot; Spring does not load `.env`
automatically.

| Variable | Required/default | Purpose |
| --- | --- | --- |
| `JWT_PRIVATE_KEY_B64` | Required | Base64 PKCS#8 DER RSA private key. |
| `JWT_PUBLIC_KEY_B64` | Required | Base64 X.509 SubjectPublicKeyInfo DER RSA public key. |
| `JWT_ISSUER` | Required | Exact issuer used for issuance and validation. |
| `JWT_AUDIENCE` | Required | Audience issued in JWTs and required by the decoder. |
| `PORT` | 8080 locally | HTTP listener; assigned by Heroku. |
| `SWAPI_BASE_URL` | https://www.swapi.tech/api | Provider root, also replaced by WireMock in tests. |
| `OTEL_TRACES_EXPORT_ENABLED` | false | Enable the optional trace exporter. |
| `SPRING_PROFILES_ACTIVE` | None | Use `observability` only for local/private diagnostics. |
| `MANAGEMENT_PORT` | 9091 in observability profile | Separate loopback diagnostics listener. |
| `OTEL_METRICS_EXPORT_ENABLED` | false in observability profile | Optional OTLP metric export. |
| `OTEL_TRACES_ENDPOINT` | http://127.0.0.1:4318/v1/traces in observability profile | Local collector trace endpoint. |
| `OTEL_METRICS_ENDPOINT` | http://127.0.0.1:4318/v1/metrics in observability profile | Local collector metric endpoint. |

The Spring property prefix is **`app.jwt`**, not `auth.jwt`. Private/public keys
must be a matching RSA pair with at least 2048 bits. Base64 encodes DER bytes,
not PEM text or a file path. Use different keys per environment. No demo username
or password hash is configured; accounts are created through registration.

For local setup:

```bash
./scripts/setup-local.sh
./scripts/run-local.sh
```

Setup creates `.env` and `.secrets/` with private permissions and preserves existing
paths instead of overwriting keys. `.env.example` contains placeholders only;
copying it without replacing the key placeholders is insufficient to start.

For a manual Maven invocation in the same terminal:

```bash
set -a
source .env
set +a
./mvnw spring-boot:run
```

For IntelliJ, select JDK 21 for the project, Maven importer, and Maven runner.
Load the four JWT variables into the selected run configuration's environment
(or use environment-file support if available), then run
`com.conexa.starwars.ConexaStarWarsApplication`. Exporting variables in a terminal
does not update the environment of an already-running IDE.

## Spring properties and initial policies

| Property | Default | Meaning |
| --- | --- | --- |
| `spring.http.clients.connect-timeout` | 1s | Provider connection timeout. |
| `spring.http.clients.read-timeout` | 10s | Provider read timeout; integration tests override it to 3s. |
| `swapi.circuit.open-wait` | 10s | OPEN wait before a request can trigger HALF_OPEN. |
| `quota.login.permits` | 5 | Independent per-identity and per-connection-IP login budgets. |
| `quota.catalog.permits` | 60 | Shared catalog budget per authenticated subject. |
| `quota.refresh-period` | 1m | Resilience4j periodic permit refresh. |
| `quota.maximum-identities` | 10000 | Capacity of each of the three quota registries. |
| `server.forward-headers-strategy` | none | Do not trust arbitrary proxy-origin headers. |
| `management.endpoints.web.exposure.include` | health | Public Actuator exposure in the default profile. |
| `management.endpoint.health.show-details` | never | Hide internal health details. |
| `springdoc.swagger-ui.persist-authorization` | false | Do not persist Swagger-entered JWTs. |

Timeouts and quota values can be overridden with Spring arguments. With `.env`
already exported, this changes only one local run:

```bash
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments="--spring.http.clients.read-timeout=15s --quota.catalog.permits=120"
```

The Films TTL (five minutes), cache maximum size (one entry), circuit count window
(ten/minimum five), 50% failure threshold, and two HALF_OPEN permits are explicit
Java configuration, not properties. Registration's ten-attempt-per-minute origin
limit is also configured in code, separately from the login/catalog quotas.

## Observability profile

See [the observability guide](observability.md) for the local collector/dashboard.
The profile enables metrics/Prometheus on `127.0.0.1:9091`; it is not a public
operational interface. Do not activate it on the Heroku web deployment. Exporters
are optional, and the functional suite does not depend on Docker or a collector.

## Related guides

- [API usage](api-usage.md)
- [Deployment and troubleshooting](deployment.md)
- [Architecture](architecture.md)
