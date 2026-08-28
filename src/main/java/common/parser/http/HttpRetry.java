package common.parser.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class HttpRetry {

    public interface BlockDetector {
        boolean isBlocked(int statusCode, String body);
    }

    private final HttpClient client;
    private final int maxRetries;
    private final long minRequestIntervalMs;
    private final long blockWaitMs;
    private final int maxBlockRetries;
    private final boolean treatForbiddenAsBlock;
    private final boolean notFoundReturnsEmpty;
    private final BlockDetector blockDetector;

    private final Object rateLimitLock = new Object();
    private volatile long lastRequestTime = 0;
    private final AtomicLong blockedUntil = new AtomicLong(0);

    public HttpRetry(HttpClient client, int maxRetries, long minRequestIntervalMs,
                      long blockWaitMs, int maxBlockRetries,
                      boolean treatForbiddenAsBlock, boolean notFoundReturnsEmpty,
                      BlockDetector blockDetector) {
        this.client = client;
        this.maxRetries = maxRetries;
        this.minRequestIntervalMs = minRequestIntervalMs;
        this.blockWaitMs = blockWaitMs;
        this.maxBlockRetries = maxBlockRetries;
        this.treatForbiddenAsBlock = treatForbiddenAsBlock;
        this.notFoundReturnsEmpty = notFoundReturnsEmpty;
        this.blockDetector = blockDetector != null ? blockDetector : (statusCode, body) -> false;
    }

    // Convenience constructor for callers with no throttle and no block/403 handling
    // (a plain "N attempts, backoff on failure" contract).
    public HttpRetry(HttpClient client, int maxRetries) {
        this(client, maxRetries, 0, 0, 0, false, false, null);
    }

    public String fetch(String url, Consumer<HttpRequest.Builder> requestCustomizer) throws Exception {
        Exception lastError = null;
        int blockRetries = 0;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            awaitIfBlocked();
            throttle();

            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url)).GET();
            requestCustomizer.accept(builder);

            try {
                HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                int statusCode = response.statusCode();

                if (statusCode == 200) {
                    if (blockDetector.isBlocked(200, response.body())) {
                        if (blockRetries >= maxBlockRetries) {
                            throw new RuntimeException("Blocked even after long waits: " + url);
                        }
                        blockRetries++;
                        System.out.println("Blocked page detected for " + url + ". Waiting "
                                + (blockWaitMs / 60000) + " min (" + blockRetries + "/" + maxBlockRetries + ")...");
                        reportBlock();
                        attempt--;
                        continue;
                    }
                    return response.body();
                }

                if (statusCode == 403 && treatForbiddenAsBlock) {
                    if (blockRetries >= maxBlockRetries) {
                        throw new RuntimeException("HTTP 403 even after long waits: " + url);
                    }
                    blockRetries++;
                    System.out.println("HTTP 403 for " + url + ". Waiting "
                            + (blockWaitMs / 60000) + " min (" + blockRetries + "/" + maxBlockRetries + ")...");
                    reportBlock();
                    attempt--;
                    continue;
                }

                if (statusCode == 404 && notFoundReturnsEmpty) {
                    return "";
                }

                if (statusCode == 429 || statusCode >= 500 || (statusCode == 403 && !treatForbiddenAsBlock)) {
                    long backoffMs = resolveBackoff(response, attempt);
                    System.out.println("HTTP " + statusCode + " for " + url
                            + " -- retry " + attempt + "/" + maxRetries + " after " + backoffMs + "ms");
                    String body = response.body();
                    System.out.println("--- Response body (first 500 chars) ---");
                    System.out.println(body != null ? body.substring(0, Math.min(500, body.length())) : "null");
                    System.out.println("--- End body ---");
                    Thread.sleep(backoffMs);
                    lastError = new RuntimeException("HTTP " + statusCode + " for " + url);
                    continue;
                }

                throw new RuntimeException("HTTP " + statusCode + " for " + url);

            } catch (java.io.IOException e) {
                lastError = e;
                Thread.sleep(exponentialBackoffMs(attempt));
            }
        }

        throw lastError != null ? lastError : new RuntimeException("Failed to fetch " + url);
    }

    // Transport-agnostic retry for callers that don't go through java.net.http.HttpClient
    // (e.g. Jsoup's own connect().get()), sharing the same backoff standard as fetch().
    public static <T> T retryWithBackoff(int maxRetries, Callable<T> attempt) throws Exception {
        Exception lastError = null;
        for (int i = 1; i <= maxRetries; i++) {
            try {
                return attempt.call();
            } catch (Exception e) {
                lastError = e;
                if (i < maxRetries) {
                    try {
                        Thread.sleep(exponentialBackoffMs(i));
                    } catch (InterruptedException ignored) {}
                }
            }
        }
        throw lastError;
    }

    private void throttle() {
        if (minRequestIntervalMs <= 0) return;
        synchronized (rateLimitLock) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRequestTime;
            long waitTime = minRequestIntervalMs - elapsed;
            if (waitTime > 0) {
                try {
                    Thread.sleep(waitTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastRequestTime = System.currentTimeMillis();
        }
    }

    // Shared across all callers of this instance so a detected block pauses every
    // thread using it instead of each one independently sleeping and retrying in lockstep.
    private void awaitIfBlocked() throws InterruptedException {
        long wait = blockedUntil.get() - System.currentTimeMillis();
        if (wait > 0) {
            Thread.sleep(wait + ThreadLocalRandom.current().nextLong(0, 5000));
        }
    }

    private void reportBlock() {
        long candidate = System.currentTimeMillis() + blockWaitMs;
        blockedUntil.updateAndGet(prev -> Math.max(prev, candidate));
    }

    private long resolveBackoff(HttpResponse<String> response, int attempt) {
        Optional<String> retryAfter = response.headers().firstValue("Retry-After");
        if (retryAfter.isPresent()) {
            try {
                return Long.parseLong(retryAfter.get().trim()) * 1000L;
            } catch (NumberFormatException ignored) {}
        }
        return exponentialBackoffMs(attempt);
    }

    private static long exponentialBackoffMs(int attempt) {
        return (long) Math.pow(2, attempt - 1) * 1000L;
    }

    // Generic Cloudflare-style challenge-page sniff, shared because it's not
    // domain-specific (identical check was previously duplicated per parser).
    public static boolean looksLikeBlockedPage(String body) {
        if (body == null) return false;
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("just a moment")
                || lower.contains("checking your browser")
                || lower.contains("temporarily unavailable")
                || lower.contains("cf-challenge")
                || lower.contains("captcha")
                || lower.contains("attention required");
    }
}
