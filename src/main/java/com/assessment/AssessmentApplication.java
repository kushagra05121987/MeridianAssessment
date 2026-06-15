package com.assessment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.assessment.config.AssessmentProperties;

/**
 * SE Assessment runner.
 *
 * This is intentionally NOT a web server. We use Spring Boot purely for
 * dependency injection, config binding and lifecycle. Actual work is driven
 * by CommandLineRunner-style components that we invoke explicitly so the
 * 3-hour clock only starts when WE decide to make an authenticated call.
 */
@SpringBootApplication
@EnableConfigurationProperties(AssessmentProperties.class)
public class AssessmentApplication {
    public static void main(String[] args) {
        // Don't auto-run anything that hits the authed API. Bring the context
        // up, then invoke layers deliberately (see Runner / CLI args).
        SpringApplication.run(AssessmentApplication.class, args);
    }
}
