package dev.jakesalvatore.forge.worker;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffCalculatorTest {

    private final BackoffCalculator calculator = new BackoffCalculator(
            new RetryProperties(Duration.ofSeconds(1), Duration.ofMinutes(10)));

    @Test
    void delayIsWithinTheExponentialWindow() {
        for (int i = 0; i < 1_000; i++) {
            assertThat(calculator.delayFor(1)).isBetween(Duration.ZERO, Duration.ofSeconds(1));
            assertThat(calculator.delayFor(2)).isBetween(Duration.ZERO, Duration.ofSeconds(2));
            assertThat(calculator.delayFor(5)).isBetween(Duration.ZERO, Duration.ofSeconds(16));
        }
    }

    @Test
    void delayIsCappedAtMaxDelay() {
        for (int i = 0; i < 1_000; i++) {
            assertThat(calculator.delayFor(11)).isBetween(Duration.ZERO, Duration.ofMinutes(10));
            // far past any representable exponent — must not overflow
            assertThat(calculator.delayFor(500)).isBetween(Duration.ZERO, Duration.ofMinutes(10));
        }
    }

    @Test
    void delaysAreActuallyJittered() {
        Set<Duration> observed = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            observed.add(calculator.delayFor(10));
        }
        // 100 draws from a half-kilosecond window collapsing to a handful of
        // values would mean the randomness is broken
        assertThat(observed.size()).isGreaterThan(10);
    }
}
