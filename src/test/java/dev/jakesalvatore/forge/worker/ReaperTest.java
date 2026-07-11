package dev.jakesalvatore.forge.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jakesalvatore.forge.TestcontainersConfiguration;
import dev.jakesalvatore.forge.jobs.Job;
import dev.jakesalvatore.forge.jobs.JobRepository;
import dev.jakesalvatore.forge.jobs.JobStatus;
import dev.jakesalvatore.forge.jobs.NewJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The crash-recovery loop end to end: a job claimed by a worker that no
 * longer heartbeats is reclaimed after the visibility timeout and executed by
 * someone else — while a slow job on a live worker is left alone.
 */
@SpringBootTest(properties = {
        "forge.worker.visibility-timeout=1s",
        "forge.worker.reap-interval=300ms",
        "forge.worker.poll-interval=100ms",
        "forge.retry.base-delay=50ms",
        "forge.retry.max-delay=200ms"
})
@ActiveProfiles("worker")
@Import({TestcontainersConfiguration.class, ReaperTest.CountingHandlerConfig.class})
class ReaperTest {

    private static final ConcurrentHashMap<UUID, AtomicInteger> EXECUTIONS = new ConcurrentHashMap<>();

    @Autowired
    private JobRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ObjectMapper objectMapper;

    @TestConfiguration(proxyBeanMethods = false)
    static class CountingHandlerConfig {

        @Bean
        JobHandler countingHandler() {
            return new JobHandler() {
                @Override
                public String type() {
                    return "counting";
                }

                @Override
                public void handle(Job job) {
                    EXECUTIONS.computeIfAbsent(job.id(), id -> new AtomicInteger()).incrementAndGet();
                }
            };
        }
    }

    @Test
    void jobFromDeadWorkerIsReclaimedAndRerun() throws Exception {
        var job = insertJob(5);
        fakeStaleClaim(job.id(), "ghost:1");

        var finished = awaitStatus(job.id(), JobStatus.SUCCEEDED, 30_000);

        assertThat(finished.status()).isEqualTo(JobStatus.SUCCEEDED);
        // the crash consumed one attempt, the successful rerun another
        assertThat(finished.attempts()).isEqualTo(2);
        assertThat(finished.lastError()).contains("reclaimed");
        assertThat(EXECUTIONS.get(job.id()).get()).isEqualTo(1);
    }

    @Test
    void jobFromLiveWorkerIsLeftAlone() throws Exception {
        redis.opsForValue().set("forge:worker:alive:1", Instant.now().toString(), Duration.ofMinutes(1));
        var job = insertJob(5);
        fakeStaleClaim(job.id(), "alive:1");

        // several reap passes worth of time
        Thread.sleep(1_500);

        assertThat(repository.findById(job.id()).orElseThrow().status()).isEqualTo(JobStatus.CLAIMED);
    }

    @Test
    void reclaimThatConsumesTheLastAttemptDeadLetters() throws Exception {
        var job = insertJob(1);
        fakeStaleClaim(job.id(), "ghost:2");

        var finished = awaitStatus(job.id(), JobStatus.DEAD, 30_000);

        assertThat(finished.status()).isEqualTo(JobStatus.DEAD);
        assertThat(finished.attempts()).isEqualTo(1);
        assertThat(EXECUTIONS.get(job.id())).isNull();
    }

    private Job insertJob(int maxAttempts) {
        // run_at is far in the future so no worker claims the job before the
        // fake stale claim is stamped on; reclaim resets run_at for the rerun
        return repository.insert(new NewJob("default", "counting",
                objectMapper.createObjectNode(), 0, Instant.now().plus(Duration.ofHours(1)),
                maxAttempts, null)).orElseThrow();
    }

    /** Stamps the row as if a worker claimed it long ago and then vanished. */
    private void fakeStaleClaim(UUID id, String workerId) {
        jdbc.sql("""
                        UPDATE jobs
                        SET status = 'CLAIMED', claimed_by = :workerId, claimed_at = now() - interval '1 hour'
                        WHERE id = :id
                        """)
                .param("workerId", workerId)
                .param("id", id)
                .update();
    }

    private Job awaitStatus(UUID id, JobStatus expected, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        Job job = repository.findById(id).orElseThrow();
        while (System.currentTimeMillis() < deadline && job.status() != expected) {
            Thread.sleep(200);
            job = repository.findById(id).orElseThrow();
        }
        return job;
    }
}
