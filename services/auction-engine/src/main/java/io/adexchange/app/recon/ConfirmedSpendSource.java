package io.adexchange.app.recon;

import java.util.Map;

/** Billed (confirmed) spend per campaign, in micros: the ground truth the budget ledger is reconciled against. */
public interface ConfirmedSpendSource {

    Map<String, Long> billedByCampaign();

    /** Billed spend per campaign for bookings made at or before {@code tMs} (booking time, not billing time). */
    Map<String, Long> billedForBookingsUpTo(long tMs);

    String name();
}
