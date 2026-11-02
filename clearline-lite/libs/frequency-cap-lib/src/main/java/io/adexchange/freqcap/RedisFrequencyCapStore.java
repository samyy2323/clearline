package io.adexchange.freqcap;

import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;

import java.util.List;

/**
 * All of one user's counters live in a single hash {@code fc:{user}} (field = campaign:bucket), so a batch
 * check for N candidates is ONE Lua call, one round trip, and the key is cluster-safe.
 */
public final class RedisFrequencyCapStore implements FrequencyCapStore, AutoCloseable {

    private static final String CHECK = """
            local nowB = tonumber(ARGV[1]); local nB = tonumber(ARGV[2])
            local out = {}
            for i = 3, #ARGV, 2 do
              local cap = tonumber(ARGV[i + 1]); local sum = 0
              if cap > 0 then
                for b = nowB - nB + 1, nowB do
                  sum = sum + tonumber(redis.call('HGET', KEYS[1], ARGV[i] .. ':' .. b) or '0')
                end
              end
              out[#out + 1] = (cap > 0 and sum >= cap) and 1 or 0
            end
            return out
            """;

    private static final String RECORD = """
            redis.call('HINCRBY', KEYS[1], ARGV[1] .. ':' .. ARGV[2], 1)
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return 1
            """;

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> conn;
    private final long bucketMillis;
    private final int buckets;

    public RedisFrequencyCapStore(String uri, long windowMillis, long bucketMillis) {
        this.client = RedisClient.create(uri);
        this.conn = client.connect();
        this.bucketMillis = bucketMillis;
        this.buckets = (int) Math.max(1, windowMillis / bucketMillis);
    }

    @Override
    public boolean check(String userId, String[] ids, int[] caps, int n, boolean[] capped, long now) {
        String[] argv = new String[2 + 2 * n];
        argv[0] = Long.toString(now / bucketMillis);
        argv[1] = Integer.toString(buckets);
        for (int i = 0; i < n; i++) {
            argv[2 + 2 * i] = ids[i];
            argv[3 + 2 * i] = Integer.toString(caps[i]);
        }
        try {
            List<Object> res = conn.sync().eval(CHECK, ScriptOutputType.MULTI, new String[]{"fc:{" + userId + "}"}, argv);
            for (int i = 0; i < n; i++) capped[i] = ((Long) res.get(i)) == 1L;
            return true;
        } catch (RuntimeException e) {
            for (int i = 0; i < n; i++) capped[i] = false; // fail open
            return false;
        }
    }

    @Override
    public void record(String userId, String campaignId, long now) {
        conn.sync().eval(RECORD, ScriptOutputType.INTEGER, new String[]{"fc:{" + userId + "}"},
                campaignId, Long.toString(now / bucketMillis), Long.toString(buckets * bucketMillis * 2));
    }

    @Override
    public void close() {
        conn.close();
        client.shutdown();
    }
}
