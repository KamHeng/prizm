package com.prizm.campaign.dto;

/**
 * Machine-readable cause of a failed redemption, so callers can branch without
 * matching on the human-readable message.
 */
public enum FailureReason {

    VOUCHER_NOT_FOUND,
    VOUCHER_ALREADY_REDEEMED,
    VOUCHER_VOID,
    CAMPAIGN_INACTIVE,
    CAMPAIGN_OUT_OF_STOCK,
    USER_LIMIT_REACHED
}
