package com.prizm.campaign.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * The per-user redemption limit is only meaningful if the caller cannot choose
 * who they are. Redemption therefore takes the user id from a verified JWT
 * subject rather than a request parameter.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Scope required to void a voucher — an operator action, not an end-user one. */
    static final String ADMIN_SCOPE = "SCOPE_voucher.admin";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Stateless bearer-token API: there is no session or browser
                // form to protect, so CSRF defence does not apply.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/vouchers/*/void").hasAuthority(ADMIN_SCOPE)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }

    /**
     * Symmetric HS256 verification, so the service boots and can be exercised
     * without an identity provider to hand.
     *
     * This is the one piece that should change before production: a symmetric
     * secret means any holder can mint tokens as well as verify them. With a
     * real IdP, delete this bean and set
     * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} instead —
     * the rest of the configuration is unaffected.
     */
    @Bean
    JwtDecoder jwtDecoder(@Value("${app.security.jwt.secret:}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET is not set. The service will not start without it, so that it "
                            + "cannot accept unverifiable tokens. Generate one with: openssl rand -hex 32");
        }
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET must be at least 32 bytes for HS256, got " + key.length);
        }
        return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256")).build();
    }
}
