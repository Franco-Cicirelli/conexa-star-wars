# Postman collection

## Import and configure

Import these files through **Postman → Import → Files**:

- [Collection](conexa-star-wars.postman_collection.json) — Postman Collection v2.1, 30 requests.
- [Local environment](local.postman_environment.json) — http://localhost:8080.
- [Heroku environment](heroku.postman_environment.json) — https://fc-conexa-star-wars-48cdc100c301.herokuapp.com.

Select **Conexa - Local** or **Conexa - Heroku** in Postman's environment selector.
Set your own `username` and `password` in that environment. Leave `accessToken`
empty initially and keep `baseUrl` without a trailing slash. All exported
credentials/tokens are intentionally empty; there is no preconfigured account.

Passwords must contain 12–256 characters. Usernames contain 1–80 ASCII
letters/digits/periods/hyphens/underscores and start with a letter or digit. The
authentication folder JSON-escapes the environment values when constructing the
request body, so special characters in a password remain valid JSON.

## Recommended order

1. Run **01 - Public** to check the instance and documentation.
2. Use **Register account** if needed, then **Login and save accessToken**.
3. Run the People, Films, Starships, and Vehicles folders.
4. Run **07 - Validation and security** to inspect expected rejections.

The collection inherits **Bearer Token → `{{accessToken}}`** for catalog calls.
Public/auth requests explicitly use **No Auth**. The invalid-token request uses
a dummy token only for that request and does not overwrite the valid token.

The login post-response script clears the previous environment token and saves
the new accessToken only from a successful Bearer response. Subsequent requests
use it automatically. Do not add the Bearer prefix to the variable. Tokens expire
after 15 minutes; run login again when needed. The password and token environment
variables are marked secret, but filled environment exports can still contain
their values: share only the empty templates committed here, not populated exports.

## Included cases and assertions

| Folder | Requests | Coverage |
| --- | ---: | --- |
| Public | 5 | API identity, health without internals, Swagger UI, OpenAPI Bearer scheme, Swagger configuration. |
| Authentication | 2 | Registration response/conflict, login status/token/TTL/no-store, automatic token capture. |
| People | 6 | Listing, UID, name, combined filters, Skywalker second page, confirmed empty search. |
| Films | 5 | Two collection pages, UID, title substring, mismatching UID/name producing empty results. |
| Starships | 3 | Listing, UID, name search. |
| Vehicles | 3 | Listing, UID, name search. |
| Validation/security | 6 | Missing/invalid token, page zero, size above ten, nonnumeric UID, whitespace-only name. |

Catalog successes also assert the page contract, string IDs, item names, numeric
totals, and supported page sizes. Assertions do not hardcode live dataset totals;
SWAPI can change. The captured Skywalker fixture has Anakin on page 2 with size 1,
but the live request checks filtering and page metadata without assuming exactly
three matches forever.

The two Films pages demonstrate the usage pattern that can reuse the collection
cache. Their HTTP responses alone do not prove a hit; use private metrics or the
automated cache tests/benchmark for that claim.

## Registration and runner limitations

Registration accepts 201 or the two documented 409 conflict codes in its tests
so an existing account can be reused on a subsequent run. A 409 does **not** prove
the supplied credentials belong to an existing account: login must still succeed.
The app permits one successful registration per direct connection address during
the instance lifetime. Behind Heroku that address may be a shared router address.
Accounts disappear on restart/deployment.

For an existing account, you can skip registration and run login plus the other
folders. Collection Runner executes folders in order. Repeated runs can exceed
the five-login-attempt or 60-catalog-request per-minute quotas and legitimately
produce 429; the response assertions will show failures instead of hiding them.
This collection does not automatically exhaust quotas or simulate provider outages.
The 400 cases require a valid token because authentication happens first.

A 403 needs a valid, correctly signed token without catalog:read; registration
always grants catalog:read, so that scenario remains covered by backend tests
rather than a fabricated token in the collection. Likewise, timeout/circuit/cache
concurrency cases use controlled backend tests, not load against public SWAPI.

Provider outages and OPEN circuit responses can make expected-200 live checks
fail with 502/503/504. Inspect the status/code using the [API guide](../api-usage.md);
do not interpret a provider failure as an empty result.

## Maintaining the assets

Keep endpoint changes synchronized with [API usage](../api-usage.md), OpenAPI,
and these JSON files. The collection uses the official
[Postman Collection v2.1 schema](https://schema.getpostman.com/json/collection/v2.1.0/collection.json).
Keep environment username/password/accessToken values empty in Git. Scripts do
not log credentials or tokens. Creating/importing the files does not execute any
requests or register users; only running requests does.
