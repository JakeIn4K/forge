package dev.jakesalvatore.forge.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "forge.worker")
public record WorkerProperties(
        int threads,
        Duration pollInterval,
        String queue
) {
}
