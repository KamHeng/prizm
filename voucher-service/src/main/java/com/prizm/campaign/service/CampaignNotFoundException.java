package com.prizm.campaign.service;

/**
 * Raised when a caller asks about a campaign id that does not exist. Mapped to
 * 404 so a mistyped id is distinguishable from a server fault — it used to
 * surface as a 500 from {@code Optional.get()}.
 */
public class CampaignNotFoundException extends RuntimeException {

    private final Long campaignId;

    public CampaignNotFoundException(Long campaignId) {
        super("No campaign with id " + campaignId);
        this.campaignId = campaignId;
    }

    public Long getCampaignId() {
        return campaignId;
    }
}
