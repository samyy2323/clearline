package io.adexchange.auction.pricing;

public interface PricingStrategy {

    String name();

    /**
     * @param winnerBid   winner's max CPM
     * @param winnerPctr  winner's predicted CTR
     * @param secondEcpm  eCPM (bid x pCTR) of the best losing eligible candidate, or 0 if none
     * @param floor       slot floor CPM
     * @return clearing CPM, always within [floor, winnerBid]
     */
    long clearingCpm(long winnerBid, double winnerPctr, double secondEcpm, long floor);

    PricingStrategy FIRST_PRICE = new PricingStrategy() {
        public String name() { return "first-price"; }
        public long clearingCpm(long bid, double pctr, double second, long floor) { return bid; }
    };

    /** Generalized second price: pay the minimum bid that would still have won, +1 micro. */
    PricingStrategy GSP = new PricingStrategy() {
        public String name() { return "gsp"; }
        public long clearingCpm(long bid, double pctr, double second, long floor) {
            if (second <= 0 || pctr <= 0) return Math.min(bid, floor);
            long p = (long) Math.ceil(second / pctr) + 1;
            return Math.min(bid, Math.max(floor, p));
        }
    };
}
