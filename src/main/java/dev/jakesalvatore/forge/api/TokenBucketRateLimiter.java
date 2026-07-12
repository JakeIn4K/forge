package dev.jakesalvatore.forge.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Token bucket in Redis, one bucket per API key. The whole
 * refill-then-consume step runs as a single Lua script, which Redis executes
 * atomically — two concurrent requests can never both spend the same token.
 * Tokens refill continuously (fractional), so the rate is smooth rather than
 * resetting in windows; capacity above the refill rate is the allowed burst.
 */
@Component
public class TokenBucketRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);
    private static final String KEY_PREFIX = "forge:ratelimit:";

    private static final RedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local capacity = tonumber(ARGV[1])
            local refill_per_ms = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens'))
            local ts = tonumber(redis.call('HGET', KEYS[1], 'ts'))
            if tokens == nil then
              tokens = capacity
              ts = now
            end
            tokens = math.min(capacity, tokens + (now - ts) * refill_per_ms)
            local allowed = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            end
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
            redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / refill_per_ms))
            return allowed
            """, Long.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;

    public TokenBucketRateLimiter(StringRedisTemplate redis, RateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public boolean tryConsume(String apiKey) {
        try {
            Long allowed = redis.execute(SCRIPT,
                    List.of(KEY_PREFIX + apiKey),
                    String.valueOf(properties.capacity()),
                    String.valueOf(properties.refillPerSecond() / 1000.0),
                    String.valueOf(System.currentTimeMillis()));
            return allowed == null || allowed == 1;
        } catch (RuntimeException e) {
            // fail open: dropping traffic because the limiter is down would
            // turn a Redis outage into an API outage
            log.warn("redis unavailable, rate limit not enforced", e);
            return true;
        }
    }
}
