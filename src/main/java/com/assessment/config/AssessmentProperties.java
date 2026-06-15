package com.assessment.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalised config. Never hard-code the key/url. Supply via env vars:
 *   ASSESSMENT_BASEURL=...   ASSESSMENT_APIKEY=sa_...
 * or via --assessment.base-url / --assessment.api-key on the command line.
 *
 * application.properties references env vars so the secret never lands in git.
 */
@Validated
@ConfigurationProperties(prefix = "assessment")
public class AssessmentProperties {

    @NotBlank
    private String baseUrl;

    @NotBlank
    private String apiKey;

    /** Where decrypted/raw artifacts are written. */
    private String outputDir = "./out";

    public String getBaseUrl() {
        System.out.println(baseUrl);return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }
}
