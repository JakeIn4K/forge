package dev.jakesalvatore.forge.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "forge.rate-limit")
public record RateLimitProperties(
        int capacity,
        double refillPerSecond
) {
}
