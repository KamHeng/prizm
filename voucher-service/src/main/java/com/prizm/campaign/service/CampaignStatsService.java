package com.prizm.campaign.service;

import com.prizm.campaign.dto.CampaignStats;
import com.prizm.campaign.model.Campaign;
import com.prizm.campaign.repository.CampaignRepository;
import com.prizm.campaign.repository.VoucherRepository;
import com.prizm.campaign.repository.VoucherRepository.VoucherStatusCount;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CampaignStatsService {

    private static final String STATUS_REDEEMED = "REDEEMED";
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final CampaignRepository campaignRepository;
    private final VoucherRepository voucherRepository;

    public CampaignStatsService(CampaignRepository campaignRepository,
                                VoucherRepository voucherRepository) {
        this.campaignRepository = campaignRepository;
        this.voucherRepository = voucherRepository;
    }

    public CampaignStats getStats(Long campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new CampaignNotFoundException(campaignId));

        Map<String, Long> counts = countsByCampaign(List.of(campaignId))
                .getOrDefault(campaignId, Map.of());

        return toStats(campaign, counts);
    }

    /**
     * Two queries regardless of how many campaigns the client has: one for the
     * campaigns, one grouped count for all of their vouchers.
     *
     * This used to call {@link #getStats} per campaign, each of which loaded
     * every voucher entity for that campaign — so both the query count and the
     * memory use grew with the client's catalogue.
     */
    public List<CampaignStats> getStatsForClient(String clientCode) {
        List<Campaign> campaigns = campaignRepository.findByClientCode(clientCode);
        if (campaigns.isEmpty()) {
            return List.of();
        }

        List<Long> campaignIds = campaigns.stream().map(Campaign::getId).toList();
        Map<Long, Map<String, Long>> counts = countsByCampaign(campaignIds);

        return campaigns.stream()
                .map(c -> toStats(c, counts.getOrDefault(c.getId(), Map.of())))
                .toList();
    }

    private Map<Long, Map<String, Long>> countsByCampaign(Collection<Long> campaignIds) {
        Map<Long, Map<String, Long>> byCampaign = new HashMap<>();
        for (VoucherStatusCount row : voucherRepository.countByStatusForCampaigns(campaignIds)) {
            byCampaign.computeIfAbsent(row.getCampaignId(), k -> new HashMap<>())
                    .put(row.getStatus(), row.getTotal());
        }
        return byCampaign;
    }

    private CampaignStats toStats(Campaign campaign, Map<String, Long> counts) {
        CampaignStats stats = new CampaignStats();
        stats.setCampaignId(campaign.getId());
        stats.setName(campaign.getName());
        stats.setTotalStock(campaign.getTotalStock());
        stats.setRemainingStock(campaign.getRemainingStock());
        // Counted from the voucher's current status rather than from the
        // presence of a redemption log row: the log is append-only, so a
        // voucher voided after redemption keeps its row forever. Reading status
        // instead makes redeemedCount agree with the per-user limit, which
        // counts the same way.
        stats.setRedeemedCount(counts.getOrDefault(STATUS_REDEEMED, 0L).intValue());
        stats.setActiveVoucherCount(counts.getOrDefault(STATUS_ACTIVE, 0L).intValue());
        stats.setMaxPerUser(campaign.getMaxPerUser());
        return stats;
    }
}
