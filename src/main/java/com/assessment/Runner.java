package com.assessment;

import com.assessment.algorithm.QueryEngine;
import com.assessment.client.AssessmentClient;
import com.assessment.client.Submission;
import com.assessment.util.Crypto;
import com.assessment.util.Hashing;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Command dispatcher. Nothing here touches an authed endpoint unless you pass
 * the matching command, so importing the context is safe.
 *
 * Usage (mvn spring-boot:run -Dspring-boot.run.arguments=...):
 *   health                              unauthenticated probe (safe, no clock)
 *   probe <path>                        authed GET (STARTS THE CLOCK)
 *   enumerate-types                     POST a bad submission to reveal valid types
 *   submit <type> <value> [notes]       submit an answer
 *   solve-layers                        fetch all records, compute + submit L1 & L2
 *   solve-layer1                        fetch all records, compute + submit content_hash only
 *   solve-layer2 <private-key-file>     decrypt all records, compute + submit decrypted_hash
 *   solve-layers-full <private-key-file> fetch, hash encrypted, decrypt, hash decrypted, submit both
 *   layer4 <value>                      submit free-form analysis (Layer 4)
 *
 * The first authed command starts the 3-hour clock. Be ready.
 */
@Component
public class Runner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Runner.class);

    private final AssessmentClient client;

    public Runner(AssessmentClient client) {
        this.client = client;
    }

    @Override
    public void run(ApplicationArguments args) {
        var positionals = args.getNonOptionArgs();
        if (positionals.isEmpty()) {
            printHelp();
            return;
        }

        String cmd = positionals.get(0);
        switch (cmd) {
            case "health" -> log.info("Health (no clock): {}", client.health());

            case "probe" -> {
                require(positionals.size() >= 2, "probe needs a <path>");
                log.warn(">>> AUTHED CALL — this may START the 3h clock <<<");
                log.info("GET {} -> {}", positionals.get(1), client.getAuthed(positionals.get(1)));
            }

            case "hash" -> {
                require(positionals.size() >= 2, "hash needs a <path>");
                byte[] data = client.getAuthedBytes(positionals.get(1));
                log.info("bytes={} sha256={} md5={}",
                        data.length, Hashing.sha256Hex(data), Hashing.md5Hex(data));
            }

            case "enumerate-types" -> {
                log.warn(">>> AUTHED CALL — sends a deliberately-bad submission <<<");
                log.info("Response: {}", client.postAuthed("/api/v1/submit",
                        Submission.of("__invalid__", "x")));
            }

            case "submit" -> {
                require(positionals.size() >= 3, "submit needs <type> <value> [notes]");
                String notes = positionals.size() >= 4 ? positionals.get(3) : null;
                log.info("Response: {}", client.postAuthed("/api/v1/submit",
                        Submission.of(positionals.get(1), positionals.get(2), notes)));
            }

            // ── Layer 1: content_hash ─────────────────────────────────────────
            case "solve-layer1" -> {
                log.info("Fetching all dataset pages...");
                List<String> pages = client.getAllDatasetPages();
                log.info("Fetched {} encrypted records", pages.size());

                byte[] encConcat = concatBase64Decoded(pages);
                String contentHash = Hashing.sha256Hex(encConcat);
                log.info("content_hash = {}", contentHash);

                String resp = client.postAuthed("/api/v1/submit",
                        Submission.of("content_hash", contentHash,
                                "SHA-256 of all " + pages.size() + " encrypted records concatenated"));
                log.info("Submit response: {}", resp);
            }

            // ── Layer 2: decrypted_hash ───────────────────────────────────────
            case "solve-layer2" -> {
                require(positionals.size() >= 2, "solve-layer2 needs <path-to-private-key.pem>");
                PrivateKey privKey = loadKey(positionals.get(1));

                log.info("Fetching all dataset pages...");
                List<String> pages = client.getAllDatasetPages();
                log.info("Fetched {} encrypted records", pages.size());

                byte[] decConcat = decryptAll(pages, privKey);
                String decHash = Hashing.sha256Hex(decConcat);
                log.info("decrypted_hash = {}", decHash);

                String resp = client.postAuthed("/api/v1/submit",
                        Submission.of("decrypted_hash", decHash,
                                "SHA-256 of all " + pages.size() + " RSA-PKCS1v15 decrypted records concatenated"));
                log.info("Submit response: {}", resp);
            }

            // ── Layers 1 + 2 together (fast snapshot) ────────────────────────
            case "solve-layers-full" -> {
                require(positionals.size() >= 2, "solve-layers-full needs <path-to-private-key.pem>");
                PrivateKey privKey = loadKey(positionals.get(1));

                log.info("Fetching all dataset pages in burst...");
                List<String> pages = client.getAllDatasetPages();
                log.info("Fetched {} encrypted records", pages.size());

                byte[] encConcat = concatBase64Decoded(pages);
                String contentHash = Hashing.sha256Hex(encConcat);
                log.info("content_hash    = {}", contentHash);

                byte[] decConcat = decryptAll(pages, privKey);
                String decryptedHash = Hashing.sha256Hex(decConcat);
                log.info("decrypted_hash  = {}", decryptedHash);

                log.info("Submitting content_hash...");
                String r1 = client.postAuthed("/api/v1/submit",
                        Submission.of("content_hash", contentHash,
                                "SHA-256 of " + pages.size() + " encrypted records"));
                log.info("Layer 1 response: {}", r1);

                log.info("Submitting decrypted_hash...");
                String r2 = client.postAuthed("/api/v1/submit",
                        Submission.of("decrypted_hash", decryptedHash,
                                "SHA-256 of " + pages.size() + " RSA-PKCS1v15 decrypted records"));
                log.info("Layer 2 response: {}", r2);
            }

            // ── Algorithm challenge ───────────────────────────────────────────
            case "solve-algorithm" -> {
                ObjectMapper mapper = new ObjectMapper();
                try {
                log.info("Downloading 50,000-record jumbo dataset via bulk endpoint...");
                long t0 = System.currentTimeMillis();
                String bulkJson = client.getJumboBulk();
                JsonNode bulkRoot = mapper.readTree(bulkJson);
                List<Map<String, Object>> records = mapper.convertValue(
                        bulkRoot.path("records"),
                        new TypeReference<List<Map<String, Object>>>() {});
                log.info("Downloaded {} records in {}ms", records.size(), System.currentTimeMillis() - t0);

                log.info("Fetching 10,000-query batch...");
                String queryResp = client.getAuthed("/api/v1/challenges/algorithm/queries");
                JsonNode queryRoot = mapper.readTree(queryResp.substring(queryResp.indexOf(' ') + 1));
                List<Map<String, Object>> queries = mapper.convertValue(
                        queryRoot.path("queries"),
                        new TypeReference<List<Map<String, Object>>>() {});
                log.info("Fetched {} queries", queries.size());

                log.info("Building indices...");
                long t1 = System.currentTimeMillis();
                QueryEngine engine = new QueryEngine(records);
                log.info("Preprocessing done in {}ms", System.currentTimeMillis() - t1);

                log.info("Answering queries...");
                long t2 = System.currentTimeMillis();
                List<Integer> answers = new ArrayList<>(queries.size());
                for (Map<String, Object> q : queries) answers.add(engine.answer(q));
                long queryMs = System.currentTimeMillis() - t2;
                log.info("Answered {} queries in {}ms ({} µs avg)",
                        queries.size(), queryMs, queryMs * 1000 / queries.size());

                String payload = answers.stream().map(Object::toString).collect(Collectors.joining(","));
                String digest = Hashing.sha256Hex(payload.getBytes(StandardCharsets.UTF_8));
                log.info("Digest: {}", digest);

                String notes = String.format(
                        "Preprocessing %dms | %d queries %dms | %d µs avg. " +
                        "HashMap for count, HashSet for exists, sorted int[]+binary-search for range_count.",
                        System.currentTimeMillis() - t1, queries.size(), queryMs, queryMs * 1000 / queries.size());
                String resp = client.postAuthed("/api/v1/submit",
                        Submission.of("algorithm_answer", digest, notes));
                log.info("Submit response: {}", resp);
                } catch (Exception e) {
                    throw new RuntimeException("solve-algorithm failed", e);
                }
            }

            // ── Layer 4: free-form analysis ───────────────────────────────────
            case "layer4" -> {
                require(positionals.size() >= 2, "layer4 needs <analysis-text>");
                String notes = positionals.size() >= 3 ? positionals.get(2) : null;
                String resp = client.postAuthed("/api/v1/submit",
                        Submission.of("analysis", positionals.get(1), notes));
                log.info("Layer 4 response: {}", resp);
            }

            default -> {
                log.warn("Unknown command: {}", cmd);
                printHelp();
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private byte[] concatBase64Decoded(List<String> b64List) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (String b64 : b64List) {
                out.write(Base64.getDecoder().decode(b64));
            }
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode base64 records", e);
        }
    }

    private byte[] decryptAll(List<String> b64List, PrivateKey key) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int i = 0;
            for (String b64 : b64List) {
                byte[] ciphertext = Base64.getDecoder().decode(b64);
                byte[] plaintext = Crypto.rsaPkcs1v15Decrypt(key, ciphertext);
                out.write(plaintext);
                if (++i % 50 == 0) log.info("  decrypted {}/{}", i, b64List.size());
            }
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("RSA decryption failed", e);
        }
    }

    private PrivateKey loadKey(String pemPath) {
        try {
            String pem = Files.readString(Path.of(pemPath));
            return Crypto.loadRsaPrivateKey(pem);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load private key from: " + pemPath, e);
        }
    }

    private void require(boolean ok, String msg) {
        if (!ok) throw new IllegalArgumentException(msg);
    }

    private void printHelp() {
        log.info("""
                Available commands:
                  health
                  probe <path>
                  enumerate-types
                  submit <type> <value> [notes]
                  solve-layer1
                  solve-layer2 <private-key.pem>
                  solve-layers-full <private-key.pem>     ← fetch + submit L1 & L2 together
                  layer4 <analysis-text>
                  solve-algorithm                         ← algorithm challenge (jumbo dataset)
                """);
    }
}
