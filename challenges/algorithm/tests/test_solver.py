"""Unit tests for the algorithm query engine — no network required."""

import hashlib
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from solver import QueryEngine, compute_digest

# Tiny hand-crafted dataset (12 records).
RECORDS = [
    {"endpoint": "/api/v1/users",   "method": "GET",    "status_code": 200, "user_segment": "premium",    "latency_ms": 50,   "request_bytes": 512,  "timestamp": "2026-01-01T00:00:00Z"},
    {"endpoint": "/api/v1/users",   "method": "GET",    "status_code": 200, "user_segment": "premium",    "latency_ms": 80,   "request_bytes": 1024, "timestamp": "2026-01-01T00:01:00Z"},
    {"endpoint": "/api/v1/users",   "method": "POST",   "status_code": 201, "user_segment": "premium",    "latency_ms": 120,  "request_bytes": 2048, "timestamp": "2026-01-01T00:02:00Z"},
    {"endpoint": "/api/v1/orders",  "method": "GET",    "status_code": 200, "user_segment": "enterprise", "latency_ms": 200,  "request_bytes": 4096, "timestamp": "2026-01-01T00:03:00Z"},
    {"endpoint": "/api/v1/orders",  "method": "DELETE", "status_code": 404, "user_segment": "trial",      "latency_ms": 300,  "request_bytes": 256,  "timestamp": "2026-01-01T00:04:00Z"},
    {"endpoint": "/api/v1/orders",  "method": "DELETE", "status_code": 404, "user_segment": "trial",      "latency_ms": 350,  "request_bytes": 256,  "timestamp": "2026-01-01T00:05:00Z"},
    {"endpoint": "/api/v1/search",  "method": "GET",    "status_code": 200, "user_segment": "free-tier",  "latency_ms": 1000, "request_bytes": 128,  "timestamp": "2026-01-01T00:06:00Z"},
    {"endpoint": "/api/v1/search",  "method": "GET",    "status_code": 429, "user_segment": "free-tier",  "latency_ms": 5,    "request_bytes": 64,   "timestamp": "2026-01-01T00:07:00Z"},
    {"endpoint": "/api/v1/billing", "method": "POST",   "status_code": 500, "user_segment": "enterprise", "latency_ms": 2000, "request_bytes": 8192, "timestamp": "2026-01-01T00:08:00Z"},
    {"endpoint": "/api/v1/billing", "method": "POST",   "status_code": 500, "user_segment": "enterprise", "latency_ms": 2500, "request_bytes": 8192, "timestamp": "2026-01-01T00:09:00Z"},
    {"endpoint": "/api/v1/billing", "method": "POST",   "status_code": 500, "user_segment": "enterprise", "latency_ms": 3000, "request_bytes": 16384,"timestamp": "2026-01-01T00:10:00Z"},
    {"endpoint": "/api/v1/billing", "method": "GET",    "status_code": 200, "user_segment": "enterprise", "latency_ms": 90,   "request_bytes": 512,  "timestamp": "2026-01-01T00:11:00Z"},
]

ENGINE = QueryEngine(RECORDS)


# ---- count queries --------------------------------------------------------

def test_count_exact_match():
    assert ENGINE.answer({"op": "count", "user_segment": "premium", "status_code": 200}) == 2

def test_count_zero():
    assert ENGINE.answer({"op": "count", "user_segment": "premium", "status_code": 500}) == 0

def test_count_multiple():
    assert ENGINE.answer({"op": "count", "user_segment": "enterprise", "status_code": 500}) == 3

def test_count_single():
    # Records 3 (/orders GET 200 enterprise) and 11 (/billing GET 200 enterprise) → 2
    assert ENGINE.answer({"op": "count", "user_segment": "enterprise", "status_code": 200}) == 2


# ---- exists queries -------------------------------------------------------

def test_exists_hit():
    assert ENGINE.answer({
        "op": "exists", "endpoint": "/api/v1/orders", "method": "DELETE",
        "status_code": 404, "user_segment": "trial",
    }) == 1

def test_exists_miss_wrong_method():
    assert ENGINE.answer({
        "op": "exists", "endpoint": "/api/v1/users", "method": "DELETE",
        "status_code": 200, "user_segment": "premium",
    }) == 0

def test_exists_miss_wrong_status():
    assert ENGINE.answer({
        "op": "exists", "endpoint": "/api/v1/orders", "method": "DELETE",
        "status_code": 200, "user_segment": "trial",
    }) == 0

def test_exists_returns_1_not_count():
    # Two matching records exist but exists must return 1, not 2.
    assert ENGINE.answer({
        "op": "exists", "endpoint": "/api/v1/billing", "method": "POST",
        "status_code": 500, "user_segment": "enterprise",
    }) == 1


# ---- range_count queries --------------------------------------------------

def test_range_count_latency_all():
    # min=0, max=99999 should cover all 12 records.
    assert ENGINE.answer({"op": "range_count", "field": "latency_ms", "min": 0, "max": 99999}) == 12

def test_range_count_latency_inclusive_bounds():
    # Exactly the records with latency_ms in [50, 80] → 2 records.
    assert ENGINE.answer({"op": "range_count", "field": "latency_ms", "min": 50, "max": 80}) == 2

def test_range_count_latency_none():
    assert ENGINE.answer({"op": "range_count", "field": "latency_ms", "min": 10000, "max": 99999}) == 0

def test_range_count_request_bytes():
    # 256-byte records: 2 (the two DELETE/orders rows)
    assert ENGINE.answer({"op": "range_count", "field": "request_bytes", "min": 256, "max": 256}) == 2

def test_range_count_request_bytes_range():
    # 512–1024: latency 50 (512), latency 80 (1024), latency 90 (512) → 3 records
    assert ENGINE.answer({"op": "range_count", "field": "request_bytes", "min": 512, "max": 1024}) == 3


# ---- digest ---------------------------------------------------------------

def test_digest_empty():
    d = compute_digest([])
    assert d == hashlib.sha256(b"").hexdigest()

def test_digest_known():
    # "1,2,3" → known SHA-256
    expected = hashlib.sha256(b"1,2,3").hexdigest()
    assert compute_digest([1, 2, 3]) == expected

def test_digest_zeros():
    assert compute_digest([0]) == hashlib.sha256(b"0").hexdigest()
