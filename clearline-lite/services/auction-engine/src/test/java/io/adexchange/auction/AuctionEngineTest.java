package io.adexchange.auction;

import io.adexchange.auction.model.BidResponse;
import io.adexchange.auction.model.Campaign;
import io.adexchange.auction.pricing.PricingStrategy;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static io.adexchange.auction.Fixtures.req;
import static io.adexchange.auction.Fixtures.slot;
import static org.assertj.core.api.Assertions.assertThat;

class AuctionEngineTest {

    @Test
    void highestEcpmWinsAndFirstPricePaysOwnBid() {
        var f = new Fixtures(PricingStrategy.FIRST_PRICE);
        f.add("low", 2_000_000);
        f.add("high", 5_000_000);
        BidResponse r = f.engine.bid(req("r1", slot("s1", 0)));
        assertThat(r.bids()).hasSize(1);
        assertThat(r.bids().get(0).campaignId()).isEqualTo("high");
        assertThat(r.bids().get(0).priceCpmMicros()).isEqualTo(5_000_000);
    }

    @Test
    void gspChargesJustAboveRunnerUp() {
        var f = new Fixtures(PricingStrategy.GSP);
        f.add("low", 2_000_000);
        f.add("high", 5_000_000);
        var bid = f.engine.bid(req("r1", slot("s1", 0))).bids().get(0);
        // same stub pCTR for both => price ~ runner-up's bid
        assertThat(bid.priceCpmMicros()).isBetween(2_000_000L, 2_000_010L);
    }

    @Test
    void gspNeverExceedsBidAndFloorIsEnforced() {
        var f = new Fixtures(PricingStrategy.GSP);
        f.add("only", 3_000_000);
        assertThat(f.engine.bid(req("r1", slot("s1", 3_500_000))).isNoBid()).as("bid below floor").isTrue();
        var bid = f.engine.bid(req("r2", slot("s1", 1_000_000))).bids().get(0);
        assertThat(bid.priceCpmMicros()).as("lone bidder pays the floor").isEqualTo(1_000_000);
    }

    @Test
    void onlyCreativesThatFitTheSlotCompete() {
        var f = new Fixtures(PricingStrategy.FIRST_PRICE);
        f.add("wrong-size", 9_000_000, 100_000_000L, Set.of(new Campaign.Size(728, 90)), 0);
        f.add("fits", 1_000_000, 100_000_000L, Set.of(new Campaign.Size(300, 250)), 0);
        assertThat(f.engine.bid(req("r1", slot("s1", 0))).bids().get(0).campaignId()).isEqualTo("fits");
    }

    @Test
    void oneWinnerPerSlotAndOneSlotPerCampaign() {
        var f = new Fixtures(PricingStrategy.FIRST_PRICE);
        f.add("a", 5_000_000);
        f.add("b", 4_000_000);
        var r = f.engine.bid(req("r1", slot("s1", 0), slot("s2", 0), slot("s3", 0)));
        assertThat(r.bids()).extracting("slotId").containsExactly("s1", "s2");
        assertThat(r.bids()).extracting("campaignId").containsExactly("a", "b");
    }

    @Test
    void exhaustedBudgetFallsThroughToNextCandidateAndFailsClosedWhenNoneLeft() {
        var f = new Fixtures(PricingStrategy.FIRST_PRICE);
        f.add("poor", 9_000_000, 10_000, Set.of(), 0);  // $0.01 budget = exactly 1 impression at $9 CPM (9,000 micros)
        f.add("rich", 1_000_000);
        assertThat(f.engine.bid(req("r1", slot("s1", 0))).bids().get(0).campaignId()).isEqualTo("poor");
        assertThat(f.engine.bid(req("r2", slot("s1", 0))).bids().get(0).campaignId()).isEqualTo("rich");
        assertThat(f.engine.budgetDenied.sum()).isPositive();
        f.index.remove("rich");
        assertThat(f.engine.bid(req("r3", slot("s1", 0))).isNoBid()).isTrue();
    }

    @Test
    void frequencyCapExcludesCampaignForUser() {
        var f = new Fixtures(PricingStrategy.FIRST_PRICE);
        f.add("capped", 9_000_000, 100_000_000L, Set.of(), 2);
        f.add("fallback", 1_000_000);
        for (int i = 0; i < 2; i++)
            assertThat(f.engine.bid(req("r" + i, slot("s1", 0))).bids().get(0).campaignId()).isEqualTo("capped");
        assertThat(f.engine.bid(req("r9", slot("s1", 0))).bids().get(0).campaignId()).isEqualTo("fallback");
    }

    @Test
    void missedStageDeadlineReturnsNoBidNeverLateBid() {
        var f = new Fixtures(PricingStrategy.FIRST_PRICE, new io.adexchange.auction.AuctionEngine.Budgets(0, 0, 0, 0));
        f.add("a", 5_000_000);
        var r = f.engine.bid(req("r1", slot("s1", 0)));
        assertThat(r.isNoBid()).isTrue();
        assertThat(r.noBidReason()).isEqualTo("TIMEOUT");
    }

    @Test
    void targetingAndFlightAreRespected() {
        var f = new Fixtures(PricingStrategy.FIRST_PRICE);
        f.index.upsert(new Campaign("us-only", "adv", "n", 5_000_000, 1_000_000_000L, Set.of("US"), Set.of(), Set.of(), Set.of(),
                0, Set.of(), 0, Fixtures.DAY0 - 1, Fixtures.DAY0 + 1000, true));
        assertThat(f.engine.bid(req("r1", slot("s1", 0))).isNoBid()).as("geo IN not targeted").isTrue();
        f.index.upsert(new Campaign("in-sports", "adv", "n", 5_000_000, 1_000_000_000L, Set.of("IN"), Set.of(), Set.of("sports"), Set.of(),
                0, Set.of(), 0, Fixtures.DAY0 - 1, Fixtures.DAY0 + 1000, true));
        assertThat(f.engine.bid(req("r2", slot("s1", 0))).bids()).hasSize(1);
        f.clock.advance(5_000);
        assertThat(f.engine.bid(req("r3", slot("s1", 0))).isNoBid()).as("flight ended").isTrue();
    }

    @Test
    void indexUpdateIsVisibleImmediatelyAfterUpsertAndRemove() {
        var f = new Fixtures(PricingStrategy.FIRST_PRICE);
        assertThat(f.engine.bid(req("r0", slot("s1", 0))).isNoBid()).isTrue();
        f.add("new", 5_000_000);
        assertThat(f.engine.bid(req("r1", slot("s1", 0))).isNoBid()).isFalse();
        f.index.remove("new");
        assertThat(f.engine.bid(req("r2", slot("s1", 0))).isNoBid()).isTrue();
    }
}
