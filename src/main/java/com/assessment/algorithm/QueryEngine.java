package com.assessment.algorithm;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * O(N) preprocessing, O(1) or O(log N) per query.
 *
 * Three indices built in a single pass over the records:
 *   count_idx   – (user_segment, status_code) → record count
 *   exists_idx  – set of (endpoint, method, status_code, user_segment) tuples
 *   lat_sorted  – sorted int[] of latency_ms values for binary-search range queries
 *   req_sorted  – sorted int[] of request_bytes values
 */
public final class QueryEngine {

    private final Map<String, Integer> countIdx = new HashMap<>();
    private final Set<String> existsIdx = new HashSet<>();
    private final int[] latSorted;
    private final int[] reqSorted;

    public QueryEngine(List<Map<String, Object>> records) {
        int n = records.size();
        int[] lat = new int[n];
        int[] req = new int[n];
        int i = 0;
        for (Map<String, Object> r : records) {
            String seg = (String) r.get("user_segment");
            int sc = toInt(r.get("status_code"));
            countIdx.merge(seg + "\0" + sc, 1, Integer::sum);
            existsIdx.add(r.get("endpoint") + "\0" + r.get("method") + "\0" + sc + "\0" + seg);
            lat[i] = toInt(r.get("latency_ms"));
            req[i] = toInt(r.get("request_bytes"));
            i++;
        }
        Arrays.sort(lat);
        Arrays.sort(req);
        this.latSorted = lat;
        this.reqSorted = req;
    }

    public int answer(Map<String, Object> q) {
        String op = (String) q.get("op");
        return switch (op) {
            case "count" -> {
                String key = q.get("user_segment") + "\0" + toInt(q.get("status_code"));
                yield countIdx.getOrDefault(key, 0);
            }
            case "exists" -> {
                String key = q.get("endpoint") + "\0" + q.get("method") + "\0"
                        + toInt(q.get("status_code")) + "\0" + q.get("user_segment");
                yield existsIdx.contains(key) ? 1 : 0;
            }
            case "range_count" -> {
                int[] arr = "latency_ms".equals(q.get("field")) ? latSorted : reqSorted;
                int lo = toInt(q.get("min"));
                int hi = toInt(q.get("max"));
                yield upperBound(arr, hi) - lowerBound(arr, lo);
            }
            default -> throw new IllegalArgumentException("unknown op: " + op);
        };
    }

    private static int lowerBound(int[] arr, int target) {
        int lo = 0, hi = arr.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (arr[mid] < target) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    private static int upperBound(int[] arr, int target) {
        int lo = 0, hi = arr.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (arr[mid] <= target) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    private static int toInt(Object o) {
        if (o instanceof Integer i) return i;
        if (o instanceof Long l) return l.intValue();
        if (o instanceof Double d) return d.intValue();
        return Integer.parseInt(o.toString());
    }
}
