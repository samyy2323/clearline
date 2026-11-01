package io.adexchange.app.config;

import io.adexchange.auction.AuctionEngine;
import io.adexchange.auction.index.IndexHolder;
import io.adexchange.auction.model.Campaign;
import io.adexchange.budget.LeaseManager;
import io.adexchange.budget.LeaseStore;
import io.adexchange.budget.PacingController;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** Ties campaign lifecycle (index events) to budget registration, local leases and pacing controllers. */
@Component
public class PacingRegistry {
    private static final Logger log = LoggerFactory.getLogger(PacingRegistry.class);

    private final Map<String, PacingController> controllers = new ConcurrentHashMap<>();
    private final IndexHolder index;
    private final LeaseStore store;
    private final LeaseManager leases;
    private final ScalableClock clock;
    private final ExchangeProperties props;
    private final AuctionEngine engine;

    public PacingRegistry(IndexHolder index, LeaseStore store, LeaseManager leases, ScalableClock clock,
                          ExchangeProperties props, AuctionEngine engine) {
        this.index = index;
        this.store = store;
        this.leases = leases;
        this.clock = clock;
        this.props = props;
        this.engine = engine;
    }

    @PostConstruct
    void wire() {
        index.listen(this::onUpsert, this::onRemove);
        if (props.pacingEnabled()) {
            engine.setPacing(id -> {
                PacingController pc = controllers.get(id);
                return pc == null || pc.admit(ThreadLocalRandom.current().nextDouble());
            });
        }
    }

    private void onUpsert(Campaign c) {
        store.registerBudget(c.id(), c.budgetMicros());
        try {
            leases.activate(c.id());
        } catch (RuntimeException e) {
            log.warn("could not acquire initial lease for {} (will retry in tick): {}", c.id(), e.toString());
        }
        controllers.put(c.id(), new PacingController(clock, c.startMs(), c.endMs(), c.budgetMicros(), 1.0, 0.02));
    }

    private void onRemove(String id) {
        controllers.remove(id);
        leases.deactivate(id);
    }

    public PacingController get(String id) {
        return controllers.get(id);
    }

    @Scheduled(fixedDelay = 500)
    void tick() {
        controllers.forEach((id, pc) -> pc.update(leases.totalSpent(id)));
        leases.tick();
    }
}
