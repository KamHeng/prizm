package com.prizm.campaign.repository;

import com.prizm.campaign.model.Voucher;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface VoucherRepository extends JpaRepository<Voucher, Long> {

    Voucher findByCode(String code);

    List<Voucher> findByCampaignId(Long campaignId);

    /**
     * Loads a voucher with a row lock held until the surrounding transaction
     * commits, so two concurrent redemptions of the same code cannot both pass
     * the status checks. Always locked before the campaign row to keep a single
     * lock ordering across the service.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Voucher v where v.code = :code")
    Voucher findByCodeForUpdate(@Param("code") String code);
}
