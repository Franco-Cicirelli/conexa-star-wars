# Documentation audit — November 11, 2026

## Scope and conclusion

The project had substantial release/observability material, but the setup and
usage requirement was only partially satisfied: several current behaviors were
missing or contradicted elsewhere, and generated OpenAPI showed only HTTP 200
for registration/login/catalog. The registration endpoint actually returns 201.

This audit compares guides with application.properties, security/controller
configuration, application ports/services, provider mapping, cache/quota/circuit
implementation, and existing tests. It preserves captured SWAPI fixtures and
historical measurements. Documentation and Javadoc remain in English.

## Findings and changes

| Finding | Impact | Improvement |
| --- | --- | --- |
| README route table said Swagger was not installed. | Contradicted the working deployed UI. | Corrected public routes and linked the Swagger/API walkthrough. |
| Deployment record still described Heroku v5 / feature source. | Did not identify the later main deployment. | Updated to verified v6, main commit e7a02ae, and its pre-deployment CI. |
| Environment setup lacked one consolidated reference. | Required JWT values, key formats, and overrides were easy to miss. | Added configuration.md covering required variables, Spring prefix, timeouts, quotas, profiles, Java selection, and IntelliJ. |
| No complete curl registration/login/catalog recipe. | Examples assumed a token without showing how to obtain it. | Added api-usage.md with private credential-file usage, in-memory token capture, filters, error codes, Swagger, and Postman. |
| OpenAPI documented registration as 200 and omitted errors. | Interactive documentation disagreed with the HTTP contract. | Added documentation-only response annotations, including 201, quota Retry-After, provider failures, and empty-body security rejections; added generated-contract assertions. |
| Origin wording implied end-user IP identity behind Heroku. | Could misrepresent registration/login restrictions. | Documented direct connection/router-address behavior with forwarded headers disabled. |
| Package tree omitted cache, circuit, quotas, and OpenAPI configuration. | Readers could not locate current features. | Updated README tree and added architecture.md with source links and a request-order diagram. |
| Nontrivial internal contracts had sparse code documentation. | Complete-candidate, absence, concurrency, and time-source semantics required inference. | Added Javadoc around gateway contracts, local pagination, registration atomicity, cache loaders, circuit permit accounting, and bounded identity cleanup. |
| Deployment and known setup errors were scattered in conversation. | Buildpack, old-main, missing-variable, and JAR-name failures were harder to diagnose. | Added deployment.md with prerequisites, build/promotion/deploy checks, and troubleshooting. |
| README claimed every time-based test avoided real waits. | Inaccurate for Resilience4j's internal quota clock. | Clarified controlled cache/circuit time versus the short real quota-refresh test. |
| Historical benchmark commands can run against newer quota/cache/circuit policies. | New measurements could be mistaken for the recorded Release 1 baseline. | Added a version/policy caveat and preserved original raw reports. |

## Requirement coverage

| Requirement | Evidence |
| --- | --- |
| Configuration instructions | [Configuration reference](configuration.md) and README local startup. |
| System usage instructions | [API walkthrough](api-usage.md), Swagger/OpenAPI, and Postman token handling. |
| Clear code documentation | [Architecture guide](architecture.md) and focused Javadoc on application/infrastructure contracts. |
| Operational/deployment instructions | [Deployment guide](deployment.md) and [observability](observability.md). |
| Documented failure behavior | API status/code table, generated response annotations, and OpenApiIT assertions. |

## Verification approach

- Cross-check property defaults, package/class names, statuses, limits, and source links against code.
- Check Markdown relative links and shell example syntax without executing deployment or creating accounts.
- Run Spotless and the full Maven verification suite with Java 21 after adding Javadoc/OpenAPI annotations.
- Preserve application routes, security policies, response bodies, limits, fixtures, and historical measurements.

This branch changes guides, comments, and generated API documentation; it does
not deploy the application or change catalog/authentication business behavior.
Live Swagger retains the previous descriptions until this branch is integrated
and deployed.

## Verification results

- `./mvnw -B spotless:apply -Dcache.benchmark=true clean verify` on JDK 21:
  BUILD SUCCESS, 138 tests passed (30 unit and 108 integration tests), no skips
  or failures. This includes generated OpenAPI status/header assertions.
- Markdown checks: 11 files, 37 local links/anchors, and 21 shell code blocks passed.
  Shell blocks were syntax-checked only; no guide examples were executed.
- `git diff --check` passed. Captured provider fixtures and historical raw benchmark
  files are unchanged.
