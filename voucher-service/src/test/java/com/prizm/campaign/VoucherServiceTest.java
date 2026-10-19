package com.prizm.campaign;

import com.prizm.campaign.dto.FailureReason;
import com.prizm.campaign.dto.RedeemResponse;
import com.prizm.campaign.service.AuditClient;
import com.prizm.campaign.service.CampaignStatsService;
import com.prizm.campaign.service.VoucherService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.jdbc.Sql;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every test method starts from a freshly rebuilt database: {@code @Sql} drops,
 * recreates and re-seeds before each one, so no test can observe another's
 * writes and execution order cannot matter.
 *
 * Re-seeding rather than wrapping each test in a rolled-back transaction is
 * deliberate. A test-managed transaction would be invisible to the other
 * threads in {@link #concurrentRedemptionsRespectUserLimit()}, and an
 * {@code AFTER_COMMIT} listener never fires for a transaction that rolls back —
 * so rollback would quietly disable both the concurrency test and the audit
 * path. Re-seeding keeps every redemption a real commit.
 */
@SpringBootTest
@Sql(scripts = {"/schema.sql", "/data.sql"})
class VoucherServiceTest {

    @Autowired
    private VoucherService voucherService;

    @Autowired
    private CampaignStatsService campaignStatsService;

    /** Stops the audit listener from making a real HTTP call during tests. */
    @MockBean
    private AuditClient auditClient;

    @Test
    void redeemActiveVoucherSucceeds() {
        RedeemResponse res = voucherService.redeem("RAYA-0001", "user-1");
        assertEquals("OK", res.getResult());
    }

    @Test
    void redeemAlreadyRedeemedVoucherFails() {
        RedeemResponse res = voucherService.redeem("RAYA-0004", "user-2");
        assertEquals("FAILED", res.getResult());
    }

    @Test
    void redeemUnknownCodeFails() {
        RedeemResponse res = voucherService.redeem("NOPE-9999", "user-3");
        assertEquals("FAILED", res.getResult());
    }

    @Test
    void secondRedemptionWithinLimitSucceeds() {
        assertEquals("OK", voucherService.redeem("LIMIT-0001", "user-l1").getResult());
        assertEquals("OK", voucherService.redeem("LIMIT-0002", "user-l1").getResult());
    }

    @Test
    void redemptionBeyondLimitIsRejected() {
        voucherService.redeem("LIMIT-0003", "user-l2");
        voucherService.redeem("LIMIT-0004", "user-l2");

        RedeemResponse res = voucherService.redeem("LIMIT-0005", "user-l2");

        assertEquals("FAILED", res.getResult());
        assertEquals(FailureReason.USER_LIMIT_REACHED, res.getReason());
        assertEquals("Redemption limit reached for this campaign (2 of 2 used)", res.getMessage());
    }

    @Test
    void limitAppliesPerUserNotPerCampaign() {
        voucherService.redeem("LIMIT-0006", "user-l3a");
        voucherService.redeem("LIMIT-0007", "user-l3a");
        assertEquals(FailureReason.USER_LIMIT_REACHED,
                voucherService.redeem("LIMIT-0008", "user-l3a").getReason());

        // A different user on the same campaign is unaffected.
        assertEquals("OK", voucherService.redeem("LIMIT-0008", "user-l3b").getResult());
    }

    @Test
    void voidingRedeemedVoucherFreesLimitSlot() {
        voucherService.redeem("LIMIT-0009", "user-l4");
        voucherService.redeem("LIMIT-0010", "user-l4");
        assertEquals(FailureReason.USER_LIMIT_REACHED,
                voucherService.redeem("LIMIT-0011", "user-l4").getReason());

        voucherService.voidVoucher("LIMIT-0009");

        assertEquals("OK", voucherService.redeem("LIMIT-0011", "user-l4").getResult());
    }

    @Test
    void voidedRedemptionsDoNotCountTowardLimit() {
        // user-99 has two redemption log rows on campaign 1, but RAYA-0005 was
        // voided, so only one counts and one slot remains.
        assertEquals("OK", voucherService.redeem("RAYA-0002", "user-99").getResult());

        RedeemResponse res = voucherService.redeem("RAYA-0003", "user-99");
        assertEquals(FailureReason.USER_LIMIT_REACHED, res.getReason());
    }

    @Test
    void failuresCarryReasonCodes() {
        assertEquals(FailureReason.VOUCHER_NOT_FOUND,
                voucherService.redeem("NOPE-9999", "user-r").getReason());
        assertEquals(FailureReason.VOUCHER_ALREADY_REDEEMED,
                voucherService.redeem("RAYA-0004", "user-r").getReason());
        assertEquals(FailureReason.VOUCHER_VOID,
                voucherService.redeem("RAYA-0005", "user-r").getReason());
        assertEquals(FailureReason.CAMPAIGN_INACTIVE,
                voucherService.redeem("EXPD-0001", "user-r").getReason());
    }

    @Test
    void redemptionOnOutOfStockCampaignIsRejected() {
        // Campaign 5 is active with remaining_stock 0, and user-sold-new has no
        // history there, so stock is the only thing that can stop them.
        RedeemResponse res = voucherService.redeem("SOLD-0001", "user-sold-new");

        assertEquals("FAILED", res.getResult());
        assertEquals(FailureReason.CAMPAIGN_OUT_OF_STOCK, res.getReason());
        assertEquals("Campaign out of stock", res.getMessage());
    }

    @Test
    void userLimitIsReportedBeforeOutOfStock() {
        // user-sold-cap is at 2 of 2 on campaign 5 AND campaign 5 has no stock.
        // Both checks would reject; the user-specific one must win.
        RedeemResponse res = voucherService.redeem("SOLD-0002", "user-sold-cap");

        assertEquals(FailureReason.USER_LIMIT_REACHED, res.getReason());
    }

    @Test
    void campaignWithLimitOfOneRejectsSecondRedemption() {
        assertEquals("OK", voucherService.redeem("ONE-0001", "user-one-a").getResult());

        RedeemResponse res = voucherService.redeem("ONE-0002", "user-one-a");

        assertEquals(FailureReason.USER_LIMIT_REACHED, res.getReason());
        assertEquals("Redemption limit reached for this campaign (1 of 1 used)", res.getMessage());
    }

    @Test
    void campaignWithLimitOfThreeAllowsThree() {
        assertEquals("OK", voucherService.redeem("TRIO-0001", "user-trio").getResult());
        assertEquals("OK", voucherService.redeem("TRIO-0002", "user-trio").getResult());
        assertEquals("OK", voucherService.redeem("TRIO-0003", "user-trio").getResult());

        RedeemResponse res = voucherService.redeem("TRIO-0004", "user-trio");

        assertEquals(FailureReason.USER_LIMIT_REACHED, res.getReason());
        assertEquals("Redemption limit reached for this campaign (3 of 3 used)", res.getMessage());
    }

    @Test
    void campaignSeededWithoutLimitFallsBackToTwo() {
        // Campaign 8 omits max_per_user in data.sql, so this is the only test
        // that proves the column's DEFAULT 2 takes effect.
        assertEquals(2, campaignStatsService.getStats(8L).getMaxPerUser());

        voucherService.redeem("DFLT-0001", "user-dflt");
        voucherService.redeem("DFLT-0002", "user-dflt");

        assertEquals(FailureReason.USER_LIMIT_REACHED,
                voucherService.redeem("DFLT-0003", "user-dflt").getReason());
    }

    @Test
    void limitIsScopedToOneCampaign() {
        assertEquals("OK", voucherService.redeem("ONE-0005", "user-cross").getResult());
        assertEquals(FailureReason.USER_LIMIT_REACHED,
                voucherService.redeem("ONE-0006", "user-cross").getReason());

        // At the cap on campaign 6, but campaign 2 keeps its own tally.
        assertEquals("OK", voucherService.redeem("MRDK-0001", "user-cross").getResult());
    }

    @Test
    void rejectedRedemptionDoesNotConsumeStock() {
        voucherService.redeem("ONE-0003", "user-one-b");
        int stockAfterRedeem = campaignStatsService.getStats(6L).getRemainingStock();

        RedeemResponse res = voucherService.redeem("ONE-0004", "user-one-b");

        assertEquals(FailureReason.USER_LIMIT_REACHED, res.getReason());
        assertEquals(stockAfterRedeem, campaignStatsService.getStats(6L).getRemainingStock());
    }

    @Test
    void statsExcludeVouchersVoidedAfterRedemption() {
        // Campaign 1 is seeded with two redemption log rows, but RAYA-0005 was
        // voided afterwards, so only RAYA-0004 is still redeemed. Counting log
        // rows instead of current status would report 2 here.
        assertEquals(1, campaignStatsService.getStats(1L).getRedeemedCount());
    }

    @Test
    void statsRedeemedCountFollowsTheSameRuleAsTheUserLimit() {
        voucherService.redeem("LIMIT-0001", "user-stats");
        voucherService.redeem("LIMIT-0002", "user-stats");

        assertEquals(2, campaignStatsService.getStats(4L).getRedeemedCount());
        assertEquals(FailureReason.USER_LIMIT_REACHED,
                voucherService.redeem("LIMIT-0003", "user-stats").getReason());

        voucherService.voidVoucher("LIMIT-0001");

        // One void, two consequences that must agree: the campaign counts one
        // fewer redemption, and the user gets their slot back.
        assertEquals(1, campaignStatsService.getStats(4L).getRedeemedCount());
        assertEquals("OK", voucherService.redeem("LIMIT-0003", "user-stats").getResult());
    }

    @Test
    void statsExposeEachCampaignsLimit() {
        assertEquals(2, campaignStatsService.getStats(1L).getMaxPerUser());
        assertEquals(1, campaignStatsService.getStats(6L).getMaxPerUser());
        assertEquals(3, campaignStatsService.getStats(7L).getMaxPerUser());
    }

    @Test
    void concurrentRedemptionsRespectUserLimit() throws Exception {
        List<String> codes = List.of(
                "LIMIT-0012", "LIMIT-0013", "LIMIT-0014",
                "LIMIT-0015", "LIMIT-0016", "LIMIT-0017");

        ExecutorService pool = Executors.newFixedThreadPool(codes.size());
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<RedeemResponse>> futures = new ArrayList<>();

        try {
            for (String code : codes) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return voucherService.redeem(code, "user-l7");
                }));
            }
            startGate.countDown();

            int succeeded = 0;
            for (Future<RedeemResponse> future : futures) {
                if ("OK".equals(future.get(30, TimeUnit.SECONDS).getResult())) {
                    succeeded++;
                }
            }

            // Six simultaneous attempts, cap of 2: the row locks must let
            // exactly two through.
            assertEquals(2, succeeded);
        } finally {
            pool.shutdownNow();
        }
    }
}
