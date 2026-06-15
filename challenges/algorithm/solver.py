"""
Algorithm challenge solver — O(N + K) query engine over the 50,000-record jumbo dataset.

Usage:
    export API_KEY=sa_...
    export BASE_URL=https://...
    python solver.py

The script mints a bulk-download token, fetches all 50,000 records, builds
three indices, answers 10,000 queries, and submits the SHA-256 digest.
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

BASE_URL = os.environ.get("BASE_URL", "https://ca-seassessment-api-dev.happywater-190f264d.northcentralus.azurecontainerapps.io")
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
    print(f"  token minted: {token}", flush=True)

    # Bulk endpoint uses the token as the credential — no Authorization header.
    req = urllib.request.Request(BASE_URL + f"/api/v1/dataset/jumbo/bulk/{token}")
    with urllib.request.urlopen(req, timeout=120) as r:
        envelope = json.loads(r.read())
    return envelope["records"]


class QueryEngine:
    """
    Preprocessing: one O(N) pass to build three indices.

    - count_idx   : dict[(user_segment, status_code)] → int
    - exists_idx  : set of (endpoint, method, status_code, user_segment)
    - lat_sorted  : sorted list of latency_ms values  (for binary-search range_count)
    - req_sorted  : sorted list of request_bytes values
    """

    def __init__(self, records: list[dict]) -> None:
        t0 = time.perf_counter()
        self.count_idx: dict[tuple, int] = defaultdict(int)
        self.exists_idx: set[tuple] = set()
        lat: list[int] = []
        req: list[int] = []

        for r in records:
            self.count_idx[(r["user_segment"], r["status_code"])] += 1
            self.exists_idx.add((r["endpoint"], r["method"], r["status_code"], r["user_segment"]))
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
        print("ERROR: set API_KEY environment variable", file=sys.stderr)
        sys.exit(1)

    print("Fetching 50,000-record jumbo dataset …")
    t0 = time.perf_counter()
    records = fetch_jumbo_records()
    fetch_ms = (time.perf_counter() - t0) * 1000
    print(f"  {len(records)} records fetched in {fetch_ms:.0f} ms")

    print("Fetching query batch …")
    batch = authed_get("/api/v1/challenges/algorithm/queries")
    queries = batch["queries"]
    print(f"  {len(queries)} queries")

    print("Building indices …")
    engine = QueryEngine(records)
    print(f"  preprocessing: {engine._preprocess_ms:.1f} ms")

    print("Answering queries …")
    t1 = time.perf_counter()
    answers = engine.answer_batch(queries)
    query_ms = (time.perf_counter() - t1) * 1000
    print(f"  {len(queries)} queries answered in {query_ms:.1f} ms  "
          f"({query_ms / len(queries) * 1000:.1f} µs avg)")

    digest = compute_digest(answers)
    print(f"Digest: {digest}")

    print("Submitting …")
    notes = (
        f"Preprocessing {engine._preprocess_ms:.0f} ms | "
        f"10k queries {query_ms:.0f} ms | "
        f"avg {query_ms / len(queries) * 1000:.1f} µs per query. "
        "Hashmap for count, frozenset for exists, sorted+bisect for range_count."
    )
    resp = authed_post("/api/v1/submit", {"type": "algorithm_answer", "value": digest, "notes": notes})
    print("Response:", resp)
    if resp.get("correct"):
        print("✓ Correct!")
    else:
        print("✗ Wrong — check your answers")


if __name__ == "__main__":
    main()
