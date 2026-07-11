package dev.jakesalvatore.forge.jobs;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record NewJob(
        String queue,
        String type,
        JsonNode payload,
        int priority,
        Instant runAt,
        int maxAttempts,
        String idempotencyKey
) {
}
