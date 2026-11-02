package io.adexchange.auction.pricing;

import io.adexchange.auction.scoring.PctrModel;

import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/** A/B framework: deterministic split of requests across pricing strategies, with revenue recorded per arm. */
public final class PricingArms {

    public static final class Arm {
        public final String name;
        public final PricingStrategy strategy;
        final int weight;
        public final LongAdder auctions = new LongAdder();
        public final LongAdder awards = new LongAdder();
        public final LongAdder revenueMicros = new LongAdder();

        Arm(String name, PricingStrategy s, int weight) {
            this.name = name;
            this.strategy = s;
            this.weight = weight;
        }
    }

    private final Arm[] arms;
    private final int total;

    /** @param weights relative weights, same order as strategies */
    public PricingArms(List<PricingStrategy> strategies, List<Integer> weights) {
        arms = new Arm[strategies.size()];
        int t = 0;
        for (int i = 0; i < arms.length; i++) {
            arms[i] = new Arm(strategies.get(i).name(), strategies.get(i), weights.get(i));
            t += weights.get(i);
        }
        total = t;
    }

    public static PricingArms single(PricingStrategy s) {
        return new PricingArms(List.of(s), List.of(1));
    }

    public static PricingArms abTest() {
        return new PricingArms(List.of(PricingStrategy.FIRST_PRICE, PricingStrategy.GSP), List.of(50, 50));
    }

    /** Same request id always lands in the same arm. */
    public Arm select(String requestId) {
        if (arms.length == 1) return arms[0];
        int b = Math.floorMod(PctrModel.fmix(PctrModel.fnv1a(requestId)), total);
        for (Arm a : arms) {
            if (b < a.weight) return a;
            b -= a.weight;
        }
        return arms[arms.length - 1];
    }

    public List<Arm> arms() {
        return List.of(arms);
    }
}
