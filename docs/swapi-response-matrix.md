# SWAPI.tech: real response matrix

Probes ran on November 10, 2026, against https://www.swapi.tech/api.
Each JSON fixture preserves the full body with its original field names and types.
The matching `.headers` file preserves HTTP status and headers captured with
`curl -isS`. `src/test/resources/swapi/observations.json` records the exact URL,
status, and curl exit code. Do not use `-f` for these captures: a 404 also has a
body worth inspecting. UID is a **string**; episode numbers and totals are numeric.

## Listings and filters

| Operation | Observed HTTP response and envelope | Result | Adapter decision |
| --- | --- | --- | --- |
| Unfiltered People, page=2, limit=2 | 200, `message:"ok"`, `results`, `total_records:82`, `total_pages:41`, `previous`, `next` | UIDs 3 and 4 | Delegate page/size; preserve upstream order and metadata. |
| Unfiltered Starships, page=2, limit=2 | 200, same plural envelope; 36 records, 18 pages | UIDs 5 and 9 | Delegate pagination. |
| Unfiltered Vehicles, page=2, limit=2 | 200, same plural envelope; 39 records, 20 pages | UIDs 6 and 8 | Delegate pagination. |
| People name=skywalker, page=2, limit=1 | 200, `result` array without totals | UIDs 1 Luke, 11 Anakin, 43 Shmi Skywalker | Paginate matches locally. Page 2 with size=1 contains UID **11**. |
| Starships name=wing, page=2, limit=1 | 200, `result` array without totals | UIDs 11, 12, 28, 29, 75; five matches | Paginate locally, preserving this order. |
| Vehicles name=speeder, page=2, limit=1 | 200, `result` array without totals | UIDs 7, 14, 30, 42, 45, 46, 55, 62; eight matches | Paginate locally, preserving this order. |
| Nonexistent name in People, Starships, Vehicles | 200, `message:"ok"`, `result:[]` | No matches | Empty collection and local total 0; neither upstream failure nor detail 404. |
| Names containing spaces | 200, `result` array | Luke Skywalker→1; Star Destroyer→3; Sail barge→24 | Encode with `--data-urlencode`; do not split names into words. |
| Page 9999, limit=2 in People, Starships, Vehicles | 200, last-page `results`, `next:null`, totals preserved | SWAPI clamps to the last page: 41, 18, and 20 respectively | Do not assume an out-of-range page is empty. Define the public policy explicitly; do not report 9999 as the effective upstream page. |
| Films page=2, limit=2 | 200, six-element `result` array without pagination metadata | UIDs 1 through 6 | Paginate the collection locally. |
| Films name=hope, name=A New Hope, or nonexistent name | 200, `result` contains the same six films | **Ignores `name`** | If the public API supports name search, filter by `properties.title` locally before pagination. |
| Films title=hope, page=2, limit=1; nonexistent title | 200, `result` array without totals | hope returns UID 1 despite page=2; nonexistent title returns `[]` | Filtering can also be delegated with title, but matches still need local pagination. |

`starships-all` and `vehicles-all` were captured with page=1 and limit=100.
Filtering their `results[].name` yields the same UID set as the corresponding name
search. That does not justify sorting by UID: the real starship listing contains
11 before 10, and the vehicle listing contains 7 before 6. Preserve each search's
own `result` order.

People returned the same three Skywalker matches with page=1 and page=2, both
with limit=1. For one-based local pagination, use offset `(page - 1) * size`, take
size elements, and calculate totals over all matches. Do not paginate upstream
pages a second time. The public API must validate positive page/size values:
SWAPI accepted page=invalid and returned 200 with the first two People, so it is
not a reliable input validator.

## Details and missing UIDs

| Resource | Match | Nonexistent UID 999999999 |
| --- | --- | --- |
| People | 200, `message:"ok"`, object `result` with uid and properties; UID 1 Luke Skywalker | 404, `message:"not found"`, no result |
| Starships | 200, same envelope; UID 9 Death Star | 404, `message:"Not found"`, no result |
| Vehicles | 200, same envelope; UID 4 Sand Crawler | 404, `message:"Not found"`, no result |
| Films | 200, same envelope; UID **1**, `properties.title:"A New Hope"`, `properties.episode_id:4` | 404, `message:"Film not found"`, no result |

Map names from `properties.name` for People/Starships/Vehicles and from
`properties.title` for Films. In paginated listings, names are directly in
`results[].name`. Film UID and episode number are different concepts.
Recognize absence by status and operation: message capitalization varies.

## External failures: original proposal before implementation

These probes did not observe 429, 5xx, or transport failures. The 404 responses
above represent missing details, not evidence of a provider outage. No fabricated
fixtures are presented as real responses.

The initial interpretation proposed for future adapter operations was:

| Provider signal | Proposed interpretation |
| --- | --- |
| HTTP 429 | Provider quota; an external error, not an empty collection. Record Retry-After if present. |
| HTTP 5xx | External failure; do not assume JSON or a message/result envelope. |
| Timeout | Transport failure without an HTTP status/envelope. |
| DNS/TLS/connection failure | Transport failure; preserve the cause rather than simulating 404. |
| 200 with invalid JSON or an incompatible envelope | Contract failure; do not turn it into absence. |

The original public-API proposal was timeout→504, other provider failures→502,
missing detail→404, and no search matches→200 with an empty page. These were design
decisions at probe time, **not observed upstream statuses or implemented behavior**.
The implemented contract superseding that proposal is documented below.
Error tests use controlled stubs, separate from real fixtures, without causing
outages or exhausting provider quotas.

## Reproduction

Requires curl and Python 3. From the project root, `python3 scripts/probe-swapi.py`
recaptures responses and overwrites fixtures. Case names restrict recapture, for
example `python3 scripts/probe-swapi.py people-name`. Normal tests use the fixtures
without accessing the Internet.

Equivalent curl/jq examples:

```bash
SWAPI=https://www.swapi.tech/api
curl -fsS "$SWAPI/people?page=2&limit=2" |
  jq '{total_records, total_pages, next, results}'
curl -fsS --get "$SWAPI/people/" --data-urlencode 'name=skywalker' --data 'page=2' --data 'limit=1' |
  jq '.result | map({uid, name: .properties.name})'
curl -fsS "$SWAPI/films/1" |
  jq '.result | {uid, title: .properties.title, episode: .properties.episode_id}'
curl -isS --get "$SWAPI/starships/" --data-urlencode 'name=Star Destroyer'
curl -isS --get "$SWAPI/vehicles/" --data-urlencode 'name=Sail barge'
curl -isS --get "$SWAPI/people/" --data-urlencode 'name=zzzz-no-such-name-987654321'
curl -isS "$SWAPI/films/999999999"
```

## Step 04 additions

Six additional probes used page=1 with limit=1 and limit=10 for People, Starships,
and Vehicles. All returned 200, respected the size, and preserved the paginated
envelope. See `*-size-1`, `*-size-10`, and their matching headers.

Two People probes demonstrate ambiguous upstream matching:

| Name | Real response | Decision |
| --- | --- | --- |
| `Skywalker\|Vader` | 200, result contains UIDs 1, 4, 11, 43 | Regex matching, not literal substring matching. |
| `[` | 400, `message:"Could not find person"`, errors field contains a regex error | Do not delegate public names containing regex metacharacters. |

The adapter handles names containing metacharacters by reading the complete
listing, limited to 20 pages and a 30s budget checked before/after each call. It
never returns partial totals. Each in-flight call retains its own timeout. The
application then compares names literally and case-insensitively.
Simple searches still use the provider's complete result set. If that envelope
starts announcing pagination, the adapter rejects it with 502 rather than reporting
partial totals; the new upstream contract must be verified first.

The implemented contract supersedes the initial error proposal:
confirmed missing UID → **200 with an empty page**; incompatible response → 502;
unavailability/quota → **503**; timeout → 504. No fabricated external-failure
fixtures were added: WireMock simulates those errors inside tests.
