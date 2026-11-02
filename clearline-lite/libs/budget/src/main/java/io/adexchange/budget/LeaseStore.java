package io.adexchange.budget;

/**
 * Authoritative budget ledger. Auction engines never call this per bid; they acquire a chunk
 * ("lease") of a campaign's budget and spend it locally.
 *
 * <p>Money is in micros (1e-6 currency units). Implementations must be atomic per campaign.
 *
 * <p>Accounting model: {@code committed = sum(settled spend of closed leases) + sum(granted of open leases)}.
 * A lease that expires without being released is reclaimed: {@code granted - reportedSpend} returns to the pool.
 * The amount an instance spent <em>after</em> its last settle and before it crashed is therefore the only
 * way overspend can occur, which bounds overspend by one lease per crashed lease.
 */
public interface LeaseStore {

    record Grant(long amountMicros, long expiresAtMillis) {
        public boolean granted() {
            return amountMicros > 0;
        }
    }

    /** Sets (or updates) the total budget. Does not reset spend. */
    void registerBudget(String campaignId, long budgetMicros);

    /** Grants up to {@code requestedMicros}; amount may be 0 when the budget is exhausted. */
    Grant acquire(String campaignId, String leaseId, long requestedMicros, long nowMillis, long ttlMillis);

    /** Heartbeat: cumulative spend so far for an open lease (monotonic, idempotent). */
    void settle(String campaignId, String leaseId, long spentSoFarMicros, long nowMillis);

    /** Closes a lease returning {@code granted - spentTotal} to the pool (idempotent). */
    void release(String campaignId, String leaseId, long spentTotalMicros, long nowMillis);

    /** Uncommitted budget after reclaiming expired leases. */
    long available(String campaignId, long nowMillis);

    /** Budget accounted as spent or leased out. */
    long committed(String campaignId, long nowMillis);
}
