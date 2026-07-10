package dev.jakesalvatore.forge.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jakesalvatore.forge.jobs.Job;
import dev.jakesalvatore.forge.jobs.JobStatus;

import java.time.Instant;
import java.util.UUID;

public record JobResponse(
        UUID id,
        String queue,
        JobStatus status,
        JsonNode payload,
        int priority,
        Instant runAt,
        int attempts,
        int maxAttempts,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {

    public static JobResponse from(Job job) {
        return new JobResponse(
                job.id(),
                job.queue(),
                job.status(),
                job.payload(),
                job.priority(),
                job.runAt(),
                job.attempts(),
                job.maxAttempts(),
                job.lastError(),
                job.createdAt(),
                job.updatedAt()
        );
    }
}
