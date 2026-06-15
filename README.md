# SE Assessment Runner

A small Spring Boot CLI for working through the four-layer API puzzle. Spring Boot
is used only for DI / config / lifecycle — there is **no web server** and nothing
authenticated runs on startup, so the 3-hour clock starts only when I choose.

## The clock (read first)

- One fixed **3-hour** window, shared across all four layers and any optional challenges.
- It starts on the **first authenticated request**. After it expires, authed endpoints return `403`.
- `GET /api/v1/health` is unauthenticated and does **not** start the clock — use it freely to warm up.

## Setup (do this BEFORE starting the clock)

```bash
mvn -q test          # confirm utils pass
mvn -q package        # build once so the first authed call isn't waiting on a compile
```

## Commands

```bash
# Safe — no clock:
mvn clean spring-boot:run -Dspring-boot.run.arguments="health" -Dspring-boot.run.jvmArguments="-Dassessment.base-url=https://ca-seassessment-api-dev.happywater-190f264d.northcentralus.azurecontainerapps.io -Dassessment.api-key=sa_3099de164eab348bd3bdbde1cf3c53e0eabf3ac39e963d5ba3aea762d441ac19"
```

### Authed — STARTS THE CLOCK:
```bash
mvn spring-boot:run -Dspring-boot.run.arguments="probe /api/v1/<path>"  -Dspring-boot.run.jvmArguments="-Dassessment.base-url=https://ca-seassessment-api-dev.happywater-190f264d.northcentralus.azurecontainerapps.io -Dassessment.api-key=sa_3099de164eab348bd3bdbde1cf3c53e0eabf3ac39e963d5ba3aea762d441ac19"
```
```bash
mvn spring-boot:run -Dspring-boot.run.arguments="hash /api/v1/<dataset-path>"  -Dspring-boot.run.jvmArguments="-Dassessment.base-url=https://ca-seassessment-api-dev.happywater-190f264d.northcentralus.azurecontainerapps.io -Dassessment.api-key=sa_3099de164eab348bd3bdbde1cf3c53e0eabf3ac39e963d5ba3aea762d441ac19"
```
```bash
mvn spring-boot:run -Dspring-boot.run.arguments="enumerate-types"  -Dspring-boot.run.jvmArguments="-Dassessment.base-url=https://ca-seassessment-api-dev.happywater-190f264d.northcentralus.azurecontainerapps.io -Dassessment.api-key=sa_3099de164eab348bd3bdbde1cf3c53e0eabf3ac39e963d5ba3aea762d441ac19"
```
```bash
mvn spring-boot:run -Dspring-boot.run.arguments="submit <type> <value> <notes>"  -Dspring-boot.run.jvmArguments="-Dassessment.base-url=https://ca-seassessment-api-dev.happywater-190f264d.northcentralus.azurecontainerapps.io -Dassessment.api-key=sa_3099de164eab348bd3bdbde1cf3c53e0eabf3ac39e963d5ba3aea762d441ac19"
```

## Plan of attack

1. **Health probe** (free) — read the body for endpoint hints, version, links.
2. **Enumerate** — once ready to start the clock, send a bad submission to get the
   valid `type` values back in the error envelope. Also locate the time-remaining endpoint.
3. **Layer 1 — fetch + integrity.** Pull the full dataset (paginate/stream as the API
   demands — "efficiently"), hash the bytes, submit the digest. `hash` command does this.
4. **Layer 2 — decrypt.** Obtain the issued key, decode (base64/hex), try AES-GCM
   (12-byte prefixed nonce) then AES-CBC (16-byte prefixed IV). See `Crypto`.
5. **Layer 3 — hidden answer.** Search decrypted records for a short **alphabetic**
   string. Submit for the specific match.
6. **Layer 4 — analysis.** Free-form insight; multiple submissions allowed. Submit
   anything notable that isn't the Layer 3 match here.
7. **Optional challenges** — evidence under `challenges/<name>/`.
8. **Repo** — submit `{ "type": "repo", "value": "<url>" }` **last**.

## Watch the budget

Every authed call logs `RateLimit-*` headers. `429` is auto-retried honouring `Retry-After`.

## Notes / scratch

(Keep a running log here of what each endpoint returned, dead ends, and decisions —
this is explicitly graded.)
