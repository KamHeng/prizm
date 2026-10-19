package com.prizm.campaign;

import com.prizm.campaign.service.AuditClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the HTTP layer, which the service-level tests never touch: who is
 * allowed to call these endpoints, and whose identity a redemption is recorded
 * against.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = {"/test-reset.sql", "/dev-seed.sql"})
class VoucherApiSecurityTest {

    private static final String ADMIN_SCOPE = "SCOPE_voucher.admin";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private AuditClient auditClient;

    @Test
    void redeemWithoutTokenIsRejected() throws Exception {
        mvc.perform(post("/vouchers/LIMIT-0001/redeem"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void redeemWithUnverifiableTokenIsRejected() throws Exception {
        mvc.perform(post("/vouchers/LIMIT-0001/redeem")
                        .header("Authorization", "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void statsRequireAuthentication() throws Exception {
        mvc.perform(get("/campaigns/1/stats"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void redeemSucceedsWithAValidToken() throws Exception {
        mvc.perform(post("/vouchers/LIMIT-0001/redeem")
                        .with(jwt().jwt(j -> j.subject("token-user"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("OK"))
                .andExpect(jsonPath("$.voucherCode").value("LIMIT-0001"));
    }

    @Test
    void spoofedUserIdParameterCannotRaiseTheLimit() throws Exception {
        // Every call carries a DIFFERENT userId parameter but the SAME token.
        // If the parameter were still trusted, each would look like a new user
        // and all three would succeed.
        redeemAs("LIMIT-0001", "token-user", "victim-a").andExpect(jsonPath("$.result").value("OK"));
        redeemAs("LIMIT-0002", "token-user", "victim-b").andExpect(jsonPath("$.result").value("OK"));

        redeemAs("LIMIT-0003", "token-user", "victim-c")
                .andExpect(jsonPath("$.result").value("FAILED"))
                .andExpect(jsonPath("$.reason").value("USER_LIMIT_REACHED"));
    }

    @Test
    void separateTokenSubjectsGetSeparateAllowances() throws Exception {
        redeemAs("LIMIT-0001", "user-a", null).andExpect(jsonPath("$.result").value("OK"));
        redeemAs("LIMIT-0002", "user-a", null).andExpect(jsonPath("$.result").value("OK"));
        redeemAs("LIMIT-0003", "user-a", null).andExpect(jsonPath("$.reason").value("USER_LIMIT_REACHED"));

        redeemAs("LIMIT-0003", "user-b", null).andExpect(jsonPath("$.result").value("OK"));
    }

    @Test
    void unknownCampaignIdReturnsNotFound() throws Exception {
        mvc.perform(get("/campaigns/99999/stats")
                        .with(jwt().jwt(j -> j.subject("reader"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CAMPAIGN_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("No campaign with id 99999"));
    }

    @Test
    void knownCampaignIdStillReturnsStats() throws Exception {
        mvc.perform(get("/campaigns/1/stats")
                        .with(jwt().jwt(j -> j.subject("reader"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campaignId").value(1));
    }

    @Test
    void voidRequiresTheAdminScope() throws Exception {
        mvc.perform(post("/vouchers/LIMIT-0001/void")
                        .with(jwt().jwt(j -> j.subject("ordinary-user"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void voidSucceedsWithTheAdminScope() throws Exception {
        mvc.perform(post("/vouchers/LIMIT-0001/void")
                        .with(jwt().jwt(j -> j.subject("operator"))
                                .authorities(new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority(ADMIN_SCOPE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("OK"));
    }

    private org.springframework.test.web.servlet.ResultActions redeemAs(
            String code, String tokenSubject, String spoofedUserId) throws Exception {
        var request = post("/vouchers/" + code + "/redeem");
        if (spoofedUserId != null) {
            request = request.param("userId", spoofedUserId);
        }
        return mvc.perform(request.with(jwt().jwt(j -> j.subject(tokenSubject))))
                .andExpect(status().isOk());
    }
}
