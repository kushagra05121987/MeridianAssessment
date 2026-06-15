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

**Key design choices:**
- All authenticated calls are gated behind explicit CLI commands — importing the Spring context
  is safe; nothing authed runs on startup.
- Dataset pagination uses a snapshot strategy: read `total` from page 1, compute
  `totalPages = ceil(total/page_size)` once, fetch exactly that many pages. Any usage records
  generated during subsequent page fetches land beyond the snapshot window and are excluded —
  giving a stable, reproducible hash.
- `AssessmentClient.getJumboBulk()` mints a single-use 60-second token then redeems it
  immediately (token is the credential; no auth header on the bulk GET).

---

## Layer outcomes

| Layer | Type             | Status    | Notes                                                |
|-------|------------------|-----------|------------------------------------------------------|
| 1     | `content_hash`   | Solved    | SHA-256 of concatenated raw (encrypted) record bytes |
| 2     | `decrypted_hash` | Solved    | SHA-256 of RSA-PKCS1v15 decrypted plaintext bytes    |
| 3     | `analysis`       | Attempted | Hidden alphabetic string across decrypted records    |
| 4     | `analysis`       | Solved    | Free-form analysis accepted                          |

**Layer 3 investigation log:**

Tried every systematic encoding strategy across the 500 seed records:
- First/last letters of `user_segment`, `endpoint`, `method` in various orderings
  (page order, timestamp order, strides 1–125)
- `latency_ms` and `request_bytes` values mod 26, mapped to letters
- Records where field values fall in ASCII letter range (65–122)
- Field-value count offsets mapped to letters for all 10 endpoints and 10 user segments
- Status code digit encodings (all digits, last digits, 4xx only, etc.)
- Words from the description text itself ("short", "hidden", "alphabetic", "records", etc.)
- Technical terms: acid, crud, olap, merge, union, index, latency, segment, endpoint, ...
- Raw RSA PKCS1v15 padding byte inspection

All attempts received `layer: 4` from the server.

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

## Notes / scratch

**Pagination bug (found + fixed):** initial attempt fetched pages in a loop checking
`records_so_far < total`, but `total` was re-read per page and grew as clock-started
usage records accumulated. Switched to snapshot: compute `totalPages` from page 1 only,
fetch exactly that many pages.

**RSA key format:** the platform issues a PKCS#8 PEM (`-----BEGIN PRIVATE KEY-----`).
Java's `KeyFactory` needs `PKCS8EncodedKeySpec`; using `RSAPrivateKeySpec` directly fails.

**Rate limiting:** 5 requests per reset window with `Retry-After` in the 429 body.
`AssessmentClient.fetchPageWithRetry` detects "429" as a body prefix and backs off
before retrying — avoids burning the budget on the 20-page dataset fetch.

**Algorithm challenge bulk endpoint:** the single-record jumbo endpoint is rate-limited
and would take ~14 hours for 50,000 records. The correct path is:
1. `POST /api/v1/dataset/jumbo/bulk-request` → get a 60-second token (costs 1 rate-limit token)
2. `GET /api/v1/dataset/jumbo/bulk/{token}` → full 50k-record JSON in one response
   (no auth header — token is the credential)
