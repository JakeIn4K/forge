package dev.jakesalvatore.forge.jobs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JobRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<Job> jobRowMapper = this::mapJob;

    public JobRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Inserts a new job. If the idempotency key already exists, no row is
     * inserted and the result is empty — the caller resolves the existing job.
     * ON CONFLICT makes the duplicate check atomic under concurrent submits.
     */
    public Optional<Job> insert(NewJob newJob) {
        return jdbc.sql("""
                        INSERT INTO jobs (id, queue, payload, priority, run_at, max_attempts, idempotency_key)
                        VALUES (:id, :queue, :payload::jsonb, :priority, :runAt, :maxAttempts, :idempotencyKey)
                        ON CONFLICT (idempotency_key) DO NOTHING
                        RETURNING *
                        """)
                .param("id", UUID.randomUUID())
                .param("queue", newJob.queue())
                .param("payload", toJson(newJob.payload()))
                .param("priority", newJob.priority())
                .param("runAt", Timestamp.from(newJob.runAt()))
                .param("maxAttempts", newJob.maxAttempts())
                .param("idempotencyKey", newJob.idempotencyKey())
                .query(jobRowMapper)
                .optional();
    }

    public Optional<Job> findById(UUID id) {
        return jdbc.sql("SELECT * FROM jobs WHERE id = :id")
                .param("id", id)
                .query(jobRowMapper)
                .optional();
    }

    public Optional<Job> findByIdempotencyKey(String key) {
        return jdbc.sql("SELECT * FROM jobs WHERE idempotency_key = :key")
                .param("key", key)
                .query(jobRowMapper)
                .optional();
    }

    private Job mapJob(ResultSet rs, int rowNum) throws SQLException {
        try {
            return new Job(
                    rs.getObject("id", UUID.class),
                    rs.getString("queue"),
                    objectMapper.readTree(rs.getString("payload")),
                    JobStatus.valueOf(rs.getString("status")),
                    rs.getInt("priority"),
                    instant(rs.getTimestamp("run_at")),
                    rs.getInt("attempts"),
                    rs.getInt("max_attempts"),
                    rs.getString("claimed_by"),
                    instant(rs.getTimestamp("claimed_at")),
                    rs.getString("idempotency_key"),
                    rs.getString("last_error"),
                    instant(rs.getTimestamp("created_at")),
                    instant(rs.getTimestamp("updated_at"))
            );
        } catch (JsonProcessingException e) {
            throw new SQLException("stored payload is not valid JSON", e);
        }
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private String toJson(com.fasterxml.jackson.databind.JsonNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("payload cannot be serialized to JSON", e);
        }
    }
}
