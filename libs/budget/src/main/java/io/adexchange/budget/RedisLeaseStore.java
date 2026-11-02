package io.adexchange.budget;

import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Lease ledger in Redis; every operation is a single atomic Lua script (see resources/lua). */
public final class RedisLeaseStore implements LeaseStore, AutoCloseable {

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> conn;
    private final String acquire, settle, release, available, credit;

    public RedisLeaseStore(String redisUri) {
        this.client = RedisClient.create(redisUri);
        this.conn = client.connect();
        String common = load("lua/lease-common.lua");
        this.acquire = common + load("lua/acquire.lua");
        this.settle = common + load("lua/settle.lua");
        this.release = common + load("lua/release.lua");
        this.available = common + load("lua/available.lua");
        this.credit = common + load("lua/credit.lua");
    }

    private static String load(String path) {
        try (InputStream in = RedisLeaseStore.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8) + "\n";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private RedisCommands<String, String> r() {
        return conn.sync();
    }

    private static String[] keys(String c) {
        return new String[]{"b:{" + c + "}", "l:{" + c + "}", "r:{" + c + "}"};
    }

    @Override
    public void registerBudget(String campaignId, long budgetMicros) {
        String[] k = keys(campaignId);
        r().hset(k[0], "budget", Long.toString(budgetMicros));
        r().hsetnx(k[0], "committed", "0");
    }

    @Override
    public Grant acquire(String campaignId, String leaseId, long requested, long now, long ttl) {
        List<Object> res = r().eval(acquire, ScriptOutputType.MULTI, keys(campaignId),
                Long.toString(now), leaseId, Long.toString(requested), Long.toString(ttl));
        return new Grant(Long.parseLong(res.get(0).toString()), Long.parseLong(res.get(1).toString()));
    }

    @Override
    public void settle(String campaignId, String leaseId, long spent, long now) {
        r().eval(settle, ScriptOutputType.INTEGER, keys(campaignId),
                Long.toString(now), leaseId, Long.toString(spent));
    }

    @Override
    public void release(String campaignId, String leaseId, long spent, long now) {
        r().eval(release, ScriptOutputType.INTEGER, keys(campaignId),
                Long.toString(now), leaseId, Long.toString(spent));
    }

    @Override
    public long credit(String campaignId, long micros, long now) {
        Object res = r().eval(credit, ScriptOutputType.VALUE, keys(campaignId), Long.toString(now), Long.toString(micros));
        return Long.parseLong(res.toString());
    }

    @Override
    public long available(String campaignId, long now) {
        List<Object> res = r().eval(available, ScriptOutputType.MULTI, keys(campaignId), Long.toString(now));
        return Long.parseLong(res.get(0).toString());
    }

    @Override
    public long committed(String campaignId, long now) {
        List<Object> res = r().eval(available, ScriptOutputType.MULTI, keys(campaignId), Long.toString(now));
        return Long.parseLong(res.get(1).toString());
    }

    @Override
    public void close() {
        conn.close();
        client.shutdown();
    }
}
