package dev.jakesalvatore.forge.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jakesalvatore.forge.jobs.NewJob;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record SubmitJobRequest(
        @Pattern(regexp = "[a-z0-9_-]+", message = "queue must contain only lowercase letters, digits, '-' and '_'")
        @Size(max = 100)
        String queue,

        @NotBlank(message = "type is required")
        @Size(max = 100)
        String type,

        @NotNull(message = "payload is required")
        JsonNode payload,

        Integer priority,

        Instant runAt,

        @Min(value = 1, message = "maxAttempts must be at least 1")
        @Max(value = 50, message = "maxAttempts must be at most 50")
        Integer maxAttempts,

        @Size(max = 200)
        String idempotencyKey
) {

    public NewJob toNewJob() {
        return new NewJob(
                queue != null ? queue : "default",
                type,
                payload,
                priority != null ? priority : 0,
                runAt != null ? runAt : Instant.now(),
                maxAttempts != null ? maxAttempts : 5,
                idempotencyKey
        );
    }
}
