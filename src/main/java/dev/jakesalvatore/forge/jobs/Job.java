package dev.jakesalvatore.forge.jobs;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record Job(
        UUID id,
        String queue,
        JsonNode payload,
        JobStatus status,
        int priority,
        Instant runAt,
        int attempts,
        int maxAttempts,
        String claimedBy,
        Instant claimedAt,
        String idempotencyKey,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {
}
