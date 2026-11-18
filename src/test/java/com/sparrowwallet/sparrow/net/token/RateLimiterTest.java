package com.sparrowwallet.sparrow.net.token;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class RateLimiterTest {
    @Test
    public void fifthRequestWaitsAfterFourFast() throws InterruptedIOException {
        AtomicLong now = new AtomicLong();
        AtomicLong slept = new AtomicLong();
        RateLimiter limiter = new RateLimiter(4, 0, now::get, nanos -> {
            slept.addAndGet(nanos);
            now.addAndGet(nanos);
        });

        for(int i = 0; i < 4; i++) {
            limiter.acquire();
        }
        Assertions.assertTrue(slept.get() < TimeUnit.MILLISECONDS.toNanos(100), "four requests go through at once");

        limiter.acquire();
        Assertions.assertTrue(slept.get() >= TimeUnit.MILLISECONDS.toNanos(200), "the fifth waits");
        Assertions.assertEquals(TimeUnit.SECONDS.toNanos(1), slept.get());
    }

    @Test
    public void perMinuteLimitApplies() throws InterruptedIOException {
        AtomicLong now = new AtomicLong();
        AtomicLong slept = new AtomicLong();
        RateLimiter limiter = new RateLimiter(0, 2, now::get, nanos -> {
            slept.addAndGet(nanos);
            now.addAndGet(nanos);
        });
        limiter.acquire();
        limiter.acquire();
        Assertions.assertEquals(0, slept.get());
        limiter.acquire();
        Assertions.assertEquals(TimeUnit.MINUTES.toNanos(1), slept.get());
    }

    @Test
    public void backoffDoublesAndIsCapped() {
        AtomicLong slept = new AtomicLong();
        RateLimiter limiter = new RateLimiter(4, 0, () -> 0, slept::set);
        limiter.backoff(0);
        Assertions.assertEquals(TimeUnit.SECONDS.toNanos(2), slept.get());
        limiter.backoff(1);
        Assertions.assertEquals(TimeUnit.SECONDS.toNanos(4), slept.get());
        limiter.backoff(2);
        Assertions.assertEquals(TimeUnit.SECONDS.toNanos(8), slept.get());
        limiter.backoff(9);
        Assertions.assertEquals(TimeUnit.SECONDS.toNanos(30), slept.get());
    }
}
