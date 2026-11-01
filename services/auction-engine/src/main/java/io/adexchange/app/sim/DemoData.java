package io.adexchange.app.sim;

import io.adexchange.auction.model.Campaign;

import java.util.*;

/**
 * Demo advertisers and campaigns (seeded, reproducible). Brand names are used purely as realistic labels for a
 * local demonstration; no logos, endorsements or real performance data are implied.
 */
public final class DemoData {
    public static final String[] GEOS = {"IN", "US", "GB", "DE", "BR", "ID", "JP", "FR"};
    public static final int[] GEO_WEIGHTS = {30, 25, 8, 7, 10, 10, 5, 5};
    public static final String[] DEVICES = {"mobile", "desktop", "tablet", "ctv"};
    public static final String[] SEGMENTS = {"sports", "news", "finance", "gaming", "travel", "fashion", "tech", "food", "auto", "health",
            "music", "movies", "parenting", "education", "fitness", "realestate", "shopping", "diy", "pets", "outdoors"};
    public static final String[] CATEGORIES = {"news", "sports", "tech", "finance", "entertainment", "lifestyle", "travel", "gaming",
            "food", "auto", "health", "education"};
    public static final String[][] SIZES = {{"300", "250"}, {"728", "90"}, {"320", "50"}, {"160", "600"}};

    /** adv-1 .. adv-8 */
    public static final String[] ADVERTISERS = {"Nike", "Samsung", "Spotify", "Zomato", "HDFC Bank", "Hyundai", "Booking.com", "Nykaa"};

    private record Brand(String[] campaigns, String[] segments, String[] categories, String[] geos, String[] devices) {}

    private static final Brand[] BRANDS = {
            new Brand(new String[]{"Brand Awareness Always-On", "Air Max Day Launch", "Running Club Membership", "Jordan Retro Drop", "Back to Sport Student Offer"},
                    new String[]{"sports", "fitness", "fashion", "outdoors"}, new String[]{"sports", "lifestyle"}, new String[]{"IN", "US", "GB", "JP"}, new String[]{"mobile"}),
            new Brand(new String[]{"Brand Awareness Always-On", "Galaxy S Series Preorder", "Neo QLED Festive Sale", "Galaxy Watch Fitness", "Galaxy Book Student Offer"},
                    new String[]{"tech", "gaming", "shopping"}, new String[]{"tech", "entertainment"}, new String[]{"IN", "US", "DE", "BR"}, new String[]{}),
            new Brand(new String[]{"Brand Awareness Always-On", "Premium Family Plan", "Student Premium Offer", "Podcast Originals", "Free to Premium Upgrade"},
                    new String[]{"music", "movies", "education"}, new String[]{"entertainment", "lifestyle"}, new String[]{}, new String[]{"mobile", "ctv"}),
            new Brand(new String[]{"Brand Awareness Always-On", "Gold Membership", "Late Night Cravings", "Weekend Brunch Offers", "Free Delivery Weekend"},
                    new String[]{"food", "shopping"}, new String[]{"food", "lifestyle"}, new String[]{"IN"}, new String[]{"mobile"}),
            new Brand(new String[]{"Brand Awareness Always-On", "Regalia Credit Card", "Home Loan Season Offer", "Personal Loan Pre-Approved", "Smart Savings Account"},
                    new String[]{"finance", "realestate", "auto"}, new String[]{"finance", "news"}, new String[]{"IN"}, new String[]{}),
            new Brand(new String[]{"Brand Awareness Always-On", "Creta Electric Test Drive", "Verna Festive Offers", "Exter Launch Campaign", "Certified Pre-Owned"},
                    new String[]{"auto", "tech"}, new String[]{"auto", "news"}, new String[]{"IN", "US", "DE"}, new String[]{}),
            new Brand(new String[]{"Brand Awareness Always-On", "Genius Loyalty Program", "Summer Getaways", "Last Minute Weekend Deals", "Workation Stays"},
                    new String[]{"travel", "outdoors"}, new String[]{"travel", "lifestyle"}, new String[]{}, new String[]{}),
            new Brand(new String[]{"Brand Awareness Always-On", "Pink Friday Sale", "Luxe Beauty Edit", "Skincare Essentials", "Men's Grooming Range"},
                    new String[]{"fashion", "shopping", "health"}, new String[]{"lifestyle", "health"}, new String[]{"IN"}, new String[]{"mobile"}),
    };

    private DemoData() {}

    private static Set<String> subset(Random r, String[] pool, int min, int max) {
        if (pool.length == 0) return Set.of();
        int n = Math.min(pool.length, min + r.nextInt(max - min + 1));
        List<String> l = new ArrayList<>(Arrays.asList(pool));
        Collections.shuffle(l, r);
        return new HashSet<>(l.subList(0, n));
    }

    public static List<Campaign> generate(long seed, int count, long startMs, long endMs, double budgetScale, String idPrefix) {
        Random r = new Random(seed);
        List<Campaign> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int a = i % BRANDS.length, k = (i / BRANDS.length) % 5, wave = i / (BRANDS.length * 5);
            Brand b = BRANDS[a];
            boolean awareness = k == 0;
            long bid = 1_200_000L + (long) (r.nextDouble() * r.nextDouble() * 8_000_000L) + r.nextInt(1_300_000);
            long budget = (long) ((20 + r.nextInt(61)) * 1_000_000L * budgetScale);
            Set<String> geos = awareness ? Set.of() : subset(r, b.geos(), 1, 3);
            Set<String> devices = awareness ? Set.of() : subset(r, b.devices(), 1, 2);
            Set<String> segs = awareness ? Set.of() : subset(r, b.segments(), 1, 3);
            Set<String> cats = awareness || r.nextBoolean() ? Set.of() : subset(r, b.categories(), 1, 2);
            int mask = 0;
            if (!awareness && r.nextDouble() < 0.3) {
                int from = r.nextInt(24);
                for (int h = 0; h < 8; h++) mask |= 1 << ((from + h) % 24);
            }
            Set<Campaign.Size> sizes = new HashSet<>();
            if (!awareness && r.nextDouble() < 0.3) {
                String[] sz = SIZES[r.nextInt(SIZES.length)];
                sizes.add(new Campaign.Size(Integer.parseInt(sz[0]), Integer.parseInt(sz[1])));
            }
            int cap = awareness ? 0 : (r.nextDouble() < 0.5 ? 0 : 3 + r.nextInt(8));
            String name = ADVERTISERS[a] + " | " + b.campaigns()[k] + (wave > 0 ? " (Wave " + (wave + 1) + ")" : "");
            out.add(new Campaign(idPrefix + String.format("%03d", i), "adv-" + (a + 1), name, bid, budget,
                    geos, devices, segs, cats, mask, sizes, cap, startMs, endMs, true));
        }
        return out;
    }

    /**
     * Hidden ground-truth CTR for (segment x category x hour): what the model has to learn. Built from separable audience,
     * context and time-of-day effects (how real CTR behaves), each a deterministic hash-derived multiplier, so a hashed
     * logistic regression can recover it from a realistic volume of data.
     */
    public static double trueCtr(int seg, int cat, int hour) {
        double segMult = 0.35 + 1.9 * unit(seg * 7919L + 13);        // audience interest   0.35x .. 2.25x
        double catMult = 0.55 + 1.2 * unit(cat * 104729L + 29);      // page context        0.55x .. 1.75x
        double daypart = 1.0 + 0.45 * Math.sin((hour - 14) / 24.0 * 2 * Math.PI); // evening peak
        return Math.min(0.30, 0.018 * segMult * catMult * daypart);
    }

    private static double unit(long x) {
        x = (x ^ (x >>> 33)) * 0xff51afd7ed558ccdL;
        x = (x ^ (x >>> 33)) * 0xc4ceb9fe1a85ec53L;
        x ^= x >>> 33;
        return (x & 0xFFFFFFL) / (double) 0x1000000L;
    }
}
