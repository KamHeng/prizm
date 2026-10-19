package com.prizm.campaign.service;

import com.prizm.campaign.dto.CampaignStats;
import com.prizm.campaign.model.Campaign;
import com.prizm.campaign.model.Voucher;
import com.prizm.campaign.repository.CampaignRepository;
import com.prizm.campaign.repository.VoucherRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class CampaignStatsService {

    private final CampaignRepository campaignRepository;
    private final VoucherRepository voucherRepository;

    public CampaignStatsService(CampaignRepository campaignRepository,
                                VoucherRepository voucherRepository) {
        this.campaignRepository = campaignRepository;
        this.voucherRepository = voucherRepository;
    }

    public CampaignStats getStats(Long campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId).get();

        List<Voucher> vouchers = voucherRepository.findByCampaignId(campaignId);

        int redeemed = 0;
        int active = 0;

        for (Voucher v : vouchers) {
            // Counted from the voucher's current status rather than from the
            // presence of a redemption log row: the log is append-only, so a
            // voucher voided after redemption keeps its row forever. Reading
            // status instead makes redeemedCount agree with the per-user limit,
            // which counts the same way.
            if ("REDEEMED".equals(v.getStatus())) {
                redeemed++;
            }
            if ("ACTIVE".equals(v.getStatus())) {
                active++;
            }
        }

        CampaignStats stats = new CampaignStats();
        stats.setCampaignId(campaign.getId());
        stats.setName(campaign.getName());
        stats.setTotalStock(campaign.getTotalStock());
        stats.setRemainingStock(campaign.getRemainingStock());
        stats.setRedeemedCount(redeemed);
        stats.setActiveVoucherCount(active);
        stats.setMaxPerUser(campaign.getMaxPerUser());
        return stats;
    }

    public List<CampaignStats> getStatsForClient(String clientCode) {
        List<CampaignStats> out = new ArrayList<>();
        for (Campaign c : campaignRepository.findByClientCode(clientCode)) {
            out.add(getStats(c.getId()));
        }
        return out;
    }
}
