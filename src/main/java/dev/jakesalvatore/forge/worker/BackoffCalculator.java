package dev.jakesalvatore.forge.worker;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with full jitter: the delay is drawn uniformly from
 * [0, base * 2^(attempt-1)], capped at maxDelay. Randomizing the entire window
 * (rather than adding jitter on top) spreads simultaneous retries most evenly
 * and avoids thundering-herd spikes against a recovering dependency.
 */
@Component
public class BackoffCalculator {

    private final RetryProperties properties;

    public BackoffCalculator(RetryProperties properties) {
        this.properties = properties;
    }

    public Duration delayFor(int attempt) {
        long capMillis = properties.maxDelay().toMillis();
        long ceilingMillis;
        if (attempt - 1 >= 40) {
            // 2^40 * any base is far past any sane cap; skip the shift to avoid overflow
            ceilingMillis = capMillis;
        } else {
            ceilingMillis = Math.min(capMillis, properties.baseDelay().toMillis() << (attempt - 1));
        }
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(ceilingMillis + 1));
    }
}
