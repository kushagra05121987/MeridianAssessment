package com.assessment.client;

import com.assessment.config.AssessmentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin wrapper over the assessment HTTP API.
 *
 * Design notes:
 *  - health() uses a SEPARATE unauthenticated client path so it can never start
 *    the clock. (We strip the Authorization header for it.)
 *  - Every authed call logs RateLimit-* headers so we can watch our budget.
 *  - 429 is retried automatically honouring Retry-After.
 *  - We deliberately do NOT auto-call anything on startup.
 */
@Component
public class AssessmentClient {

    private static final Logger log = LoggerFactory.getLogger(AssessmentClient.class);

    private final WebClient web;
    private final WebClient.Builder rawBuilder;
    private final AssessmentProperties props;

    public AssessmentClient(WebClient assessmentWebClient,
                            WebClient.Builder builder,
                            AssessmentProperties props) {
        this.web = assessmentWebClient;
        this.rawBuilder = builder;
        this.props = props;
    }

    // ----- Unauthenticated -----

    /** GET /api/v1/health — does NOT start the clock, no auth header. */
    public String health() {
        WebClient noAuth = rawBuilder.baseUrl(props.getBaseUrl()).build();
        return noAuth.get()
                .uri("/api/v1/health")
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    // ----- Authenticated (these START / consume the clock) -----

    /**
     * Generic authed GET returning the body as String, logging rate-limit headers.
     * Use this first to probe endpoint shapes once you accept the clock starting.
     */
    public String getAuthed(String path) {
        return web.get()
                .uri(path)
                .exchangeToMono(resp -> {
                    logRateLimit(path, resp.headers().asHttpHeaders());
                    return resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(b -> resp.statusCode().value() + " " + b);
                })
                .retryWhen(rateLimitRetry())
                .block();
    }

    /** Raw bytes (for Layer 1 byte-level integrity / hashing). */
    public byte[] getAuthedBytes(String path) {
        return web.get()
                .uri(path)
                .retrieve()
                .toEntity(byte[].class)
                .doOnNext(resp -> logRateLimit(path, resp.getHeaders()))
                .map(org.springframework.http.ResponseEntity::getBody)
                .retryWhen(rateLimitRetry())
                .block();
    }

    /** Generic authed POST (used by the submission endpoint). Captures 4xx body instead of throwing. */
    public String postAuthed(String path, Object body) {
        return web.post()
                .uri(path)
                .bodyValue(body)
                .exchangeToMono(resp -> {
                    logRateLimit(path, resp.headers().asHttpHeaders());
                    return resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(b -> resp.statusCode().value() + " " + b);
                })
                .retryWhen(rateLimitRetry())
                .block();
    }

    /**
     * Mint a single-use 60-second bulk-download token for the jumbo dataset,
     * then immediately redeem it (token is the credential — no auth header on bulk GET).
     * Returns the raw JSON string of the 50,000-record envelope.
     */
    public String getJumboBulk() {
        // Mint token (costs 1 rate-limit token)
        String mintResp = postAuthed("/api/v1/dataset/jumbo/bulk-request", java.util.Map.of());
        // Extract token value
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                            mintResp.substring(mintResp.indexOf(' ') + 1));
            String token = node.path("token").asText();
            log.info("Bulk token minted: {}", token);
            // Redeem — no Authorization header, token IS the credential
            String bulkUrl = props.getBaseUrl() + "/api/v1/dataset/jumbo/bulk/" + token;
            org.springframework.web.reactive.function.client.WebClient noAuth =
                    rawBuilder.baseUrl(props.getBaseUrl()).build();
            return noAuth.get()
                    .uri("/api/v1/dataset/jumbo/bulk/" + token)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            throw new RuntimeException("Failed to mint/redeem jumbo bulk token", e);
        }
    }

    /** Generic authed GET returning String, capturing 4xx body too. */
    public String getAuthedRaw(String path) {
        return web.get()
                .uri(path)
                .exchangeToMono(resp -> {
                    logRateLimit(path, resp.headers().asHttpHeaders());
                    return resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(b -> resp.statusCode().value() + " " + b);
                })
                .retryWhen(rateLimitRetry())
                .block();
    }

    /**
     * Fetch every page of /api/v1/dataset and return the raw base64-encoded
     * ciphertext strings in page order.
     *
     * Strategy: read total from page 1, compute total_pages once (snapshot),
     * then fetch exactly that many pages without sleeping. This mirrors the
     * approach that produces the correct Layer 1/2 hashes: the snapshot is
     * fixed at the point page 1 is fetched, and any usage records generated
     * by subsequent page fetches land beyond our page window.
     *
     * On 429: backs off and retries.
     */
    public List<String> getAllDatasetPages() {
        ObjectMapper mapper = new ObjectMapper();
        List<String> all = new ArrayList<>();
        try {
            // Page 1 — determine snapshot size.
            String raw1 = fetchPageWithRetry("/api/v1/dataset?page=1", mapper);
            JsonNode root1 = mapper.readTree(raw1);
            addData(root1, all);

            int total = root1.path("total").asInt(0);
            int pageSize = root1.path("page_size").asInt(25);
            int totalPages = (int) Math.ceil((double) total / pageSize);
            log.info("Dataset snapshot: total={}, page_size={}, pages={}", total, pageSize, totalPages);

            for (int pg = 2; pg <= totalPages; pg++) {
                String raw = fetchPageWithRetry("/api/v1/dataset?page=" + pg, mapper);
                JsonNode root = mapper.readTree(raw);
                addData(root, all);
                if (pg % 5 == 0) log.info("  fetched page {}/{}, records so far: {}", pg, totalPages, all.size());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed fetching dataset pages", e);
        }
        return all;
    }

    /** GET a dataset page, detecting 429 in the body and backing off. */
    private String fetchPageWithRetry(String path, ObjectMapper mapper) throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            String raw = getAuthed(path);
            if (raw.startsWith("429")) {
                int wait = 5;
                try {
                    JsonNode node = mapper.readTree(raw.substring(raw.indexOf(' ') + 1));
                    if (node.has("retry_after")) wait = node.path("retry_after").asInt(5);
                } catch (Exception ignored) {}
                log.warn("429 on {}, waiting {}s (attempt {})", path, wait, attempt + 1);
                Thread.sleep(wait * 1000L);
                continue;
            }
            return raw.substring(raw.indexOf(' ') + 1);
        }
        throw new RuntimeException("Exceeded retries for " + path);
    }

    private void addData(JsonNode root, List<String> out) {
        JsonNode data = root.path("data");
        if (data.isArray()) {
            data.forEach(n -> out.add(n.asText()));
        }
    }

    private void logRateLimit(String path, org.springframework.http.HttpHeaders h) {
        log.info("[{}] RateLimit limit={} remaining={} reset={}",
                path,
                h.getFirst("RateLimit-Limit"),
                h.getFirst("RateLimit-Remaining"),
                h.getFirst("RateLimit-Reset"));
    }

    /** Retry on 429 honouring Retry-After, with a sane cap. */
    private Retry rateLimitRetry() {
        return Retry.from(signals -> signals.flatMap(rs -> {
            Throwable err = rs.failure();
            if (err instanceof WebClientResponseException w
                    && w.getStatusCode().value() == 429) {
                long wait = parseRetryAfter(w);
                log.warn("429 received, backing off {}s", wait);
                return Mono.delay(Duration.ofSeconds(wait));
            }
            return Mono.error(err);
        }));
    }

    private long parseRetryAfter(WebClientResponseException w) {
        String ra = w.getHeaders().getFirst("Retry-After");
        try { return ra != null ? Long.parseLong(ra.trim()) : 2; }
        catch (NumberFormatException e) { return 2; }
    }
}
