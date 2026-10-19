package com.prizm.campaign.service;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Audit service configuration.
 *
 * application.yml resolves {@code AUDIT_API_KEY} to an empty string when it is
 * unset, so {@code @NotBlank} refuses startup with a message naming the
 * property. Note it must NOT be declared as a bare {@code ${AUDIT_API_KEY}}:
 * Spring's binder leaves an unresolvable placeholder as literal text, which is
 * non-blank and would sail past this validation.
 */
@Validated
@ConfigurationProperties(prefix = "audit")
public class AuditProperties {

    @NotBlank
    private String url;

    @NotBlank
    private String apiKey;

    private Duration connectTimeout = Duration.ofSeconds(2);

    private Duration readTimeout = Duration.ofSeconds(3);

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
}
