package com.prizm.campaign.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class RedemptionAuditListener {

    private static final Logger log = LoggerFactory.getLogger(RedemptionAuditListener.class);

    private final AuditClient auditClient;

    public RedemptionAuditListener(AuditClient auditClient) {
        this.auditClient = auditClient;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRedemptionRecorded(RedemptionRecorded event) {
        // The redemption is already committed by this point, and an exception
        // thrown here would still surface to the caller, so audit failures are
        // swallowed rather than allowed to fail a successful redemption.
        try {
            auditClient.recordRedemption(event.clientCode(), event.voucherCode(), event.userId());
        } catch (Exception e) {
            log.error("audit call failed for voucher {}", event.voucherCode(), e);
        }
    }
}
