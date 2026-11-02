package io.adexchange.auction;

/** Receives sampled auction traces. {@link #wants()} is called for EVERY auction, so it must be a couple of nanoseconds (a rate-limit check). */
public interface AuctionObserver {

    boolean wants();

    void accept(AuctionTrace trace);
}
