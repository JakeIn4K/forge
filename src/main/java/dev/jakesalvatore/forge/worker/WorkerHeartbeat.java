package dev.jakesalvatore.forge.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Publishes this worker's liveness to Redis as a key with a TTL longer than
 * the beat interval, so a single delayed beat doesn't declare the worker dead
 * but a crashed process goes silent within one TTL. The reaper treats a
 * missing key as "worker is gone".
 */
@Component
@Profile("worker")
public class WorkerHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(WorkerHeartbeat.class);
    private static final String KEY_PREFIX = "forge:worker:";

    private final StringRedisTemplate redis;
    private final WorkerProperties properties;
    private final WorkerIdentity identity;

    public WorkerHeartbeat(StringRedisTemplate redis, WorkerProperties properties, WorkerIdentity identity) {
        this.redis = redis;
        this.properties = properties;
        this.identity = identity;
    }

    @Scheduled(fixedDelayString = "${forge.worker.heartbeat-interval}")
    public void beat() {
        try {
            redis.opsForValue().set(KEY_PREFIX + identity.id(), Instant.now().toString(),
                    properties.heartbeatTtl());
        } catch (RuntimeException e) {
            // a missed beat is tolerable: the TTL outlives several intervals
            log.warn("redis unavailable, heartbeat not published", e);
        }
    }

    /** Throws if Redis is unreachable — the caller decides how to fail. */
    public boolean isAlive(String workerId) {
        return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + workerId));
    }
}
