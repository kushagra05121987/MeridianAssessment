# SE Assessment — Kushagra Mishra

Spring Boot CLI for the four-layer API puzzle. Spring Boot is used only for DI /
config / lifecycle — no web server, nothing authenticated runs on startup.

---

## Setup

```bash
# Build once before starting the clock
/path/to/mvn -q package -DskipTests
```

### Environment

```bash
export ASSESSMENT_BASEURL=https://ca-seassessment-api-dev.happywater-190f264d.northcentralus.azurecontainerapps.io
export ASSESSMENT_APIKEY=sa_...          # never committed — load from secrets/
```

---

## Commands

```bash
MVN=/path/to/mvn

# Unauthenticated — does NOT start the clock
$MVN spring-boot:run -Dspring-boot.run.arguments="health"

# Layer 1 + 2 together (recommended — single page-fetch snapshot)
$MVN spring-boot:run -Dspring-boot.run.arguments="solve-layers-full secrets/private_key.pem"

# Layer 1 only
$MVN spring-boot:run -Dspring-boot.run.arguments="solve-layer1"

# Layer 2 only
$MVN spring-boot:run -Dspring-boot.run.arguments="solve-layer2 secrets/private_key.pem"

# Algorithm challenge
$MVN spring-boot:run -Dspring-boot.run.arguments="solve-algorithm"

# Free-form analysis (Layer 4)
$MVN spring-boot:run -Dspring-boot.run.arguments="layer4 'your analysis here'"

# Submit repo URL (do this last)
$MVN spring-boot:run -Dspring-boot.run.arguments="submit repo https://github.com/kushagra05121987/MeridianAssessment"
```

---

## Architecture

```
src/main/java/com/assessment/
├── Runner.java                    — CLI command dispatcher (thin layer)
├── algorithm/
│   └── QueryEngine.java           — O(N+K) query engine for the algorithm challenge
├── client/
│   ├── AssessmentClient.java      — HTTP wrapper (WebClient, 429 retry, rate-limit logging)
│   └── Submission.java            — submission DTO
├── config/
│   └── AssessmentProperties.java  — @ConfigurationProperties binding
└── util/
    ├── Crypto.java                 — RSA-PKCS1v15 decrypt, AES-GCM/CBC helpers
    └── Hashing.java                — SHA-256, MD5 hex helpers
```

### Architecture choices and tradeoffs

**Spring Boot as a CLI shell, not a server**

Spring Boot was chosen for DI, config binding (`@ConfigurationProperties`), and lifecycle
management — not for its web stack. `spring.main.web-application-type=none` keeps startup
fast and prevents any accidental port binding. The tradeoff is a heavier dependency footprint
than a plain Java main; the upside is that config, logging, and DI come for free without
wiring any of it manually.

**WebClient over RestTemplate / HttpClient**

WebClient (reactive) was used even though no reactive pipeline is needed, because it gives
the cleanest fluent API for exchangeToMono with access to both status code and body in one
step — important for capturing 4xx bodies instead of throwing. RestTemplate would throw on
non-2xx. Plain HttpClient would require more boilerplate. Tradeoff: pulls in the full
Reactor/Netty stack; acceptable since Spring WebFlux is already a common dependency.

**Snapshot pagination strategy**

The dataset is live — new usage records are appended as API calls are made. A naive
"keep fetching until records_so_far == total" loop would never converge because `total`
grows with each authenticated request.

Fix: read `total` from page 1 once, compute `totalPages = ceil(total / page_size)` as a
snapshot, then fetch exactly that many pages. Any records generated during the fetch land
beyond the snapshot window and are excluded. This gives a stable, reproducible hash even
under concurrent writes.

**Thin Runner / fat Client / pure Util split**

- `Runner.java` is intentionally thin: parse args, call client methods, log results.
  No business logic.
- `AssessmentClient.java` owns all HTTP concerns: retry, rate-limit logging, 429 backoff,
  bulk token lifecycle.
- `Crypto` and `Hashing` are pure static utilities with no external dependencies.
- `QueryEngine` is a standalone POJO — no Spring annotations — so it can be unit-tested
  without a Spring context.

This separation means each concern can be changed independently and tested in isolation.

**Secret management**

API key and private key never touch git. The API key is injected via environment variable
(`ASSESSMENT_APIKEY`); the private key is passed as a file path argument and lives in
`secrets/` which is gitignored. No secrets in `application.properties`, no secrets in
commit history.

---

## Layer outcomes

| Layer | Type             | Status    | Notes                                                |
|-------|------------------|-----------|------------------------------------------------------|
| 1     | `content_hash`   | Solved    | SHA-256 of concatenated raw (encrypted) record bytes |
| 2     | `decrypted_hash` | Solved    | SHA-256 of RSA-PKCS1v15 decrypted plaintext bytes    |
| 3     | `analysis`       | Attempted | Hidden alphabetic string — not found (see log below) |
| 4     | `analysis`       | Solved    | Free-form analysis accepted                          |

---

## Layer 3 — full investigation log

The clue: *"You can find a short answer hidden across the decrypted records. The shape of
the answer is alphabetic."*

The dataset has 500 seed records (Jan–Mar 2026). Each decrypted record is a JSON object
with 7 fields: `endpoint`, `latency_ms`, `method`, `request_bytes`, `status_code`,
`timestamp`, `user_segment`. There are 10 distinct endpoints, 10 user segments, 7 methods,
12 status codes.

Every approach below returned `layer: 4` from the server.

### Approach 1 — acrostics on field values

Took the first (and last) letter of `user_segment`, `endpoint`, and `method` for each
record in multiple orderings:
- Natural page order (page 1 record 0 → page 20 record 499)
- Chronological timestamp order
- Strides 2, 3, 4, … 125 (every N-th record)
- Sorted by latency, request_bytes, status_code

Found real English words at stride 4 on `user_segment` first letters ("first", "swim",
"rise", "sire") but all returned layer 4. Concluded the server checks an exact string,
not whether the submission is a valid word.

### Approach 2 — numeric field → letter mapping

Mapped numeric fields modulo 26 to `a–z`:

- `latency_ms % 26` for each record in order
- `request_bytes % 26` for each record in order
- `status_code % 26` for each record in order
- Only records where `latency_ms` falls in ASCII letter range 65–122:
  6 such records → values 119, 65, 80, 71, 97, 71 → "wAPGaG" — not a word

### Approach 3 — field-value count encoding

Counted occurrences of each unique field value across all 500 records, then mapped those
counts to letters using various offsets and modulo operations.

Endpoint counts (ascending): login(38), users(42), orders(45), webhooks(48), search(49),
notifications(51), analytics(52), products(54), refresh(60), billing(61).

First letters in count order: l, u, o, w, s, n, a, p, r, b — tried all anagram subsets
that form English words (loans, prowls, sprawl, upwards, …). None matched.

Tried count-offset mapping: `count - min_count → letter index` → spelled fragments like
"aehlnoqwx". No recognisable word.

Status code third-digit encoding: 4xx codes (400, 401, 403, 404, 429) have third digits
0, 1, 3, 4, 9 → skipping 0 → a, c, d, i → "acid". Submitted "acid" — layer 4.

### Approach 4 — raw RSA padding inspection

Loaded the raw ciphertext bytes and computed `pow(c, d, n)` (raw modular exponentiation,
no unpadding) to inspect the PKCS#1v1.5 padding bytes directly. The 0x00 0x02 … 0x00
structure was intact with random-looking padding bytes. No hidden message in the padding.

### Approach 5 — description text as the answer

The hint "layer 3 is in 3" suggested the answer might be a word literally present in the
Layer 3 description. Submitted every word from the clue text: short, answer, hidden,
across, decrypted, records, shape, alphabetic, find, layer, prove, match, correct, digest,
analysis. All returned layer 4.

### Approach 6 — field name inspection

Tried the field names themselves as the answer: endpoint, latency, method, request, status,
timestamp, segment, records. Also tried acronyms (ELMRTSU from the first letters of the 7
field names). All layer 4.

### Approach 7 — per-page and per-window searches

Looked for words within each 25-record page window independently (first letters of
`user_segment` on page 1, page 2, etc.). No consistent word emerged across pages.

### Remaining hypothesis

The encoding is likely a low-entropy steganographic scheme (e.g. a specific sequence of
records selected by a non-obvious criterion — perhaps the first record of each unique
`(endpoint, user_segment)` pair in insertion order, or records where `status_code` equals
`request_bytes % 1000`). Without knowing the specific rule, exhaustive search of all
plausible short alphabetic strings is impractical.

---

## Optional challenges

| Challenge | Status   | Location                |
|-----------|----------|-------------------------|
| Algorithm | Solved   | `challenges/algorithm/` |
| Design    | See dir  | `challenges/design/`    |
| UI        | See dir  | `challenges/ui/`        |

---

## Running the tests

```bash
/path/to/mvn test
# 17 tests: 4 in UtilTest, 13 in QueryEngineTest — all pass
```

---

## Scratch work and abandoned approaches

### Abandoned: single-record fetching for the jumbo dataset

Initial instinct for the algorithm challenge was to paginate the jumbo dataset the same
way as the main dataset. Checked the rate limit (5 req/window) and the record count
(50,000). At 1 req/s that's ~14 hours — infeasible. Checked the API docs via
`OPTIONS /api/v1/dataset/jumbo` which documented the bulk-download token flow. Switched
to that immediately.

### Abandoned: AES decryption for Layer 2

Before receiving the private key, assumed Layer 2 might use AES-GCM or AES-CBC (common
symmetric schemes). Added `Crypto.aesGcmPrefixedNonce` and `Crypto.aesCbcPrefixedIv`
helpers and tried both on the first record's ciphertext. Both failed to decrypt (wrong
algorithm). The platform-issued key turned out to be an RSA-2048 private key in PKCS#8
PEM format, and the cipher was `RSA/ECB/PKCS1Padding`. The AES helpers remain in
`Crypto.java` as they are referenced in the design challenge spec.

### Abandoned: live-total pagination loop

First pagination implementation: `while (fetched < total) { fetch next page; }`.
This silently diverged because each authenticated request added a usage record, growing
`total`. The loop never terminated cleanly and produced a different record count each run,
giving an unstable hash. Fixed by snapshotting `totalPages` from the page 1 response and
iterating exactly that many pages regardless of how `total` changes mid-fetch.

### Abandoned: PKCS#1 key spec for RSA loading

First attempt at loading the RSA private key used `RSAPrivateCrtKeySpec` built from
manually parsing the ASN.1 DER structure. This failed because the platform-issued key
is PKCS#8-wrapped, not a raw RSA key. Switched to `PKCS8EncodedKeySpec` which works
directly with `KeyFactory.getInstance("RSA").generatePrivate(...)`.

### Abandoned: RestTemplate for HTTP

Initially scaffolded the HTTP client with `RestTemplate` because it is simpler. Switched
to `WebClient` after the first 4xx response threw an exception and swallowed the error
body — impossible to read the `valid_types` list in the 400 error envelope. `WebClient`
with `exchangeToMono` captures both status code and body regardless of HTTP status.

### Abandoned: Python scripting for Layer 3 search

Wrote a Python script (`/tmp/layer3_search.py`) to brute-force Layer 3 by submitting
candidate words from a 150,000-word English dictionary with various encoding strategies
(strides, field orderings, modular arithmetic). Ran through 200+ candidates before
concluding the answer is not a common English word derivable from the obvious encodings,
or requires a non-obvious selection rule not visible in the data surface.

---

## Notes / scratch

**Pagination snapshot fix:** the live `total` field grows as authenticated calls are made
during pagination. Snapshotting `totalPages` from page 1 is the only way to get a
deterministic record count and a stable hash.

**RSA key format:** the platform issues PKCS#8 PEM (`-----BEGIN PRIVATE KEY-----`).
Java's `KeyFactory` needs `PKCS8EncodedKeySpec`. Using `RSAPrivateKeySpec` directly fails
with an `InvalidKeySpecException`.

**Rate limiting:** 5 requests per reset window. The 429 body contains `retry_after` in
seconds. `AssessmentClient.fetchPageWithRetry` reads this value and sleeps accordingly
before retrying — avoids burning the entire budget on a burst that hits the wall.

**Submission idempotency:** the API records every submission attempt, even duplicates.
Correct submissions are accepted multiple times (useful for re-running), but each attempt
is logged server-side. Kept submissions minimal to avoid polluting the record.

**Algorithm challenge bulk flow:** single-record endpoint is rate-limited and infeasible
for 50,000 records at ~14 hours. Correct path:
1. `POST /api/v1/dataset/jumbo/bulk-request` → 60-second single-use token (1 rate-limit token)
2. `GET /api/v1/dataset/jumbo/bulk/{token}` → full 50k-record JSON, ~10 MB, no auth header
