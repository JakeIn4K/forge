package dev.jakesalvatore.forge.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "forge.retry")
public record RetryProperties(
        Duration baseDelay,
        Duration maxDelay
) {
}
