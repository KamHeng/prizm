package com.prizm.campaign.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Component
public class AuditClient {

    private static final Logger log = LoggerFactory.getLogger(AuditClient.class);

    private final AuditProperties properties;
    private final RestTemplate restTemplate;

    public AuditClient(RestTemplateBuilder restTemplateBuilder, AuditProperties properties) {
        this.properties = properties;
        // Without these a hung audit host holds a Tomcat worker thread
        // indefinitely, and enough of them take redemption down with it.
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(properties.getConnectTimeout())
                .setReadTimeout(properties.getReadTimeout())
                .build();
    }

    public void recordRedemption(String clientCode, String voucherCode, String userId) {
        Map<String, String> payload = new HashMap<>();
        payload.put("clientCode", clientCode);
        payload.put("voucherCode", voucherCode);
        payload.put("userId", userId);

        // The key travels as a bearer credential rather than a body field, so it
        // stays out of request logs and proxy traces.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(properties.getApiKey());

        restTemplate.postForObject(
                properties.getUrl(), new HttpEntity<>(payload, headers), String.class);
        log.info("audit event sent for {}", voucherCode);
    }
}
