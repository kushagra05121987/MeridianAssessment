# SE Assessment — Kushagra Mishra

This repo contains my solution to the four-layer API puzzle. I used Java (Spring Boot) as the
main language since it gave me free DI, config, and lifecycle management without much boilerplate.
The app runs entirely as a CLI — no web server, nothing authenticated happens on startup.

---

## How I approached it

My first move was to read the health endpoint and the error envelope to understand what types
the submission endpoint accepts, then figure out the pagination shape before touching any
authenticated call. Once I knew what I was dealing with I started the clock and went layer by layer.

The biggest early gotcha was pagination: the `total` field in the response grows as you make
authenticated requests (each call appends a usage record), so a naive loop comparing
`fetched < total` never terminates cleanly. The fix is to snapshot `totalPages` from page 1
and iterate exactly that many pages regardless of what `total` says later.

---

## Project layout

```
src/main/java/com/assessment/
├── Runner.java                  — CLI entry point, one case per command
├── algorithm/
│   └── QueryEngine.java         — O(N+K) index-based query engine
├── client/
│   ├── AssessmentClient.java    — all HTTP logic (429 retry, bulk token, rate-limit logging)
│   └── Submission.java          — submission DTO
├── config/
│   └── AssessmentProperties.java
└── util/
    ├── Crypto.java              — RSA-PKCS1v15 decrypt + AES helpers
    └── Hashing.java             — SHA-256 / MD5

challenges/
├── algorithm/                   — algorithm challenge (solved)
│   ├── solver.py                — standalone Python solver (reference implementation)
│   ├── README.md
│   ├── RATIONALE.md             — benchmark numbers and design decisions
│   └── tests/test_solver.py
├── design/README.md             — not attempted
└── ui/README.md                 — not attempted
```

---

## Setup

```bash
# Build before starting the clock so the first authed call doesn't wait on a compile
/path/to/mvn -q package -DskipTests

export ASSESSMENT_BASEURL=https://ca-seassessment-api-dev.happywater-190f264d.northcentralus.azurecontainerapps.io
export ASSESSMENT_APIKEY=sa_...   # keep this out of git — load from env or secrets/
```

---

## Solving the layers — step by step

### Layer 1 — content hash

Layer 1 asks you to prove you can fetch the whole dataset and verify byte-level integrity.

**What to do:**
1. Paginate through `GET /api/v1/dataset?page=N` until you've pulled all seed records.
   Snapshot `total_pages = ceil(total / page_size)` from page 1 — don't re-read `total` per
   page or it'll grow underneath you.
2. Base64-decode each record's ciphertext and concatenate all the raw bytes in page order.
3. SHA-256 the concatenated bytes.
4. Submit: `{ "type": "content_hash", "value": "<64-char hex>" }`

**Run it:**
```bash
mvn spring-boot:run -Dspring-boot.run.arguments="solve-layer1"
```

---

### Layer 2 — decrypted hash

Same idea as Layer 1 but you RSA-decrypt each record first before hashing.

**What to do:**
1. Fetch all pages (same snapshot strategy as Layer 1).
2. For each ciphertext: base64-decode → RSA-2048 PKCS#1v1.5 decrypt using the platform-issued
   private key → concatenate the plaintext bytes.
3. SHA-256 the concatenated plaintext.
4. Submit: `{ "type": "decrypted_hash", "value": "<64-char hex>" }`

A few things I got wrong first:
- The platform key is PKCS#8 PEM (`-----BEGIN PRIVATE KEY-----`), not a raw RSA key.
  Java's `KeyFactory` needs `PKCS8EncodedKeySpec` — using `RSAPrivateKeySpec` directly fails.
- The cipher is `RSA/ECB/PKCS1Padding`. I initially tried AES-GCM and AES-CBC before realising
  it was asymmetric.

**Run it (does Layer 1 + 2 in a single page fetch — recommended):**
```bash
mvn spring-boot:run \
  -Dspring-boot.run.arguments="solve-layers-full secrets/private_key.pem"
```

---

### Layer 3 — hidden alphabetic string

The clue: *"You can find a short answer hidden across the decrypted records. The shape of the
answer is alphabetic."*

I spent significant time on this one. The dataset has 500 seed records, each a JSON object with
7 fields: `endpoint`, `latency_ms`, `method`, `request_bytes`, `status_code`, `timestamp`,
`user_segment`. I tried every encoding I could think of — see the full log below. Everything
I submitted came back as `layer: 4`, which is what the API returns for any analysis answer that
isn't the exact Layer 3 match.

**Status: not solved.**

---

### Layer 4 — free-form analysis

Submit anything interesting about the data as `{ "type": "analysis", "value": "..." }`.
The API accepts any analysis answer that isn't the Layer 3 match and records it as Layer 4.
Multiple submissions are allowed.

**Run it:**
```bash
mvn spring-boot:run \
  -Dspring-boot.run.arguments="layer4 'your observation here'"
```

---

## Running the tests

```bash
/path/to/mvn test
```

17 tests across two suites, all passing:
- `UtilTest` (4 tests) — SHA-256 and RSA round-trip checks
- `QueryEngineTest` (13 tests) — unit tests for all three query types using a 12-record
  hand-crafted dataset, no network required

---

## Optional challenges

The platform offers three optional challenges beyond the four layers. Evidence of completion
goes under `challenges/<name>/`.

| Challenge  | Status        | Notes                                              |
|------------|---------------|----------------------------------------------------|
| Algorithm  | **Solved**    | Hash submitted and verified correct by the server  |
| Design     | Not attempted | Brief available at `/api/v1/challenges/design`     |
| UI         | Not attempted | Brief available at `/api/v1/challenges/ui`         |

### Algorithm challenge

Build a query engine over 50,000 records that answers 10,000 queries fast enough that a
naive O(N×K) scan would time out.

**How I solved it:**

The single-record endpoint (`/api/v1/dataset/jumbo/{seq}`) is rate-limited and would take
~14 hours for 50,000 records — clearly the wrong path. The `OPTIONS /api/v1/dataset/jumbo`
response documented a bulk-download flow:

1. `POST /api/v1/dataset/jumbo/bulk-request` → get a 60-second single-use token
2. `GET /api/v1/dataset/jumbo/bulk/{token}` → full 50k-record JSON in one shot (~10 MB),
   no `Authorization` header needed — the token itself is the credential

Once I had the records I built three indices in a single O(N) pass:
- **HashMap** `(user_segment, status_code) → count` for `count` queries
- **HashSet** of `(endpoint, method, status_code, user_segment)` tuples for `exists` queries
- **Sorted int[]** + binary search for `range_count` queries on `latency_ms` and `request_bytes`

Results on my machine:
- Preprocessing 40 ms, 10k queries 7 ms, 0.7 µs per query average
- Server confirmed: `{ "correct": true }`

See `challenges/algorithm/RATIONALE.md` for benchmark details and the design tradeoffs.

---

## Architecture decisions

**Spring Boot as a CLI, not a server**

I used Spring Boot purely for DI and config — `spring.main.web-application-type=none` keeps
startup fast and prevents accidental port binding. The tradeoff is a heavier dependency
footprint than a plain `main()`, but getting `@ConfigurationProperties`, structured logging,
and DI for free was worth it in an assessment context.

**WebClient instead of RestTemplate**

RestTemplate throws an exception on 4xx responses, which swallows the error body. The first
time I hit a 400, I couldn't read the `valid_types` list that would have saved me time.
Switched to WebClient with `exchangeToMono` — it gives access to the status code and body
regardless of HTTP status, and also handles 429 retries cleanly.

**Thin Runner / fat Client split**

`Runner.java` only parses CLI args and calls client methods. All HTTP concerns — retries, rate-
limit header logging, 429 backoff, bulk token lifecycle — live in `AssessmentClient`. Util
classes (`Crypto`, `Hashing`) are pure static methods with no Spring dependencies, so they can
be unit tested without starting a context.

**Secret handling**

API key goes in an env var (`ASSESSMENT_APIKEY`). Private key is passed as a file path arg
and lives in `secrets/` which is gitignored. Neither ever touches `application.properties`
or commit history.

---

## Layer 3 — full investigation log

I tried everything I could think of. All submissions returned `layer: 4`.

**Acrostics on field values**

Took the first (and sometimes last) letter of `user_segment`, `endpoint`, and `method` for
every record in different orderings: natural page order, chronological timestamp order,
strides 2 through 125 (every Nth record). Found real English words like "first", "swim",
"rise" at stride 4 on `user_segment` first letters — but all returned layer 4.

**Numeric field → letter**

Mapped `latency_ms % 26`, `request_bytes % 26`, and `status_code % 26` to `a–z` for each
record. Also filtered to only records where `latency_ms` falls in the ASCII letter range
(65–122) — 6 records → values 119, 65, 80, 71, 97, 71 → "wAPGaG". Not a word.

**Field-value count encoding**

Counted occurrences of each unique endpoint and user_segment across all 500 records, then
tried mapping those counts to letters with various offsets. Endpoint counts in ascending
order give first letters l, u, o, w, s, n, a, p, r, b — no clean word emerges as an
anagram or substring.

Also tried the 4xx status code last-digit trick: 401→a, 403→c, 404→d, 429→i → "acid".
Thematically elegant (ACID = database properties), but wrong.

**Raw RSA padding bytes**

Used Python to compute `pow(ciphertext, d, n)` without unpadding to inspect the PKCS#1v1.5
structure directly. The padding bytes (0x00 0x02 ... 0x00) looked genuinely random — no
hidden message there.

**Description text as the answer**

The hint "layer 3 is in 3" made me wonder if the answer was a word from the clue itself.
Submitted every word in the Layer 3 description: short, answer, hidden, across, decrypted,
records, shape, alphabetic, find, layer, prove, match, correct, digest. All layer 4.

**Field names**

Tried the field names themselves: endpoint, latency, method, request, status, timestamp,
segment. Also tried the acronym from all seven first letters (ELMRSTU). All layer 4.

**Filtered subsets**

Filtered records by method (only GET records, only POST records, etc.) and by status code,
then read first letters of `user_segment` or `endpoint` in timestamp order. Nothing.

Tried: most common `user_segment` per page, first record of each day, first record of each
unique status code, and records sorted by `(status_code, timestamp)`. All layer 4.

**Record boundaries**

Searched the concatenated JSON for English words that span across record boundaries. The
only alphabetic strings at boundaries were fragments: "egment", "gment", "ment" — all tails
of "user_segment".

---

## Scratch work and abandoned approaches

**Pagination loop that never converged**

My first attempt looped while `fetched_so_far < total`. Each page fetch added a usage record,
so `total` kept growing by 1. The loop never exited cleanly and produced a different record
count each run. Snapshotting `totalPages` from page 1 fixed it.

**AES decryption for Layer 2**

Before I knew the key was RSA, I assumed AES-GCM or AES-CBC since those are the most common
symmetric schemes. Added `aesGcmPrefixedNonce` and `aesCbcPrefixedIv` helpers and tried both.
Both threw `BadPaddingException`. Once I saw the key was 2048-bit and the brief mentioned RSA,
that was the end of that.

**Single-record fetching for the jumbo dataset**

I initially thought about paginating the jumbo dataset the same way as the main dataset. Then
I did the arithmetic: 50,000 records at 5 req/reset-window = a very long time. `OPTIONS` on
the jumbo endpoint documented the bulk-download token approach — two requests total instead of
10,000.

**RestTemplate**

Started scaffolding with `RestTemplate` because it is simpler. Switched to `WebClient` after
the first 4xx response threw an exception and I couldn't read the error body. Lesson noted.
