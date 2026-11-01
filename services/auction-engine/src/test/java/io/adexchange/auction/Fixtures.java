package io.adexchange.auction;

import io.adexchange.auction.index.IndexHolder;
import io.adexchange.auction.model.BidRequest;
import io.adexchange.auction.model.Campaign;
import io.adexchange.auction.pricing.PricingArms;
import io.adexchange.auction.pricing.PricingStrategy;
import io.adexchange.auction.scoring.PctrModel;
import io.adexchange.budget.InMemoryLeaseStore;
import io.adexchange.budget.LeaseManager;
import io.adexchange.clock.ManualClock;
import io.adexchange.freqcap.InMemoryFrequencyCapStore;

import java.util.List;
import java.util.Set;

final class Fixtures {
    static final long DAY0 = 1_700_000_000_000L;

    final ManualClock clock = new ManualClock(DAY0);
    final IndexHolder index = new IndexHolder();
    final PctrModel model = new PctrModel();
    final InMemoryLeaseStore store = new InMemoryLeaseStore();
    final LeaseManager leases = new LeaseManager(store, clock, "eng", 1_000_000, 3_600_000, 1_000, 0.3);
    final InMemoryFrequencyCapStore freq = new InMemoryFrequencyCapStore(86_400_000L, 3_600_000L);
    AuctionEngine engine;

    Fixtures(PricingStrategy strategy) {
        this(strategy, AuctionEngine.Budgets.defaults());
    }

    Fixtures(PricingStrategy strategy, AuctionEngine.Budgets budgets) {
        engine = new AuctionEngine(index, model, freq, leases, PricingArms.single(strategy), clock, budgets);
        index.listen(c -> {
            store.registerBudget(c.id(), c.budgetMicros());
            leases.activate(c.id());
        }, leases::deactivate);
    }

    Campaign add(String id, long bidCpm) {
        return add(id, bidCpm, 100_000_000L, Set.of(), 0);
    }

    Campaign add(String id, long bidCpm, long budget, Set<Campaign.Size> sizes, int freqCap) {
        Campaign c = new Campaign(id, "adv-" + id, id, bidCpm, budget, Set.of(), Set.of(), Set.of(), Set.of(),
                0, sizes, freqCap, DAY0 - 1000, DAY0 + 10 * 86_400_000L, true);
        index.upsert(c);
        return c;
    }

    static BidRequest req(String id, BidRequest.Slot... slots) {
        return new BidRequest(id, "pub1", "news", "mobile", "IN", "user1", List.of("sports"), List.of(slots));
    }

    static BidRequest.Slot slot(String id, long floor) {
        return new BidRequest.Slot(id, 300, 250, floor);
    }
}
