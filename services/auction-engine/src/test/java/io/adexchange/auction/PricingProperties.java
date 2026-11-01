package io.adexchange.auction;

import io.adexchange.auction.pricing.PricingArms;
import io.adexchange.auction.pricing.PricingStrategy;
import net.jqwik.api.*;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.LongRange;

import static org.assertj.core.api.Assertions.assertThat;

class PricingProperties {

    @Property
    void clearingPriceIsWithinFloorAndBidAndGspNeverExceedsFirstPrice(
            @ForAll @LongRange(min = 1_000, max = 50_000_000) long bid,
            @ForAll @LongRange(min = 0, max = 50_000_000) long floorRaw,
            @ForAll @DoubleRange(min = 0.0005, max = 0.5) @net.jqwik.api.constraints.Scale(6) double pctr,
            @ForAll @DoubleRange(min = 0, max = 5_000_000) double second) {
        long floor = Math.min(floorRaw, bid);
        long gsp = PricingStrategy.GSP.clearingCpm(bid, pctr, second, floor);
        long fp = PricingStrategy.FIRST_PRICE.clearingCpm(bid, pctr, second, floor);
        assertThat(gsp).isBetween(Math.min(floor, bid), bid);
        assertThat(gsp).isLessThanOrEqualTo(fp);
    }

    @Property
    void armAssignmentIsDeterministic(@ForAll String requestId) {
        var arms = PricingArms.abTest();
        assertThat(arms.select(requestId)).isSameAs(arms.select(requestId));
    }

    @Example
    void abSplitIsRoughlyEven() {
        var arms = PricingArms.abTest();
        int a = 0;
        for (int i = 0; i < 20_000; i++) if (arms.select("req-" + i).name.equals("gsp")) a++;
        assertThat(a).isBetween(9_500, 10_500);
    }
}
