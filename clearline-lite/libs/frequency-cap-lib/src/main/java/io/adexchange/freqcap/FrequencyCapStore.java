package io.adexchange.freqcap;

/** Per-user, per-campaign impression caps over a sliding window made of time buckets. */
public interface FrequencyCapStore {

    /**
     * Batch lookup for all candidates in one call (one Redis round trip).
     *
     * @param caps    max impressions per campaign in the window; {@code <= 0} means uncapped
     * @param capped  output, {@code capped[i]=true} if the user already reached {@code caps[i]}
     * @return false if the result is degraded (store unreachable, answers are fail-open)
     */
    boolean check(String userId, String[] campaignIds, int[] caps, int n, boolean[] capped, long nowMillis);

    void record(String userId, String campaignId, long nowMillis);
}
