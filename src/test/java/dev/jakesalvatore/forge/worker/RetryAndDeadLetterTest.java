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
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 exit behavior for handler failures: a failing job is retried with
 * backoff until it succeeds, and a job that never succeeds is dead-lettered
 * after max_attempts.
 */
@SpringBootTest(properties = {
        "forge.retry.base-delay=50ms",
        "forge.retry.max-delay=200ms",
        "forge.worker.poll-interval=100ms"
})
@ActiveProfiles("worker")
@Import({TestcontainersConfiguration.class, RetryAndDeadLetterTest.FailingHandlersConfig.class})
class RetryAndDeadLetterTest {

    private static final ConcurrentHashMap<UUID, AtomicInteger> ATTEMPTS_SEEN = new ConcurrentHashMap<>();

    @Autowired
    private JobRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingHandlersConfig {

        @Bean
        JobHandler flakyHandler() {
            return new JobHandler() {
                @Override
                public String type() {
                    return "flaky";
                }

                @Override
                public void handle(Job job) {
                    int attempt = ATTEMPTS_SEEN
                            .computeIfAbsent(job.id(), id -> new AtomicInteger())
                            .incrementAndGet();
                    if (attempt < 3) {
                        throw new IllegalStateException("transient failure on attempt " + attempt);
                    }
                }
            };
        }

        @Bean
        JobHandler doomedHandler() {
            return new JobHandler() {
                @Override
                public String type() {
                    return "doomed";
                }

                @Override
                public void handle(Job job) {
                    throw new IllegalStateException("this job never succeeds");
                }
            };
        }
    }

    @Test
    void flakyJobSucceedsAfterRetries() throws Exception {
        var job = repository.insert(new NewJob("default", "flaky",
                objectMapper.createObjectNode(), 0, Instant.now(), 5, null)).orElseThrow();

        var finished = awaitStatus(job.id(), JobStatus.SUCCEEDED, 30_000);

        assertThat(finished.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(finished.attempts()).isEqualTo(3);
        assertThat(finished.lastError()).contains("transient failure");
    }

    @Test
    void jobThatNeverSucceedsIsDeadLettered() throws Exception {
        var job = repository.insert(new NewJob("default", "doomed",
                objectMapper.createObjectNode(), 0, Instant.now(), 2, null)).orElseThrow();

        var finished = awaitStatus(job.id(), JobStatus.DEAD, 30_000);

        assertThat(finished.status()).isEqualTo(JobStatus.DEAD);
        assertThat(finished.attempts()).isEqualTo(2);
        assertThat(finished.lastError()).contains("this job never succeeds");
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
