package io.adexchange.app.sim;

import io.adexchange.auction.model.Campaign;

import java.util.*;

/** Generated advertisers/campaigns with varied budgets, bids, targeting and dayparts (seeded, reproducible). */
public final class DemoData {
    public static final String[] GEOS = {"IN", "US", "GB", "DE", "BR", "ID", "JP", "FR"};
    public static final int[] GEO_WEIGHTS = {30, 25, 8, 7, 10, 10, 5, 5};
    public static final String[] DEVICES = {"mobile", "desktop", "tablet", "ctv"};
    public static final String[] SEGMENTS = {"sports", "news", "finance", "gaming", "travel", "fashion", "tech", "food", "auto", "health",
            "music", "movies", "parenting", "education", "fitness", "realestate", "shopping", "diy", "pets", "outdoors"};
    public static final String[] CATEGORIES = {"news", "sports", "tech", "finance", "entertainment", "lifestyle", "travel", "gaming",
            "food", "auto", "health", "education"};
    public static final String[][] SIZES = {{"300", "250"}, {"728", "90"}, {"320", "50"}, {"160", "600"}};

    private DemoData() {}

    private static <T> Set<T> pick(Random r, T[] pool, int min, int max) {
        int n = min + r.nextInt(max - min + 1);
        Set<T> s = new HashSet<>();
        while (s.size() < n) s.add(pool[r.nextInt(pool.length)]);
        return s;
    }

    public static List<Campaign> generate(long seed, int count, long startMs, long endMs, double budgetScale, String idPrefix) {
        Random r = new Random(seed);
        List<Campaign> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            boolean broad = i < 6;
            long bid = 1_000_000L + (long) (r.nextDouble() * r.nextDouble() * 9_000_000L) + r.nextInt(1_500_000);
            long budget = (long) ((20 + r.nextInt(61)) * 1_000_000L * budgetScale);
            Set<String> geos = (broad || r.nextBoolean()) ? Set.of() : pick(r, GEOS, 1, 3);
            Set<String> devices = (broad || r.nextDouble() < 0.6) ? Set.of() : pick(r, DEVICES, 1, 2);
            Set<String> segs = (broad || r.nextDouble() < 0.6) ? Set.of() : pick(r, SEGMENTS, 2, 5);
            Set<String> cats = (broad || r.nextBoolean()) ? Set.of() : pick(r, CATEGORIES, 1, 3);
            int mask = 0;
            if (!broad && r.nextDouble() < 0.3) {
                int from = r.nextInt(24);
                for (int h = 0; h < 8; h++) mask |= 1 << ((from + h) % 24);
            }
            Set<Campaign.Size> sizes = new HashSet<>();
            if (!broad && r.nextDouble() < 0.3) for (String s : pick(r, new String[]{"0", "1", "2", "3"}, 1, 2))
                sizes.add(new Campaign.Size(Integer.parseInt(SIZES[Integer.parseInt(s)][0]), Integer.parseInt(SIZES[Integer.parseInt(s)][1])));
            int cap = r.nextDouble() < 0.5 ? 0 : 3 + r.nextInt(8);
            String id = idPrefix + String.format("%03d", i);
            out.add(new Campaign(id, "adv-" + (1 + i % 8), "Campaign " + (i + 1) + (broad ? " (run of network)" : ""), bid, budget,
                    geos, devices, segs, cats, mask, sizes, cap, startMs, endMs, true));
        }
        return out;
    }

    /** Hidden ground-truth CTR per (segment x category x hour): what the model should learn. */
    public static double trueCtr(int seg, int cat, int hour) {
        double base = 0.006 + 0.03 * (((seg * 31 + cat * 17) * 2654435761L >>> 8) % 1000) / 1000.0;
        double daypart = 1.0 + 0.5 * Math.sin((hour - 14) / 24.0 * 2 * Math.PI); // evening peak
        return Math.min(0.25, base * daypart);
    }
}
