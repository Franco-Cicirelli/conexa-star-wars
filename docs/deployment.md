# Heroku deployment and troubleshooting

## Deployment contract

The deployed app is `fc-conexa-star-wars` on the Heroku-24 Cedar stack using the
Java buildpack. The repository must contain these files at the root:

| File | Required content/behavior |
| --- | --- |
| `pom.xml` | Java 21 target, executable Spring Boot JAR, finalName `swapi-challenge`. |
| `system.properties` | `java.runtime.version=21` selects the build/runtime major version. |
| `Procfile` | `web: java -jar target/swapi-challenge.jar` (exact filename, no extension). |
| `src/main/resources/application.properties` | `server.port=${PORT:8080}` and required environment placeholders. |

`mvnw` must be executable. The generated JAR belongs under ignored `target/`; it
is built by Heroku rather than committed. The server listens on Heroku's assigned
PORT. Only health is exposed publicly through Actuator, without internal details.

## Prepare and verify the source

Feature work targets develop; a release branch promotes verified changes to main
through a PR. Do not assume the checked-out branch is what `git push heroku main`
sends: that command explicitly pushes the **local main ref**.

After the release PR is merged and CI passes:

```bash
git fetch origin --tags
git switch main
git pull --ff-only origin main
git status --short
git log -1 --oneline
git ls-tree --name-only HEAD

export JAVA_HOME=$(/usr/libexec/java_home -v 21) # macOS; use your JDK path elsewhere.
export PATH="$JAVA_HOME/bin:$PATH"
chmod +x mvnw
./mvnw -v
./mvnw -B clean verify
ls -lh target/swapi-challenge.jar
```

The tree must include pom.xml, Procfile, and system.properties. Maven must report
Java 21. Check that GitHub CI's tested SHA is the intended source commit. A
version number in the POM alone is not proof that the correct branch was deployed.

## Configure Heroku

Prerequisites: Heroku CLI authenticated to the correct account, access to the
application, and the `heroku` remote pointing to
`https://git.heroku.com/fc-conexa-star-wars.git`.

```bash
heroku auth:whoami
heroku apps:info --app fc-conexa-star-wars
git remote -v
# Run only if the Heroku remote is missing or needs to be configured:
heroku git:remote --app fc-conexa-star-wars
```

Set these config vars through Heroku's application settings or a private secret
provisioning process:

- JWT_PRIVATE_KEY_B64 and JWT_PUBLIC_KEY_B64: a matching RSA pair exclusive to this
  environment (PKCS#8 and X.509 SubjectPublicKeyInfo DER, base64 encoded).
- JWT_ISSUER and JWT_AUDIENCE: explicit consistent values for token issuance and validation.
- SWAPI_BASE_URL is optional and defaults to the public SWAPI provider.

Do not upload `.env`, `.secrets/`, or credentials to GitHub/Heroku Git. Heroku does
not load the development `.env`. Do not activate the local observability profile
for the public web deployment. See [configuration](configuration.md) for formats
and property names; custom JWT property overrides use `app.jwt.*`.

## Deploy and verify

```bash
git push heroku main
heroku ps --app fc-conexa-star-wars
heroku releases --app fc-conexa-star-wars

BASE_URL=https://fc-conexa-star-wars-48cdc100c301.herokuapp.com
curl --fail-with-body --silent --show-error "$BASE_URL/actuator/health"
curl --silent --show-error --output /dev/null --write-out '%{http_code}\n' \
  "$BASE_URL/swagger-ui/index.html"
curl --fail-with-body --silent --show-error "$BASE_URL/v3/api-docs" | jq '.info'
```

Confirm web.1 is up, health is 200/UP without components/details, and Swagger UI,
its assets, and OpenAPI return 200. Use the [API walkthrough](api-usage.md) to
verify registration/login, 401 without a token, and 200 for a protected catalog
query with the issued token. Do not print tokens or include credentials in public
verification evidence. Update the README with the URL, deployed commit, Heroku
release, CI result, and observed checks.

Deployment restarts the process: accounts, IP registrations, cache, quotas, and
circuit state reset. Re-register if necessary. Old JWTs can remain valid until
expiry when signing keys/issuer/audience are unchanged. Origin-based registration
and login restrictions use the connection address, which may be a shared router
address; do not treat it as a reliable end-user public-IP identity on Heroku.
Catalog quotas instead use the authenticated JWT subject.

## Troubleshooting

| Symptom | Likely cause | Check/action |
| --- | --- | --- |
| Buildpack sees only README.md or cannot find pom.xml | Local main points to an old/incomplete commit or the POM is nested. | Inspect `git ls-tree --name-only main`, fetch origin, and fast-forward main after promotion. |
| Non-fast-forward push to Heroku | Heroku was deployed from another branch/history. | Compare `git ls-remote heroku refs/heads/main` with the intended main. Do not force blindly; any intentional replacement must use an expected-commit lease and preserve the intended deployment. |
| Unable to access GitHub / could not read Username | Git HTTPS credentials missing. | Authenticate GitHub CLI or configure the Git credential helper; do not place tokens in remote URLs. |
| Could not resolve JWT_PRIVATE_KEY_B64 / issuer / audience | Required environment variable absent in the actual Spring process. | Load the run configuration environment locally or set the four config vars in Heroku. |
| Base64/key-spec error or matching RSA pair error | Placeholder, PEM text instead of DER, mismatched keys, or short RSA key. | Use the correct encoded formats and a matching pair of at least 2048 bits. |
| Cannot access JAR / web process exits | Procfile and Maven finalName disagree or build failed. | Confirm `target/swapi-challenge.jar`, build success, and exact Procfile path. |
| Port binding fails or app does not serve requests | Ignored PORT or process crash. | Confirm `${PORT:8080}`, web process status, and startup logs. |
| Account that worked before deployment no longer logs in | In-memory store reset. | Register again, respecting the one-registration-per-connection-address policy. |
| Registration 409 | Duplicate username or address already used in this instance. | Check AUTH_USERNAME_EXISTS versus AUTH_REGISTRATION_IP_USED; log in to the existing account if you own its credentials. |
| Swagger 404 | Wrong/old artifact or missing Springdoc. | Confirm v1.2.2+ source, Springdoc 3.2.0, and `/swagger-ui/index.html` / `/v3/api-docs`. |
| Swagger catalog call 401 | Token absent, expired, or pasted with a duplicate Bearer prefix. | Log in and paste only accessToken into Authorize. |
| Local 429 | Login/catalog or registration quota. | Inspect code/Retry-After; do not confuse it with provider quota errors mapped to 503. |
| CATALOG_UNAVAILABLE / CATALOG_TIMEOUT | Provider availability/connection issue or timeout. | Correlate traceId with outgoing-call metrics/logs; a failure is not an empty catalog. |
| SWAPI_CIRCUIT_OPEN | Circuit rejecting external work. | Inspect state metrics/transitions. After the wait a real request can probe; cached Films may still work within TTL. |
| Trivy cannot resolve Maven POMs due to 429 | Maven Central quota during dependency resolution. | Retain the CI Maven-cache/preload steps and honor the upstream retry interval. |

For runtime diagnostics use `heroku logs --app fc-conexa-star-wars --num 100`
privately; share only relevant redacted events. Configuration changes and key
rotation can restart the app; key rotation additionally invalidates tokens signed
by the previous key.
