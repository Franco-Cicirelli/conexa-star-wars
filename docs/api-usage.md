# API usage and verification

## Base URLs and public routes

Use `http://localhost:8080` locally or
`https://fc-conexa-star-wars-48cdc100c301.herokuapp.com` for the deployed demo.

| Method | Path | Access and purpose |
| --- | --- | --- |
| GET | `/api/v1` | Public API identity; not a dependency health check. |
| GET | `/actuator/health` | Public UP/DOWN status without internal details. |
| GET | `/swagger-ui/index.html` | Public interactive documentation. |
| GET | `/v3/api-docs` | Public OpenAPI JSON. |
| POST | `/api/v1/auth/register` | Register an instance-local account. |
| POST | `/api/v1/auth/login` | Obtain an RS256 Bearer access token. |
| GET | `/api/v1/people`, `/api/v1/films`, `/api/v1/starships`, `/api/v1/vehicles` | Require Bearer JWT and catalog:read. |

## Register, log in, and query using curl

Prerequisites: a running application, curl, and jq. These commands default to a
local instance; set BASE_URL to the Heroku origin to test the demo. Reusing a
registration on an instance is unnecessary: log in with the existing account.

Create `.secrets/request-credentials.json` in the project root using your editor:

```json
{
  "username": "your_username",
  "password": "ChooseYourOwnPassword123!"
}
```

Use your own password. This is an illustrative value, not an existing account.
The `.secrets/` directory is ignored by Git; restrict the file's permissions.

```bash
BASE_URL=http://localhost:8080
chmod 600 .secrets/request-credentials.json

# First successful registration only: expect 201 with a username, no token.
curl --fail-with-body --silent --show-error --include \
  --header 'Content-Type: application/json' \
  --data-binary @.secrets/request-credentials.json \
  "$BASE_URL/api/v1/auth/register"

# Login: capture accessToken in memory without printing it.
TOKEN=$(curl --fail-with-body --silent --show-error \
  --header 'Content-Type: application/json' \
  --data-binary @.secrets/request-credentials.json \
  "$BASE_URL/api/v1/auth/login" | jq -er '.accessToken')

curl --fail-with-body --silent --show-error \
  --header "Authorization: Bearer $TOKEN" \
  "$BASE_URL/api/v1/people?page=1&size=2" | jq .
```

Do not share the token or credentials file. Remove unused credentials files when
finished and `unset TOKEN`. There is no configured demo account. Registration
requires a 1–80 character ASCII username starting with a letter/digit (remaining
characters may also include periods, underscores, and hyphens), plus a nonblank
12–256 character password. Passwords are not trimmed. Duplicate names are checked
case-insensitively, and every registered account receives catalog:read.

One successful registration is permitted per direct connection address for the
instance lifetime. All accounts disappear on restart. Behind Heroku, the direct
address can be a shared router address because forwarded headers are disabled;
the restriction is not a reliable per-browser or end-user public-IP identity.

## Query parameters and response

| Parameter | Accepted values |
| --- | --- |
| `page` | Integer >= 1, default 1. |
| `size` | Integer 1–10, default 10. |
| `id` | Positive numeric UID; leading zeros are normalized. |
| `name` | Trimmed nonempty text up to 80 characters; case-insensitive literal substring. |

ID and name are combined with AND. Films use title for name matching; their UID
is different from episode_id. The public item intentionally contains only id
and name; it is not a full SWAPI entity.

```json
{
  "items": [{"id": "1", "name": "Luke Skywalker"}],
  "page": 1,
  "size": 1,
  "totalItems": 82,
  "totalPages": 82
}
```

The numeric totals above illustrate the captured People dataset and can change
with the provider. Unfiltered People/Starships/Vehicles delegate page/limit and
preserve provider order/totals. Searches, UID results, and Films are paginated
locally; totals are calculated over matches. An out-of-range local page retains
totals and returns empty items. SWAPI may clamp unfiltered out-of-range pages to
its last page; the public page field retains the requested value.

Useful queries with the same Bearer header:

| Path/query | Expected behavior |
| --- | --- |
| `/api/v1/people?id=1&name=luke` | Match Luke when the provider dataset contains that UID/name. |
| `/api/v1/people?name=skywalker&page=2&size=1` | Captured fixture: Anakin only, totalItems 3. |
| `/api/v1/films?page=1&size=2`, then `?page=2&size=2` | Reuse one valid cached collection within its TTL. |
| `/api/v1/films?id=1&name=empire` | Empty items; UID 1 is A New Hope. |
| `/api/v1/starships?name=wing&size=2` | Filter and paginate matching ships. |
| `/api/v1/vehicles?name=speeder&size=2` | Filter and paginate matching vehicles. |
| `/api/v1/people?name=zzzz-no-such-name-987654321` | 200 with empty items when absence is confirmed. |
| `/api/v1/people?page=0` or `?size=11` | 400 with a valid authorized token. |

For names with spaces or special characters, let curl encode the query:

```bash
curl --get --silent --show-error \
  --header "Authorization: Bearer $TOKEN" \
  --data-urlencode 'name=Luke Skywalker' --data 'size=1' \
  "$BASE_URL/api/v1/people" | jq .
```

## Errors and rate limits

| Status/code | Meaning and next step |
| --- | --- |
| 400 / `CATALOG_INVALID_REQUEST` | Fix page, size, UID, or name. |
| 400 / `AUTH_INVALID_REGISTRATION` | Fix registration JSON/fields. |
| 401 | Missing/invalid/expired JWT, or generic invalid login credentials. Log in again where appropriate. |
| 403 | Valid JWT without catalog:read. |
| 409 / `AUTH_USERNAME_EXISTS` | Use the existing account's login or choose another name. |
| 409 / `AUTH_REGISTRATION_IP_USED` | The connection address has already registered an account on this instance. |
| 429 / `RATE_LIMIT_EXCEEDED` | Login/catalog quota exhausted or identity registry full. Honor Retry-After. |
| 429 on registration | Independent registration attempt limit; this endpoint uses Spring's generic ProblemDetail without the login/catalog quota code. |
| 502 / `CATALOG_INVALID_RESPONSE` | Incompatible provider response or unrecognized detail absence. |
| 503 / `CATALOG_UNAVAILABLE` | Connection failure, provider 429, or provider 5xx. |
| 503 / `SWAPI_CIRCUIT_OPEN` | Circuit rejected external work before network I/O. |
| 504 / `CATALOG_TIMEOUT` | Provider timeout or bounded complete-read time budget exceeded. |

Example local quota rejection:

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 60
Content-Type: application/problem+json
```

```json
{"title":"Request quota exceeded","status":429,"code":"RATE_LIMIT_EXCEEDED"}
```

ProblemDetail may also contain type, detail, and instance. Security-layer 401/403
responses can have an empty body; clients should check the HTTP status rather
than assume every rejection has JSON or a custom code.

Login allows five attempts per minute per normalized username and independently
per connection IP, before password verification. Catalog allows 60 requests per
minute per JWT subject across all four resources, including cache hits. Tokens
for the same subject share the quota. Retry-After is a conservative full refresh
period in seconds. Fixed-period refresh permits bursts at cycle boundaries; it
does not pace traffic evenly. All state is per instance.

## Swagger UI and Postman

Import the [Postman collection and environment templates](postman/README.md) for
30 ready-to-run requests with automatic login token capture, inherited Bearer
authentication, and response assertions. Exported credentials/tokens are empty.

In Swagger UI, log in and paste only accessToken into **Authorize → bearerAuth**;
the UI adds the Bearer prefix. Select the resource in the single catalog operation.
Login and registration remain public. Tokens are not persisted by Swagger UI.

In Postman, save the token in the login request's post-response script:

```javascript
if (pm.response.code === 200) {
    pm.collectionVariables.set("accessToken", pm.response.json().accessToken);
} else {
    pm.collectionVariables.unset("accessToken");
}
```

Set collection authorization to Bearer Token `{{accessToken}}`, catalog requests
to inherit it, and login/registration to No Auth. JWT lifetime is 15 minutes;
refresh requires another login because there is no refresh-token endpoint.
