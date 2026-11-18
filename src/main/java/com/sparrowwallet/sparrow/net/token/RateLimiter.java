package com.sparrowwallet.sparrow.net.token;

import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Holds requests to at most perSecond per second and perMinute per minute (0 means no limit), and backs off after a refusal. */
public final class RateLimiter {
    private static final long BACKOFF_BASE_MILLIS = 2000;
    private static final long BACKOFF_MAX_MILLIS = 30_000;

    /** Sleeps for the given nanoseconds; replaceable so tests do not wait. */
    interface Sleeper {
        void sleepNanos(long nanos) throws InterruptedException;
    }

    private final int perSecond;
    private final int perMinute;
    private final LongSupplier nanoClock;
    private final Sleeper sleeper;
    //Start times of the requests in the last minute, oldest first
    private final ArrayDeque<Long> times = new ArrayDeque<>();

    public RateLimiter(int perSecond, int perMinute) {
        this(perSecond, perMinute, System::nanoTime, TimeUnit.NANOSECONDS::sleep);
    }

    RateLimiter(int perSecond, int perMinute, LongSupplier nanoClock, Sleeper sleeper) {
        this.perSecond = perSecond;
        this.perMinute = perMinute;
        this.nanoClock = nanoClock;
        this.sleeper = sleeper;
    }

    /** Blocks until a request may be made. */
    public void acquire() throws InterruptedIOException {
        while(true) {
            if(Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Interrupted waiting to contact the token server");
            }
            long wait;
            synchronized(this) {
                long now = nanoClock.getAsLong();
                while(!times.isEmpty() && times.peekFirst() <= now - TimeUnit.MINUTES.toNanos(1)) {
                    times.pollFirst();
                }
                wait = Math.max(waitFor(now, perSecond, TimeUnit.SECONDS.toNanos(1)), waitFor(now, perMinute, TimeUnit.MINUTES.toNanos(1)));
                if(wait <= 0) {
                    times.addLast(now);
                    return;
                }
            }
            sleep(wait);
        }
    }

    //Nanoseconds until the request that fills the window leaves it, or 0 when there is room
    private long waitFor(long now, int limit, long windowNanos) {
        if(limit <= 0 || times.size() < limit) {
            return 0;
        }
        int skip = times.size() - limit;
        int i = 0;
        for(long t : times) {
            if(i++ == skip) {
                return t + windowNanos - now;
            }
        }
        return 0;
    }

    /** Waits after a refusal (attempt 0 is the first): 2 s, 4 s, 8 s, up to 30 s. */
    public void backoff(int attempt) {
        long millis = Math.min(BACKOFF_MAX_MILLIS, BACKOFF_BASE_MILLIS << Math.min(Math.max(attempt, 0), 10));
        try {
            sleeper.sleepNanos(TimeUnit.MILLISECONDS.toNanos(millis));
        } catch(InterruptedException e) {
            //The next acquire sees the flag and stops
            Thread.currentThread().interrupt();
        }
    }

    private void sleep(long nanos) throws InterruptedIOException {
        try {
            sleeper.sleepNanos(nanos);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            InterruptedIOException ioe = new InterruptedIOException("Interrupted waiting to contact the token server");
            ioe.initCause(e);
            throw ioe;
        }
    }
}
