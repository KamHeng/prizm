package com.prizm.campaign.repository;

import com.prizm.campaign.model.Redemption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RedemptionRepository extends JpaRepository<Redemption, Long> {

    List<Redemption> findByVoucherId(Long voucherId);

    List<Redemption> findByCampaignId(Long campaignId);

    /**
     * Counts how many vouchers a user currently holds redeemed on a campaign.
     *
     * Redemption rows are append-only, so the log alone would also count
     * vouchers that were voided after the fact; joining to the voucher and
     * filtering on REDEEMED means voiding a voucher returns the user's slot.
     * Counting distinct voucher ids keeps a duplicate log row from silently
     * costing a user part of their allowance.
     *
     * Redemption has no JPA relation to Voucher (both carry raw ids), hence the
     * explicit join predicate.
     */
    @Query("select count(distinct r.voucherId) from Redemption r, Voucher v "
            + "where r.voucherId = v.id "
            + "and r.campaignId = :campaignId "
            + "and r.userId = :userId "
            + "and v.status = 'REDEEMED'")
    long countActiveRedemptionsByUser(@Param("campaignId") Long campaignId,
                                      @Param("userId") String userId);
}
