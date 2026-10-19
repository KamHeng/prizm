package com.prizm.campaign.service;

import com.prizm.campaign.dto.FailureReason;
import com.prizm.campaign.dto.RedeemResponse;
import com.prizm.campaign.model.Campaign;
import com.prizm.campaign.model.Redemption;
import com.prizm.campaign.model.Voucher;
import com.prizm.campaign.repository.CampaignRepository;
import com.prizm.campaign.repository.RedemptionRepository;
import com.prizm.campaign.repository.VoucherRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

@Service
public class VoucherService {

    @Autowired
    private VoucherRepository voucherRepository;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private RedemptionRepository redemptionRepository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /**
     * Redeems a voucher for a user.
     *
     * The voucher row is locked first and the campaign row second — a single
     * ordering for the one write path, so there is nothing to deadlock against.
     * Both locks are held until commit, which is what makes the status checks,
     * the per-user limit count and the stock decrement safe to act on.
     */
    @Transactional
    public RedeemResponse redeem(String code, String userId) {

        Voucher voucher = voucherRepository.findByCodeForUpdate(code);
        if (voucher == null) {
            return RedeemResponse.fail(FailureReason.VOUCHER_NOT_FOUND, "Voucher not found");
        }

        if ("REDEEMED".equals(voucher.getStatus())) {
            return RedeemResponse.fail(FailureReason.VOUCHER_ALREADY_REDEEMED, "Voucher already redeemed");
        }

        if ("VOID".equals(voucher.getStatus())) {
            return RedeemResponse.fail(FailureReason.VOUCHER_VOID, "Voucher is void");
        }

        Campaign campaign = campaignRepository.findByIdForUpdate(voucher.getCampaignId()).get();

        if (!campaign.isActive()) {
            return RedeemResponse.fail(FailureReason.CAMPAIGN_INACTIVE, "Campaign is not active");
        }

        long alreadyRedeemed = redemptionRepository.countActiveRedemptionsByUser(campaign.getId(), userId);
        if (alreadyRedeemed >= campaign.getMaxPerUser()) {
            return RedeemResponse.fail(FailureReason.USER_LIMIT_REACHED,
                    "Redemption limit reached for this campaign ("
                            + alreadyRedeemed + " of " + campaign.getMaxPerUser() + " used)");
        }

        if (campaign.getRemainingStock() <= 0) {
            return RedeemResponse.fail(FailureReason.CAMPAIGN_OUT_OF_STOCK, "Campaign out of stock");
        }

        voucher.setStatus("REDEEMED");
        voucher.setRedeemedBy(userId);
        voucher.setRedeemedAt(new Date());
        voucherRepository.save(voucher);

        campaign.setRemainingStock(campaign.getRemainingStock() - 1);
        campaignRepository.save(campaign);

        Redemption redemption = new Redemption(
                voucher.getId(), campaign.getId(), userId, new Date());
        redemptionRepository.save(redemption);

        eventPublisher.publishEvent(
                new RedemptionRecorded(campaign.getClientCode(), voucher.getCode(), userId));

        return RedeemResponse.ok(voucher.getCode(), campaign.getRemainingStock());
    }

    public RedeemResponse voidVoucher(String code) {
        Voucher voucher = voucherRepository.findByCode(code);
        if (voucher == null) {
            return RedeemResponse.fail(FailureReason.VOUCHER_NOT_FOUND, "Voucher not found");
        }
        voucher.setStatus("VOID");
        voucherRepository.save(voucher);
        return RedeemResponse.ok(voucher.getCode(), null);
    }
}
