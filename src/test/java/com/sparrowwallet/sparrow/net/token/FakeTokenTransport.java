package com.sparrowwallet.sparrow.net.token;

import com.sparrowwallet.sparrow.net.ord.Transport;
import org.junit.jupiter.api.Assertions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Answers from a queue of strings or IOExceptions (the last one repeats) and records every URL asked. GET only. */
class FakeTokenTransport implements Transport {
    final List<String> urls = new ArrayList<>();
    final ArrayDeque<Object> answers = new ArrayDeque<>();

    FakeTokenTransport answer(Object answer) {
        answers.add(answer);
        return this;
    }

    @Override
    public String json(String url, String postBody) throws IOException {
        urls.add(url);
        Assertions.assertNull(postBody, "GET only");
        Object next = answers.size() > 1 ? answers.poll() : answers.peek();
        if(next instanceof IOException e) {
            throw e;
        }
        return (String)next;
    }

    @Override
    public byte[] bytes(String url, String accept, int maxBytes) {
        throw new AssertionError("not used");
    }

    static String fixture(Class<?> owner, String path) throws IOException {
        try(InputStream in = owner.getResourceAsStream(path)) {
            Assertions.assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** A limiter that never waits and counts its backoffs. */
    static class Counting {
        final AtomicInteger backoffs = new AtomicInteger();
        final RateLimiter limiter = new RateLimiter(1000, 0, () -> 0, nanos -> backoffs.incrementAndGet());
    }
}
