package dev.jakesalvatore.forge.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis fast path for duplicate submissions. Purely an optimization: the
 * unique constraint on jobs.idempotency_key is what guarantees correctness,
 * so every Redis failure degrades to the database check instead of erroring.
 */
@Component
public class IdempotencyCache {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCache.class);
    private static final String KEY_PREFIX = "forge:idem:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public IdempotencyCache(StringRedisTemplate redis, @Value("${forge.idempotency.ttl}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    public Optional<UUID> findJobId(String idempotencyKey) {
        try {
            String jobId = redis.opsForValue().get(KEY_PREFIX + idempotencyKey);
            return Optional.ofNullable(jobId).map(UUID::fromString);
        } catch (RuntimeException e) {
            log.warn("redis unavailable for idempotency lookup, falling back to database", e);
            return Optional.empty();
        }
    }

    public void remember(String idempotencyKey, UUID jobId) {
        try {
            redis.opsForValue().setIfAbsent(KEY_PREFIX + idempotencyKey, jobId.toString(), ttl);
        } catch (RuntimeException e) {
            log.warn("redis unavailable, idempotency key not cached", e);
        }
    }
}
