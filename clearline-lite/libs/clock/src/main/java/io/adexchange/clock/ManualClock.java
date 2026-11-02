package io.adexchange.clock;

import java.util.concurrent.atomic.AtomicLong;

/** Test clock advanced explicitly. */
public final class ManualClock implements AdClock {
    private final AtomicLong now;

    public ManualClock(long startMillis) {
        this.now = new AtomicLong(startMillis);
    }

    @Override
    public long nowMillis() {
        return now.get();
    }

    public void advance(long millis) {
        now.addAndGet(millis);
    }

    public void set(long millis) {
        now.set(millis);
    }
}
