"""
Algorithm challenge — O(N + K) query engine over the 50,000-record jumbo dataset.

How it works:
  1. Mint a single-use bulk-download token (POST /api/v1/dataset/jumbo/bulk-request)
  2. Redeem it immediately to pull all 50,000 records in one HTTP response (~10 MB)
  3. Build three in-memory indices in a single O(N) pass over the records
  4. Answer all 10,000 queries in O(1) or O(log N) each
  5. SHA-256 the comma-joined answers and submit

Usage:
    export API_KEY=sa_...
    export BASE_URL=https://...
    python solver.py
"""

import hashlib
import json
import os
import sys
import time
import urllib.request
import urllib.error
from bisect import bisect_left, bisect_right
from collections import defaultdict

BASE_URL = os.environ.get(
    "BASE_URL",
    "https://ca-seassessment-api-dev.happywater-190f264d.northcentralus.azurecontainerapps.io",
)
API_KEY = os.environ.get("API_KEY", "")


def authed_get(path: str) -> dict:
    req = urllib.request.Request(
        BASE_URL + path,
        headers={"Authorization": f"Bearer {API_KEY}"},
    )
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())


def authed_post(path: str, body: dict) -> dict:
    req = urllib.request.Request(
        BASE_URL + path,
        data=json.dumps(body).encode(),
        headers={"Authorization": f"Bearer {API_KEY}", "Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read())


def fetch_jumbo_records() -> list[dict]:
    """Mint a 60-second bulk token and download all 50,000 records in one shot."""
    token_resp = authed_post("/api/v1/dataset/jumbo/bulk-request", {})
    token = token_resp["token"]
    print(f"  bulk token minted: {token}", flush=True)
    # No Authorization header on the bulk GET — the token IS the credential.
    req = urllib.request.Request(BASE_URL + f"/api/v1/dataset/jumbo/bulk/{token}")
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())["records"]


class QueryEngine:
    """
    Builds three indices in a single O(N) pass:

      count_idx  : (user_segment, status_code) → int
      exists_idx : frozenset of (endpoint, method, status_code, user_segment) tuples
      lat_sorted : sorted int[] of latency_ms for binary-search range queries
      req_sorted : sorted int[] of request_bytes
    """

    def __init__(self, records: list[dict]) -> None:
        t0 = time.perf_counter()
        self.count_idx: dict[tuple, int] = defaultdict(int)
        self.exists_idx: set[tuple] = set()
        lat: list[int] = []
        req: list[int] = []

        for r in records:
            self.count_idx[(r["user_segment"], r["status_code"])] += 1
            self.exists_idx.add(
                (r["endpoint"], r["method"], r["status_code"], r["user_segment"])
            )
            lat.append(r["latency_ms"])
            req.append(r["request_bytes"])

        self.lat_sorted = sorted(lat)
        self.req_sorted = sorted(req)
        self._preprocess_ms = (time.perf_counter() - t0) * 1000

    def answer(self, q: dict) -> int:
        op = q["op"]
        if op == "count":
            return self.count_idx[(q["user_segment"], q["status_code"])]
        if op == "exists":
            key = (q["endpoint"], q["method"], q["status_code"], q["user_segment"])
            return 1 if key in self.exists_idx else 0
        if op == "range_count":
            arr = self.lat_sorted if q["field"] == "latency_ms" else self.req_sorted
            lo, hi = q["min"], q["max"]
            return bisect_right(arr, hi) - bisect_left(arr, lo)
        raise ValueError(f"unknown op: {op}")

    def answer_batch(self, queries: list[dict]) -> list[int]:
        return [self.answer(q) for q in queries]


def compute_digest(answers: list[int]) -> str:
    payload = ",".join(str(a) for a in answers)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def main() -> None:
    if not API_KEY:
        print("ERROR: set the API_KEY environment variable first.", file=sys.stderr)
        sys.exit(1)

    print("Fetching 50,000-record jumbo dataset via bulk endpoint...")
    t0 = time.perf_counter()
    records = fetch_jumbo_records()
    print(f"  {len(records)} records in {(time.perf_counter()-t0)*1000:.0f} ms")

    print("Fetching 10,000-query batch...")
    queries = authed_get("/api/v1/challenges/algorithm/queries")["queries"]
    print(f"  {len(queries)} queries")

    print("Building indices (single O(N) pass)...")
    engine = QueryEngine(records)
    print(f"  preprocessing done in {engine._preprocess_ms:.1f} ms")

    print("Answering queries...")
    t1 = time.perf_counter()
    answers = engine.answer_batch(queries)
    query_ms = (time.perf_counter() - t1) * 1000
    print(
        f"  {len(queries)} queries in {query_ms:.1f} ms  "
        f"({query_ms / len(queries) * 1000:.1f} µs avg)"
    )

    digest = compute_digest(answers)
    print(f"Digest: {digest}")

    notes = (
        f"Preprocessing {engine._preprocess_ms:.0f} ms | "
        f"10k queries {query_ms:.0f} ms | "
        f"{query_ms / len(queries) * 1000:.1f} µs avg. "
        "HashMap for count, HashSet for exists, sorted+bisect for range_count."
    )
    resp = authed_post(
        "/api/v1/submit", {"type": "algorithm_answer", "value": digest, "notes": notes}
    )
    print("Server response:", resp)
    print("✓ Correct!" if resp.get("correct") else "✗ Wrong — recheck answers.")


if __name__ == "__main__":
    main()

