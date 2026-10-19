package com.prizm.campaign.repository;

import com.prizm.campaign.model.Campaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    List<Campaign> findByClientCode(String clientCode);

    /**
     * Takes one unit of stock, or reports that there was none to take.
     *
     * The {@code remaining_stock > 0} predicate is the whole guard: the database
     * applies the test and the decrement in one statement, so no two callers can
     * both read the last unit. Returns the number of rows updated — 1 for a
     * successful claim, 0 for out of stock. This replaces a pessimistic lock on
     * the campaign row, which serialised every redemption on the campaign.
     */
    @Modifying
    @Query("update Campaign c set c.remainingStock = c.remainingStock - 1 "
            + "where c.id = :id and c.remainingStock > 0")
    int claimStock(@Param("id") Long id);

    /**
     * Returns one unit of stock when a redemption is reversed, never exceeding
     * total_stock.
     */
    @Modifying
    @Query("update Campaign c set c.remainingStock = c.remainingStock + 1 "
            + "where c.id = :id and c.remainingStock < c.totalStock")
    int restoreStock(@Param("id") Long id);

    /**
     * Reads stock straight from the database. Needed because the bulk updates
     * above bypass the persistence context, so a Campaign entity loaded earlier
     * in the same transaction still holds the pre-update value.
     */
    @Query("select c.remainingStock from Campaign c where c.id = :id")
    Integer findRemainingStock(@Param("id") Long id);
}
