package com.prizm.campaign.repository;

import com.prizm.campaign.model.Voucher;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Date;
import java.util.List;

public interface VoucherRepository extends JpaRepository<Voucher, Long> {

    Voucher findByCode(String code);

    List<Voucher> findByCampaignId(Long campaignId);

    /**
     * Locks a voucher row until the surrounding transaction commits. Used by the
     * void path, where contention is negligible and a lock is simpler than a
     * conditional update.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Voucher v where v.code = :code")
    Voucher findByCodeForUpdate(@Param("code") String code);

    /**
     * Marks a voucher redeemed only if it is still ACTIVE, in one statement.
     *
     * The {@code status = 'ACTIVE'} predicate means two concurrent requests for
     * the same code cannot both succeed: the database serialises the row and the
     * loser updates 0 rows. Returns rows updated — 1 for the winner, 0 for
     * anyone who arrived second.
     */
    @Modifying
    @Query("update Voucher v set v.status = 'REDEEMED', v.redeemedBy = :userId, v.redeemedAt = :redeemedAt "
            + "where v.id = :id and v.status = 'ACTIVE'")
    int claimForRedemption(@Param("id") Long id,
                           @Param("userId") String userId,
                           @Param("redeemedAt") Date redeemedAt);

    /**
     * Voucher counts per campaign and status, for every campaign in one query.
     *
     * Replaces loading every voucher entity (and previously a redemption lookup
     * per voucher) just to produce two integers, so the stats endpoints no
     * longer scale their query count or their memory with campaign size.
     */
    @Query("select v.campaignId as campaignId, v.status as status, count(v) as total "
            + "from Voucher v where v.campaignId in :campaignIds "
            + "group by v.campaignId, v.status")
    List<VoucherStatusCount> countByStatusForCampaigns(@Param("campaignIds") Collection<Long> campaignIds);

    /** Projection for {@link #countByStatusForCampaigns}. */
    interface VoucherStatusCount {
        Long getCampaignId();
        String getStatus();
        long getTotal();
    }
}
