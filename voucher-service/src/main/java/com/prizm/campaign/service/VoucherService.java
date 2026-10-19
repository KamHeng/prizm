package com.prizm.campaign.service;

import com.prizm.campaign.dto.FailureReason;
import com.prizm.campaign.dto.RedeemResponse;
import com.prizm.campaign.model.Campaign;
import com.prizm.campaign.model.Redemption;
import com.prizm.campaign.model.Voucher;
import com.prizm.campaign.repository.AllowanceGuard;
import com.prizm.campaign.repository.CampaignRepository;
import com.prizm.campaign.repository.RedemptionRepository;
import com.prizm.campaign.repository.VoucherRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.util.Date;

@Service
public class VoucherService {

    private final VoucherRepository voucherRepository;
    private final CampaignRepository campaignRepository;
    private final RedemptionRepository redemptionRepository;
    private final AllowanceGuard allowanceGuard;
    private final ApplicationEventPublisher eventPublisher;

    public VoucherService(VoucherRepository voucherRepository,
                          CampaignRepository campaignRepository,
                          RedemptionRepository redemptionRepository,
                          AllowanceGuard allowanceGuard,
                          ApplicationEventPublisher eventPublisher) {
        this.voucherRepository = voucherRepository;
        this.campaignRepository = campaignRepository;
        this.redemptionRepository = redemptionRepository;
        this.allowanceGuard = allowanceGuard;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Redeems a voucher for a user.
     *
     * Nothing here locks the campaign. Stock and the voucher itself are each
     * claimed by a conditional UPDATE whose WHERE clause is the guard, so two
     * callers can never both win the same unit or the same code — the loser
     * simply updates 0 rows. Only the per-user cap needs serialising, and that
     * is narrowed to the one (campaign, user) pair, so unrelated customers on a
     * busy campaign no longer queue behind each other.
     *
     * Checks are ordered so a user at their cap is told so even when the
     * campaign has also run dry.
     */
    @Transactional
    public RedeemResponse redeem(String code, String userId) {

        Voucher voucher = voucherRepository.findByCode(code);
        if (voucher == null) {
            return RedeemResponse.fail(FailureReason.VOUCHER_NOT_FOUND, "Voucher not found");
        }

        if ("REDEEMED".equals(voucher.getStatus())) {
            return RedeemResponse.fail(FailureReason.VOUCHER_ALREADY_REDEEMED, "Voucher already redeemed");
        }

        if ("VOID".equals(voucher.getStatus())) {
            return RedeemResponse.fail(FailureReason.VOUCHER_VOID, "Voucher is void");
        }

        Long campaignId = voucher.getCampaignId();
        // A voucher pointing at a campaign that does not exist is corrupt data,
        // not a bad request, so this is deliberately not a FailureReason.
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new IllegalStateException(
                        "Voucher " + code + " references campaign " + campaignId + ", which does not exist"));

        if (!campaign.isActive()) {
            return RedeemResponse.fail(FailureReason.CAMPAIGN_INACTIVE, "Campaign is not active");
        }

        allowanceGuard.lockFor(campaignId, userId);

        long alreadyRedeemed = redemptionRepository.countActiveRedemptionsByUser(campaignId, userId);
        if (alreadyRedeemed >= campaign.getMaxPerUser()) {
            return RedeemResponse.fail(FailureReason.USER_LIMIT_REACHED,
                    "Redemption limit reached for this campaign ("
                            + alreadyRedeemed + " of " + campaign.getMaxPerUser() + " used)");
        }

        if (campaignRepository.claimStock(campaignId) == 0) {
            return RedeemResponse.fail(FailureReason.CAMPAIGN_OUT_OF_STOCK, "Campaign out of stock");
        }

        Date now = new Date();
        if (voucherRepository.claimForRedemption(voucher.getId(), userId, now) == 0) {
            // Another request took this exact code between our read and here.
            // Roll back so the stock unit claimed above is given back rather
            // than quietly lost, then report the loss to the caller.
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return RedeemResponse.fail(FailureReason.VOUCHER_ALREADY_REDEEMED, "Voucher already redeemed");
        }

        redemptionRepository.save(new Redemption(voucher.getId(), campaignId, userId, now));

        eventPublisher.publishEvent(
                new RedemptionRecorded(campaign.getClientCode(), voucher.getCode(), userId));

        return RedeemResponse.ok(voucher.getCode(), campaignRepository.findRemainingStock(campaignId));
    }

    /**
     * Voids a voucher.
     *
     * Voiding an ACTIVE code just cancels it: it was never redeemed, so the
     * campaign's stock is untouched. Voiding a REDEEMED one is a reversal, so
     * the unit goes back — without that, stock drifts permanently downward while
     * the user's allowance is returned, and the campaign reports itself sold out
     * while units remain.
     */
    @Transactional
    public RedeemResponse voidVoucher(String code) {
        Voucher voucher = voucherRepository.findByCodeForUpdate(code);
        if (voucher == null) {
            return RedeemResponse.fail(FailureReason.VOUCHER_NOT_FOUND, "Voucher not found");
        }

        if ("VOID".equals(voucher.getStatus())) {
            return RedeemResponse.fail(FailureReason.VOUCHER_ALREADY_VOID, "Voucher is already void");
        }

        boolean reversingRedemption = "REDEEMED".equals(voucher.getStatus());

        voucher.setStatus("VOID");
        voucherRepository.save(voucher);

        Integer remainingStock = null;
        if (reversingRedemption) {
            campaignRepository.restoreStock(voucher.getCampaignId());
            remainingStock = campaignRepository.findRemainingStock(voucher.getCampaignId());
        }

        return RedeemResponse.ok(voucher.getCode(), remainingStock);
    }
}
