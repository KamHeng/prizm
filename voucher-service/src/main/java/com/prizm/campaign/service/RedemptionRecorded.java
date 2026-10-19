package com.prizm.campaign.service;

/**
 * Published inside the redemption transaction and consumed once it commits, so
 * the audit call runs after the voucher and campaign row locks are released.
 */
public record RedemptionRecorded(String clientCode, String voucherCode, String userId) {
}
