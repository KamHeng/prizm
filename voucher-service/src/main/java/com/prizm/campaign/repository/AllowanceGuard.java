package com.prizm.campaign.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Serialises concurrent redemptions by the same user on the same campaign.
 *
 * Stock and the voucher claim are guarded by conditional UPDATEs, which need no
 * lock. The per-user cap cannot be expressed that way — it is a count compared
 * against a limit, and two requests from one user could both read a count below
 * the cap and both proceed. Taking a write lock on this pair's row closes that
 * window while leaving every other user on the campaign free to proceed in
 * parallel.
 */
@Repository
public class AllowanceGuard {

    private final JdbcTemplate jdbcTemplate;

    public AllowanceGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Creates the pair's row if absent, then locks it until the surrounding
     * transaction commits.
     *
     * NOTE: {@code MERGE ... KEY} is H2 syntax. On PostgreSQL this becomes
     * {@code INSERT ... ON CONFLICT DO NOTHING}; on MySQL,
     * {@code INSERT IGNORE}. It is the one statement in the service that is not
     * portable as written.
     */
    public void lockFor(Long campaignId, String userId) {
        jdbcTemplate.update(
                "MERGE INTO user_campaign_allowance (campaign_id, user_id) "
                        + "KEY(campaign_id, user_id) VALUES (?, ?)",
                campaignId, userId);

        jdbcTemplate.queryForObject(
                "SELECT campaign_id FROM user_campaign_allowance "
                        + "WHERE campaign_id = ? AND user_id = ? FOR UPDATE",
                Long.class, campaignId, userId);
    }
}
