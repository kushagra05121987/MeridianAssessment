package com.assessment.client;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Submission payload matching the spec:
 *   { "type": "<layer-type>", "value": "<answer>", "notes": "<optional, <=8 KiB>" }
 *
 * Valid `type` values come back in the 400 error envelope on a bad request,
 * so send a deliberately-bad one first to enumerate them (see Runner).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Submission(String type, String value, String notes) {

    public static Submission of(String type, String value) {
        return new Submission(type, value, null);
    }

    public static Submission of(String type, String value, String notes) {
        // Spec caps notes at 8 KiB; guard locally to fail fast.
        if (notes != null && notes.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8 * 1024) {
            throw new IllegalArgumentException("notes exceeds 8 KiB");
        }
        return new Submission(type, value, notes);
    }
}
