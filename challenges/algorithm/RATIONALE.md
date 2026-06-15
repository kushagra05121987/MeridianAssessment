# Algorithm Challenge — Rationale & Benchmarks

## Approach

Naive O(N × K): scan all 50,000 records for each of 10,000 queries → 500M operations. At
~100M simple Java ops/sec that's ~5 seconds per query type, totalling tens of seconds and
completely blowing the target.

The right shape is O(N + K): one preprocessing pass over the N=50,000 records to build
indices, then O(1) or O(log N) per query.

## Index choices

### `count` — HashMap

Key: `(user_segment, status_code)` concatenated with a null-byte separator (avoids boxing
of a two-field tuple without a helper class). Value: integer count.

Build: one `merge(key, 1, Integer::sum)` per record → O(N).
Query: one `getOrDefault` → O(1).

### `exists` — HashSet

Key: `(endpoint, method, status_code, user_segment)` similarly concatenated.

Build: one `add` per record → O(N).
Query: one `contains` → O(1) amortised.

### `range_count` — sorted int[] + binary search

Two sorted `int[]` arrays (latency_ms and request_bytes) built from primitive arrays to
avoid boxing overhead, then `Arrays.sort`.

Query: `upperBound(hi) - lowerBound(lo)` → O(log N).

Could also use prefix-count arrays (O(1) query after O(max_val) build) but the value
ranges here (0–65535) don't justify the 64 KB arrays vs. the log N binary search on
50,000 elements (~16 comparisons).

## Benchmark results (measured on Apple M-series, single run)

| Phase          | Time   |
|----------------|--------|
| Bulk download  | ~27 s  |
| Preprocessing  | ~41 ms |
| 10,000 queries | ~7 ms  |
| Per-query avg  | ~0.7 µs|

Preprocessing and query answering are well within the suggested targets:
- Preprocessing: 41 ms (target ≤ 200 ms) ✓
- Per-query: 0.7 µs (target ≤ 10 µs) ✓
- Full batch: 7 ms (target ≤ 500 ms) ✓

The dominant cost is network I/O for the 50,000-record bulk download (~27 s), which is
outside our control. All compute is sub-50 ms.

## Tradeoffs not taken

- **Prefix-count arrays**: O(1) range queries but O(max_value) build and memory; overkill
  for 50k records with log N already giving <1 µs.
- **Parallel preprocessing**: the single-pass is already ≤50 ms; parallelism would add
  coordination overhead with no meaningful gain.
- **Persistence/caching of the jumbo dataset**: the bulk token is 60 seconds so within a
  single run we always re-fetch. Caching to disk would help iterative development but adds
  complexity not needed for a single submission.
