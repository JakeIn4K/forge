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
import java.util.List;
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
                        INSERT INTO jobs (id, queue, type, payload, priority, run_at, max_attempts, idempotency_key)
                        VALUES (:id, :queue, :type, :payload::jsonb, :priority, :runAt, :maxAttempts, :idempotencyKey)
                        ON CONFLICT (idempotency_key) DO NOTHING
                        RETURNING *
                        """)
                .param("id", UUID.randomUUID())
                .param("queue", newJob.queue())
                .param("type", newJob.type())
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

    /**
     * Atomically claims the next runnable job for a queue. SKIP LOCKED makes
     * concurrent claimers skip rows another transaction has already locked
     * instead of blocking on them, so N workers each get a different job.
     */
    public Optional<Job> claimNext(String queue, String workerId) {
        return jdbc.sql("""
                        UPDATE jobs
                        SET status = 'CLAIMED', claimed_by = :workerId, claimed_at = now(), updated_at = now()
                        WHERE id = (
                            SELECT id FROM jobs
                            WHERE queue = :queue AND status = 'PENDING' AND run_at <= now()
                            ORDER BY priority DESC, run_at
                            LIMIT 1
                            FOR UPDATE SKIP LOCKED
                        )
                        RETURNING *
                        """)
                .param("queue", queue)
                .param("workerId", workerId)
                .query(jobRowMapper)
                .optional();
    }

    public void markRunning(UUID id) {
        jdbc.sql("UPDATE jobs SET status = 'RUNNING', updated_at = now() WHERE id = :id")
                .param("id", id)
                .update();
    }

    public void markSucceeded(UUID id) {
        jdbc.sql("""
                        UPDATE jobs
                        SET status = 'SUCCEEDED', attempts = attempts + 1, updated_at = now()
                        WHERE id = :id
                        """)
                .param("id", id)
                .update();
    }

    /**
     * Returns a failed job to PENDING with a future run_at, releasing the
     * claim so any worker can pick up the retry once the backoff elapses.
     */
    public void scheduleRetry(UUID id, String error, Instant retryAt) {
        jdbc.sql("""
                        UPDATE jobs
                        SET status = 'PENDING', attempts = attempts + 1, last_error = :error,
                            run_at = :retryAt, claimed_by = NULL, claimed_at = NULL, updated_at = now()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("error", error)
                .param("retryAt", Timestamp.from(retryAt))
                .update();
    }

    public void markDead(UUID id, String error) {
        jdbc.sql("""
                        UPDATE jobs
                        SET status = 'DEAD', attempts = attempts + 1, last_error = :error,
                            claimed_by = NULL, claimed_at = NULL, updated_at = now()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("error", error)
                .update();
    }

    public List<Job> findDead(String queue, int limit) {
        return jdbc.sql("""
                        SELECT * FROM jobs
                        WHERE queue = :queue AND status = 'DEAD'
                        ORDER BY updated_at DESC
                        LIMIT :limit
                        """)
                .param("queue", queue)
                .param("limit", limit)
                .query(jobRowMapper)
                .list();
    }

    /** Jobs claimed before the cutoff whose worker may have died. */
    public List<Job> findStaleClaimed(Instant cutoff, int limit) {
        return jdbc.sql("""
                        SELECT * FROM jobs
                        WHERE status IN ('CLAIMED', 'RUNNING') AND claimed_at <= :cutoff
                        ORDER BY claimed_at
                        LIMIT :limit
                        """)
                .param("cutoff", Timestamp.from(cutoff))
                .param("limit", limit)
                .query(jobRowMapper)
                .list();
    }

    /**
     * Takes a job away from a dead worker: back to PENDING for retry, or DEAD
     * if this crash consumed the last attempt. The claimed_by guard makes
     * concurrent reapers safe — whichever UPDATE runs first wins, the other
     * matches zero rows. A crash counts as an attempt so a job that keeps
     * killing workers cannot loop forever.
     */
    public Optional<Job> reclaim(UUID id, String claimedBy, String error, Instant retryAt) {
        return jdbc.sql("""
                        UPDATE jobs
                        SET status = CASE WHEN attempts + 1 >= max_attempts THEN 'DEAD' ELSE 'PENDING' END,
                            run_at = CASE WHEN attempts + 1 >= max_attempts THEN run_at ELSE :retryAt END,
                            attempts = attempts + 1,
                            claimed_by = NULL, claimed_at = NULL,
                            last_error = :error, updated_at = now()
                        WHERE id = :id AND claimed_by = :claimedBy AND status IN ('CLAIMED', 'RUNNING')
                        RETURNING *
                        """)
                .param("id", id)
                .param("claimedBy", claimedBy)
                .param("error", error)
                .param("retryAt", Timestamp.from(retryAt))
                .query(jobRowMapper)
                .optional();
    }

    private Job mapJob(ResultSet rs, int rowNum) throws SQLException {
        try {
            return new Job(
                    rs.getObject("id", UUID.class),
                    rs.getString("queue"),
                    rs.getString("type"),
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
