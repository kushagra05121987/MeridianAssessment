package com.assessment.algorithm;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for QueryEngine — no network, hand-crafted 12-record dataset.
 */
class QueryEngineTest {

    private static QueryEngine engine;

    /** 12 records covering all three query types. */
    private static final List<Map<String, Object>> RECORDS = List.of(
        rec("/api/v1/users",   "GET",    200, "premium",    50,   512),
        rec("/api/v1/users",   "GET",    200, "premium",    80,   1024),
        rec("/api/v1/users",   "POST",   201, "premium",    120,  2048),
        rec("/api/v1/orders",  "GET",    200, "enterprise", 200,  4096),
        rec("/api/v1/orders",  "DELETE", 404, "trial",      300,  256),
        rec("/api/v1/orders",  "DELETE", 404, "trial",      350,  256),
        rec("/api/v1/search",  "GET",    200, "free-tier",  1000, 128),
        rec("/api/v1/search",  "GET",    429, "free-tier",  5,    64),
        rec("/api/v1/billing", "POST",   500, "enterprise", 2000, 8192),
        rec("/api/v1/billing", "POST",   500, "enterprise", 2500, 8192),
        rec("/api/v1/billing", "POST",   500, "enterprise", 3000, 16384),
        rec("/api/v1/billing", "GET",    200, "enterprise", 90,   512)
    );

    @BeforeAll
    static void build() {
        engine = new QueryEngine(RECORDS);
    }

    // ---- count queries -------------------------------------------------------

    @Test void countExactMatch() {
        assertEquals(2, engine.answer(Map.of("op", "count", "user_segment", "premium", "status_code", 200)));
    }

    @Test void countZero() {
        assertEquals(0, engine.answer(Map.of("op", "count", "user_segment", "premium", "status_code", 500)));
    }

    @Test void countMultiple() {
        assertEquals(3, engine.answer(Map.of("op", "count", "user_segment", "enterprise", "status_code", 500)));
    }

    @Test void countSingle() {
        // records 3 (/orders GET 200 enterprise) + 11 (/billing GET 200 enterprise) = 2
        assertEquals(2, engine.answer(Map.of("op", "count", "user_segment", "enterprise", "status_code", 200)));
    }

    // ---- exists queries ------------------------------------------------------

    @Test void existsHit() {
        assertEquals(1, engine.answer(Map.of(
            "op", "exists", "endpoint", "/api/v1/orders",
            "method", "DELETE", "status_code", 404, "user_segment", "trial")));
    }

    @Test void existsMissWrongMethod() {
        assertEquals(0, engine.answer(Map.of(
            "op", "exists", "endpoint", "/api/v1/users",
            "method", "DELETE", "status_code", 200, "user_segment", "premium")));
    }

    @Test void existsMissWrongStatus() {
        assertEquals(0, engine.answer(Map.of(
            "op", "exists", "endpoint", "/api/v1/orders",
            "method", "DELETE", "status_code", 200, "user_segment", "trial")));
    }

    @Test void existsReturns1NotCount() {
        // Three billing/POST/500/enterprise records exist — must return 1, not 3.
        assertEquals(1, engine.answer(Map.of(
            "op", "exists", "endpoint", "/api/v1/billing",
            "method", "POST", "status_code", 500, "user_segment", "enterprise")));
    }

    // ---- range_count queries -------------------------------------------------

    @Test void rangeLatencyAll() {
        assertEquals(12, engine.answer(Map.of("op", "range_count", "field", "latency_ms", "min", 0, "max", 99999)));
    }

    @Test void rangeLatencyInclusiveBounds() {
        // latency 50 and 80 only → 2
        assertEquals(2, engine.answer(Map.of("op", "range_count", "field", "latency_ms", "min", 50, "max", 80)));
    }

    @Test void rangeLatencyNone() {
        assertEquals(0, engine.answer(Map.of("op", "range_count", "field", "latency_ms", "min", 10000, "max", 99999)));
    }

    @Test void rangeRequestBytesExact() {
        // Only the two DELETE/orders records have 256 bytes
        assertEquals(2, engine.answer(Map.of("op", "range_count", "field", "request_bytes", "min", 256, "max", 256)));
    }

    @Test void rangeRequestBytesRange() {
        // 512–1024: rec 0 (512), rec 1 (1024), rec 11 (512) → 3
        assertEquals(3, engine.answer(Map.of("op", "range_count", "field", "request_bytes", "min", 512, "max", 1024)));
    }

    // ---- helpers -------------------------------------------------------------

    private static Map<String, Object> rec(String ep, String method, int sc,
                                            String seg, int lat, int req) {
        return Map.of("endpoint", ep, "method", method, "status_code", sc,
                      "user_segment", seg, "latency_ms", lat, "request_bytes", req,
                      "timestamp", "2026-01-01T00:00:00Z");
    }
}
